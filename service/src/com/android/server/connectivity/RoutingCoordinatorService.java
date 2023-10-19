/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.connectivity;

import static com.android.net.module.util.NetdUtils.toRouteInfoParcel;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.INetd;
import android.net.IRoutingCoordinator;
import android.net.MulticastRoutingConfig;
import android.net.RouteInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.ArraySet;
import android.system.Os;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.net.module.util.CollectionUtils;
import com.android.net.module.util.LinkPropertiesUtils.CompareResult;
import com.android.net.module.util.PacketReader;
import com.android.net.module.util.Struct;
import com.android.server.connectivity.StructMrt6Msg;

import static android.net.MulticastRoutingConfig.FORWARD_NONE;
import static android.net.MulticastRoutingConfig.FORWARD_WITH_MIN_SCOPE;
import static android.net.MulticastRoutingConfig.FORWARD_SELECTED;
import static com.android.net.module.util.NetdUtils.toRouteInfoParcel;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.Arrays; 
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;




public class RoutingCoordinatorService extends IRoutingCoordinator.Stub {
    // uses library 'service-connectivity' for native functions

    private static final String TAG = RoutingCoordinatorService.class.getSimpleName();

    private final Context mContext;
    private final INetd mNetd;

    private ParcelFileDescriptor mMulticastRoutingFD;
    private final HandlerThread mHandlerThread;
    private final HandlerThread mListenerThread;
    private final Handler mPacketListenerHandler;
    private final Handler mHandler;
    private final PacketListener mPacketListener;

    private final int DYNAMIC_MFC_TIMEOUT_MS = 1000;
    private final int STATIC_MFC_TIMEOUT_MS = 300000;

    private Map<String, Byte> vifIdx = new HashMap<String, Byte>();
    private Map<Byte, String> vifIdxToIfName = new HashMap<>();
    private static byte nextVifIdx = 1;

    private byte getVifIdx(String ifName) {
        if (vifIdx.get(ifName) == null) {
            vifIdx.put(ifName, nextVifIdx);
            vifIdxToIfName.put(nextVifIdx, ifName);
            int ifIndex = getInterfaceIndex(ifName);
            nativeAddMif(mMulticastRoutingFD.getFileDescriptor(), nextVifIdx, ifIndex);
            nextVifIdx++;
        }
        return vifIdx.get(ifName);
    }

    private String getIfName(byte vifIdx) {
        if (vifIdxToIfName.get(vifIdx) != null) {
            return vifIdxToIfName.get(vifIdx);
        }
        return "";
    }

    private class MulticastRoutingConfigIif {
        // mapping of oif name to MulticastRoutingConfig
        Map<String, MulticastRoutingConfig> oifConfig = new HashMap<String, MulticastRoutingConfig>();

    };

    private Map<String, MulticastRoutingConfigIif> mMulticastRoutingConfigs = new HashMap<String, MulticastRoutingConfigIif>();

    private MulticastRoutingConfigIif getMulticastRoutingConfigsForIif(String iifName) {
        return mMulticastRoutingConfigs.get(iifName);
    }

    private MulticastRoutingConfig getMulticastRoutingConfig(String iifName, String oifName) {
        MulticastRoutingConfigIif configs = getMulticastRoutingConfigsForIif(iifName);
        final MulticastRoutingConfig defaultConfig = MulticastRoutingConfig.CONFIG_FORWARD_NONE;
        if (configs == null) {
            return defaultConfig;
        }
        else {
            return configs.oifConfig.getOrDefault(oifName, defaultConfig);
        }
    }

    private void setMulticastRoutingConfig(final String iifName, final String oifName, final MulticastRoutingConfig config) {
        MulticastRoutingConfigIif iifConfigs = getMulticastRoutingConfigsForIif(iifName);
        if (iifConfigs == null) {
            iifConfigs = new MulticastRoutingConfigIif();
            mMulticastRoutingConfigs.put(iifName, iifConfigs);
        }
        iifConfigs.oifConfig.put(oifName, config);
    }


    // If netd is null, will fetch it from the context. The argument is here for
    // dependency injection.
    public RoutingCoordinatorService(Context context, @Nullable INetd netd) {
        mContext = context;
        mNetd = null != netd ? netd
                : INetd.Stub.asInterface((IBinder) context.getSystemService(Context.NETD_SERVICE));
    
        mHandlerThread = new HandlerThread("HandlerThread");
        mListenerThread = new HandlerThread("ListenerThread");
        mHandlerThread.start();
        mListenerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mMulticastRoutingFD = ParcelFileDescriptor.adoptFd(initMulticastSocket());
        mPacketListenerHandler = new Handler(mListenerThread.getLooper());
        mPacketListener = new PacketListener(mPacketListenerHandler, mMulticastRoutingFD.getFileDescriptor());
        mPacketListenerHandler.post(startListener);
    }

    /**
     * Add a route for specific network
     *
     * @param netId the network to add the route to
     * @param route the route to add
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *         cause of the failure.
     */
    @Override
    public void addRoute(final int netId, final RouteInfo route)
            throws ServiceSpecificException, RemoteException {
        //mNetd.networkAddRouteParcel(netId, toRouteInfoParcel(route));
    }

    /**
     * Remove a route for specific network
     *
     * @param netId the network to remove the route from
     * @param route the route to remove
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *         cause of the failure.
     */
    @Override
    public void removeRoute(final int netId, final RouteInfo route)
            throws ServiceSpecificException, RemoteException {
        //mNetd.networkRemoveRouteParcel(netId, toRouteInfoParcel(route));
    }

    /**
     * Update a route for specific network
     *
     * @param netId the network to update the route for
     * @param route parcelable with route information
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *         cause of the failure.
     */
    @Override
    public void updateRoute(final int netId, final RouteInfo route)
            throws ServiceSpecificException, RemoteException {
        mNetd.networkUpdateRouteParcel(netId, toRouteInfoParcel(route));
    }

    /**
     * Adds an interface to a network. The interface must not be assigned to any network, including
     * the specified network.
     *
     * @param netId the network to add the interface to.
     * @param iface the name of the interface to add.
     *
     * @throws ServiceSpecificException in case of failure, with an error code corresponding to the
     *         unix errno.
     */
    @Override
    public void addInterfaceToNetwork(final int netId, final String iface)
            throws ServiceSpecificException, RemoteException {
        mNetd.networkAddInterface(netId, iface);
    }

    /**
     * Removes an interface from a network. The interface must be assigned to the specified network.
     *
     * @param netId the network to remove the interface from.
     * @param iface the name of the interface to remove.
     *
     * @throws ServiceSpecificException in case of failure, with an error code corresponding to the
     *         unix errno.
     */
    @Override
    public void removeInterfaceFromNetwork(final int netId, final String iface)
            throws ServiceSpecificException, RemoteException {
        mNetd.networkRemoveInterface(netId, iface);
    }


    private final Object mIfacesLock = new Object();
    private class ForwardingPair {
        @NonNull public final String fromIface;
        @NonNull public final String toIface;
        public boolean enableNat;
        ForwardingPair(@NonNull final String fromIface, @NonNull final String toIface,
                final boolean enableNat) {
            this.fromIface = fromIface;
            this.toIface = toIface;
            this.enableNat = enableNat;
        }

        // Note that equals() and hashcode() only use fromIface and toIface, not enableNat.
        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            final ForwardingPair that = (ForwardingPair) o;

            return fromIface.equals(that.fromIface) && toIface.equals(that.toIface);
        }

        @Override
        public int hashCode() {
            int result = fromIface.hashCode();
            result = 2 * result + toIface.hashCode();
            return result;
        }
    }

    @GuardedBy("mIfacesLock")
    private final ArraySet<ForwardingPair> mForwardedInterfaces = new ArraySet<>();

    /**
     * Add forwarding ip rule
     *
     * @param fromIface interface name to add forwarding ip rule
     * @param toIface interface name to add forwarding ip rule
     * @param enableNat whether to enable NAT between these two interfaces
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *         cause of the failure.
     */
    public void addInterfaceForward(final String fromIface, final String toIface,
            final boolean enableNat) throws ServiceSpecificException, RemoteException {
        Objects.requireNonNull(fromIface);
        Objects.requireNonNull(toIface);
        synchronized (mIfacesLock) {
            if (mForwardedInterfaces.size() == 0) {
                mNetd.ipfwdEnableForwarding("RoutingCoordinator");
            }
            final ForwardingPair fwp = new ForwardingPair(fromIface, toIface, enableNat);
            // enableNat is ignored by ForwardingPair#equals and #hashcode.
            if (mForwardedInterfaces.contains(fwp)) {
                throw new IllegalStateException("Forward already exists between ifaces "
                        + fromIface + " → " + toIface);
            }
            mForwardedInterfaces.add(fwp);
            if (enableNat) mNetd.tetherAddForward(fromIface, toIface);
            mNetd.ipfwdAddInterfaceForward(fromIface, toIface);
        }
    }

    /**
     * Remove forwarding ip rule
     *
     * @param fromIface interface name to remove forwarding ip rule
     * @param toIface interface name to remove forwarding ip rule
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *         cause of the failure.
     */
    public void removeInterfaceForward(final String fromIface, final String toIface)
            throws ServiceSpecificException, RemoteException {
        Objects.requireNonNull(fromIface);
        Objects.requireNonNull(toIface);
        synchronized (mIfacesLock) {
            final ForwardingPair fwp = CollectionUtils.findFirst(mForwardedInterfaces,
                    p -> fromIface.equals(p.fromIface) && toIface.equals(p.toIface));
            if (null == fwp) {
                throw new IllegalStateException("No forward set up between interfaces "
                        + fromIface + " → " + toIface);
            }
            mForwardedInterfaces.remove(fwp);
            mNetd.ipfwdRemoveInterfaceForward(fromIface, toIface);
            if (fwp.enableNat) mNetd.tetherRemoveForward(fromIface, toIface);
            if (mForwardedInterfaces.size() == 0) {
                mNetd.ipfwdDisableForwarding("RoutingCoordinator");
            }
        }
    }

    private void RemoveObsoleteMfcs(final String iifName, final String oifName,
                                    final MulticastRoutingConfig config) {
        Iterator<Map.Entry<MfcKey, MfcValue>> it = mMfcs.entrySet().iterator();
        byte iifIdx = getVifIdx(iifName);
        byte oifIdx = getVifIdx(oifName);
        int forwardingMode = config.getForwardingMode();
        int minScope = config.getMinimumScope();
        Set<Inet6Address> addresses = config.getListeningAddresses();
        while (it.hasNext()) {
            Map.Entry<MfcKey, MfcValue> mfcEntry = it.next();
            MfcKey key = mfcEntry.getKey();
            MfcValue value = mfcEntry.getValue();
            int iif = key.getIifIndex();
            byte[] dst = key.getDstAddress();
            Inet6Address address;
            try {
                address = (Inet6Address) Inet6Address.getByAddress(dst);
            
                int dstScope = dst[1] & 0xf;
                if (iif == iifIdx && value.containsOifIdx(oifIdx)) {
                    if ( forwardingMode == FORWARD_NONE ||
                        (forwardingMode == FORWARD_SELECTED && !addresses.contains(address)) ||
                        (forwardingMode == FORWARD_WITH_MIN_SCOPE && dstScope < minScope)
                       ) {
                            removeOif(oifIdx, mfcEntry, it);
                        }
                    
                }
            } catch (UnknownHostException e) {
                Log.wtf(TAG, "Can't read inet6address : " + dst);
            }
        }
    }

    private void doApplyMulticastRoutingConfig(final String iifName, final String oifName,
                                            final MulticastRoutingConfig newConfig) {

        // Make sure if and oif have multicast virtual indexes;
        byte iifVirtualIdx = getVifIdx(iifName);
        byte oifVirtualIdx = getVifIdx(oifName);

        final MulticastRoutingConfig oldConfig = getMulticastRoutingConfig(iifName, oifName);
        
        if (oldConfig.equals(newConfig)) return;

        Log.d(TAG, "Applying multicast routing config for iif " + iifName + " to oif " + oifName
        + " with Config " + newConfig.toString());

        int oldMode = oldConfig.getForwardingMode();
        int newMode = newConfig.getForwardingMode();
        byte[] anyAddr = new byte[16];
        byte[] oifs = new byte[1];
        oifs[0] = getVifIdx(oifName);
        int iifIndex = getInterfaceIndex(iifName);

        if (oldMode != newMode) {
            if (newMode == FORWARD_SELECTED) {
                ArrayList<Inet6Address> listeningAddresses = new ArrayList<>(newConfig.getListeningAddresses());
                for(int x = 0; x < listeningAddresses.size(); x++) {
                    byte[] groupAddressBytes = listeningAddresses.get(x).getAddress();
                    addMfc(getVifIdx(iifName), oifs, anyAddr, groupAddressBytes, Instant.MAX, true);
                    nativeJoinGroup(mMulticastRoutingFD.getFileDescriptor(), iifIndex, groupAddressBytes);
                }
            }
            else if (oldMode == FORWARD_SELECTED) {
                ArrayList<Inet6Address> listeningAddresses = new ArrayList<>(oldConfig.getListeningAddresses());
                for(int x = 0; x < listeningAddresses.size(); x++) {
                    nativeLeaveGroup(mMulticastRoutingFD.getFileDescriptor(), iifIndex,
                                listeningAddresses.get(x).getAddress());
                }
            }
        }
        else if (newMode == FORWARD_SELECTED) {
            final CompareResult<Inet6Address> addressDiff = new CompareResult<>(
                oldConfig.getListeningAddresses(), newConfig.getListeningAddresses());
            for (final Inet6Address added : addressDiff.added) {
                addMfc(getVifIdx(iifName), oifs, anyAddr, added.getAddress(), Instant.MAX, true);
                nativeJoinGroup(mMulticastRoutingFD.getFileDescriptor(), iifIndex,
                                added.getAddress());
            }
            for (final Inet6Address removed : addressDiff.removed) {
                nativeLeaveGroup(mMulticastRoutingFD.getFileDescriptor(), iifIndex,
                                removed.getAddress());
            }
        }

        setMulticastRoutingConfig(iifName, oifName, newConfig);

        RemoveObsoleteMfcs(iifName, oifName, newConfig);
    }
    /**
     * Apply multicast routing configuration
     *
     * @param iifName interface name of the incoming interface
     * @param oifName interface name of the outgoing interface
     * @param newConfig the multicast routing configuration to be applied from iif to oif
     */
    public void applyMulticastRoutingConfig(final String iifName, final String oifName,
                final MulticastRoutingConfig newConfig) {
        mHandler.post(() -> doApplyMulticastRoutingConfig(iifName, oifName, newConfig));
    }

    private class MfcKey {
        private int mIifVirtualIdx;
        private byte[] mSrcAddr;
        private byte[] mDstAddr;
        private boolean mIsStatic;

        public MfcKey(int iif, byte[] src, byte[] dst, boolean isStatic) {
            mIifVirtualIdx = iif;
            mSrcAddr = src;
            mDstAddr = dst;
            mIsStatic = isStatic;
        }

        public boolean equals(Object other) {
            if (other == this) {
                return true;
            }
            else if (!(other instanceof MfcKey)) {
                return false;
            }
            else {
                MfcKey otherKey = (MfcKey) other;
                return mIifVirtualIdx == otherKey.mIifVirtualIdx
                    && mSrcAddr == otherKey.mSrcAddr
                    && mDstAddr == otherKey.mDstAddr
                    && mIsStatic == otherKey.mIsStatic;
            }
        }

        public int hashCode() {
            return Objects.hash(mIifVirtualIdx, mSrcAddr, mDstAddr, mIsStatic);
        }

        public int getIifIndex() {
            return mIifVirtualIdx;
        }

        public byte[] getSrcAddress() {
            return mSrcAddr;
        }
        
        public byte[] getDstAddress() {
            return mDstAddr;
        }

        public boolean getIsStatic() {
            return mIsStatic;
        }

    }

    private class MfcValue {
        private byte[] mOifVirtualIdxes;
        private Instant mExpiresAt;
        

        public MfcValue(byte oif, Instant expire) {
            byte[] oifs = new byte[]{oif};
            mOifVirtualIdxes = oifs;
            mExpiresAt = expire;
        }

        public MfcValue(byte[] oifs, Instant expire) {
            mOifVirtualIdxes = oifs;
            mExpiresAt = expire;
        }

        public boolean containsOifIdx(byte idx) {
            for (byte b : mOifVirtualIdxes) {
                if (b == idx) {
                    return true;
                }
            }
            return false;
        }

        public void addOif(byte idx) {
            if (!containsOifIdx(idx)) {
                byte[] oifs = Arrays.copyOf(mOifVirtualIdxes, mOifVirtualIdxes.length + 1);
                oifs[mOifVirtualIdxes.length] = idx;
                mOifVirtualIdxes = oifs;
            }
        }

        public void removeOif(byte idx) {
            if (containsOifIdx(idx)) {
                byte[] updatedOifs = new byte[mOifVirtualIdxes.length - 1];
                for (int i = 0, k = 0; i < mOifVirtualIdxes.length; i++) {
                    if (mOifVirtualIdxes[i] == idx) {
                        continue;
                    }
                    updatedOifs[k++] = mOifVirtualIdxes[i];
                }
                mOifVirtualIdxes = updatedOifs;
            }
        }

        public byte[] getOifIndexes() {
            return mOifVirtualIdxes;
        }

        public Instant getExpiryTime() {
            return mExpiresAt;
        }
        
        public void setExpiryTime(Instant expiresAt) {
            mExpiresAt = expiresAt;
        }

    }

    // The mMfcs map keeps track of the MFCs added to kernel.
    private HashMap<MfcKey, MfcValue> mMfcs = new HashMap<>();

    private void addMfc(int iif, byte[] oifs, byte[] src, byte[] dst, Instant expiresAt, boolean isStatic) {
        MfcKey key = new MfcKey(iif, src, dst, isStatic);
        MfcValue value = mMfcs.get(key);

        if (value == null) {
            value = new MfcValue(oifs, expiresAt);
            mMfcs.put(key, value);
        }
        else {
            boolean added = false;
            for (byte oif : oifs) {
                if (!value.containsOifIdx(oif)) {
                    value.addOif(oif);
                    value.setExpiryTime(expiresAt);
                    added = true;
                }
            }
            if (!added) {
                // all oifs are already included in existing MFC
                return;
            }
        }

        nativeAddMfc(mMulticastRoutingFD.getFileDescriptor(), key.getIifIndex(), value.getOifIndexes(),
                    key.getSrcAddress(), key.getDstAddress(), key.getIsStatic());
    }

    private void removeOif(byte oif, Map.Entry<MfcKey, MfcValue> mfcEntry, Iterator<Map.Entry<MfcKey, MfcValue>> it) {
        MfcKey key = mfcEntry.getKey();
        MfcValue value = mfcEntry.getValue();
        byte[] oifs = value.getOifIndexes();
        if (oifs.length == 1 && oifs[0] == oif) {
            // only oif is included, just remove the MFC
            removeMfc(mfcEntry, it);
        }
        else if (value.containsOifIdx(oif)) {
            // oif is included together with other oifs, remove it from the list and update the MFC in kernel
            value.removeOif(oif);
            nativeAddMfc(mMulticastRoutingFD.getFileDescriptor(), key.getIifIndex(), value.getOifIndexes(),
                        key.getSrcAddress(), key.getDstAddress(), key.getIsStatic());
        }
    }
    
    private void removeMfc(Map.Entry<MfcKey, MfcValue> mfcEntry, Iterator<Map.Entry<MfcKey, MfcValue>> it) {
        MfcKey key = mfcEntry.getKey();
        nativeRemoveMfc(mMulticastRoutingFD.getFileDescriptor(), key.getIifIndex(), key.getSrcAddress(),
                        key.getDstAddress(), key.getIsStatic());
        it.remove();
    }

    private void cleanupExpiredMfcs() {
        Iterator<Map.Entry<MfcKey, MfcValue>> it = mMfcs.entrySet().iterator();
        Instant now = Instant.now();
        while (it.hasNext()) {
            Map.Entry<MfcKey, MfcValue> mfcEntry = it.next();
            if (mfcEntry.getValue().getExpiryTime().isBefore(now)) {
                removeMfc(mfcEntry, it);
            }
        }
    }

    Runnable startListener = new Runnable() {
        public void run() {
            mPacketListener.start();
        }
    };

    private int initMulticastSocket() {
        int sock = -1;
        try {
            sock = nativeConfigMulticastSocket();
            Log.i(TAG, "socket created for multicast routing ");
        } catch (IOException e) {
            Log.e(TAG, "failed to create multicast socket: " + e);
        }
        return sock;
    }

    private int getGroupAddressScope(Inet6Address address) {
        return address.getAddress()[1] & 0xf;
    }

    private void handleMessage(final StructMrt6Msg mrt6Msg) {
        final int dstScope = getGroupAddressScope(mrt6Msg.mDst);
        final int iifVid = mrt6Msg.mMif;
        MulticastRoutingConfigIif iinfo = getMulticastRoutingConfigsForIif(getIfName((byte) iifVid));

        ArrayList<Byte> forwardingOifs = new ArrayList<Byte>();
        for (Map.Entry<String, MulticastRoutingConfig> config : iinfo.oifConfig.entrySet()) {
            if (config.getValue().getForwardingMode() == FORWARD_WITH_MIN_SCOPE
                && config.getValue().getMinimumScope() <= dstScope) {
                forwardingOifs.add(getVifIdx(config.getKey()));
            }
        }
        if (forwardingOifs.size() > 0) {
            byte[] oifs = new byte[forwardingOifs.size()];
            for (int j = 0; j < forwardingOifs.size(); j++) {
                oifs[j] = (byte) forwardingOifs.get(j);
            }
            byte[] anyAddr = new byte[16];
            // add dynamic MFC to forward the packet
            addMfc(iifVid, oifs, mrt6Msg.mSrc.getAddress(), mrt6Msg.mDst.getAddress(), Instant.now().plusMillis(DYNAMIC_MFC_TIMEOUT_MS), false);
            mHandler.postDelayed(
                    () -> cleanupExpiredMfcs(),
                    DYNAMIC_MFC_TIMEOUT_MS
            );
            // add static MFC to forward upcoming packets to the same group address
            addMfc(iifVid, oifs, anyAddr, mrt6Msg.mDst.getAddress(), Instant.now().plusMillis(STATIC_MFC_TIMEOUT_MS), true);
            mHandler.postDelayed(
                    () -> cleanupExpiredMfcs(),
                    STATIC_MFC_TIMEOUT_MS
            );
        }
    }

    private final class PacketListener extends PacketReader {
        private final FileDescriptor mFd;

        public PacketListener(Handler h, FileDescriptor fd) {
            super(h);
            mFd = fd;
        }

        @Override
        protected FileDescriptor createFd() {
            return mFd;
        }

        @Override
        protected void handlePacket(byte[] recvbuf, int length) {

            final ByteBuffer buf = ByteBuffer.wrap(recvbuf);
            final StructMrt6Msg mrt6Msg = Struct.parse(StructMrt6Msg.class, buf);

            Log.d(TAG, "Got nocache upcall in packetreader " + mrt6Msg.toString());

            mHandler.post(() -> handleMessage(mrt6Msg));
        }
    }

    private static native int nativeConfigMulticastSocket() throws IOException;
    private static native void nativeAddMif(FileDescriptor fd, int virtualIfIndex, int ifIndex);
    private static native void nativeAddMfc(FileDescriptor fd, int iif, byte[] oifs, byte[] src, byte[] dst, boolean isStatic);
    private static native void nativeRemoveMfc(FileDescriptor fd, int iif, byte[] src, byte[] dst, boolean isStatic);
    private static native void nativeJoinGroup(FileDescriptor fd, int ifIndex, byte[] addr);
    private static native void nativeLeaveGroup(FileDescriptor fd, int ifIndex, byte[] addr);

    private static Integer getInterfaceIndex(String ifname) {
        try {
            NetworkInterface ni = NetworkInterface.getByName(ifname);
            return ni.getIndex();
        } catch (NullPointerException | SocketException e) {
            return null;
        }
    }
}
