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

import static android.net.MulticastRoutingConfig.FORWARD_NONE;
import static android.net.MulticastRoutingConfig.FORWARD_SELECTED;
import static android.net.MulticastRoutingConfig.FORWARD_WITH_MIN_SCOPE;
import static android.system.OsConstants.NETLINK_ROUTE;

import static com.android.net.module.util.NetdUtils.toRouteInfoParcel;

import android.annotation.NonNull;
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
import android.system.ErrnoException;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.CollectionUtils;
import com.android.net.module.util.LinkPropertiesUtils.CompareResult;
import com.android.net.module.util.netlink.NetlinkUtils;
import com.android.net.module.util.netlink.RtNetlinkMulticastRouteDumpMessage;
import com.android.net.module.util.PacketReader;
import com.android.net.module.util.Struct;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Class to coordinate routing across multiple clients.
 *
 * At present this is just a wrapper for netd methods, but it will be used to host some more
 * coordination logic in the near future. It can be used to pull up some of the routing logic
 * from netd into Java land.
 *
 * Note that usage of this class is not thread-safe. Clients are responsible for their own
 * synchronization.
 */
public class RoutingCoordinatorService extends IRoutingCoordinator.Stub {
    static {
        System.loadLibrary("service-connectivity");
    }
    private static final String TAG = RoutingCoordinatorService.class.getSimpleName();
    private final INetd mNetd;
    private final Dependencies mDependencies;

    private final HandlerThread mHandlerThread;
    private final HandlerThread mListenerThread;
    private final Handler mListenerHandler;
    private final Handler mHandler;
    private final MulticastNocacheUpcallListener mMulticastNocacheUpcallListener;
    private ParcelFileDescriptor mMulticastRoutingFD;

    private final int MFC_INACTIVE_CHECK_INTERVAL_MS = 60000;
    private final int MFC_INACTIVE_EXPIRE_TIMEOUT_MS = 300000;
    private final int MFC_INACTIVE_EXPIRE_TIMEOUT_USER_HZ = 300000;
    @VisibleForTesting
    public final int MFC_MAX_NUMBER_OF_ENTRIES = 10000;

    private Map<String, Integer> mVirtualInterfaceIndexMap = new HashMap<>();
    private SparseArray<String> mVirtualInterfaceNameMap = new SparseArray<>();

    private Map<String, MulticastRoutingConfigIif> mMulticastRoutingConfigs =
            new HashMap<String, MulticastRoutingConfigIif>();

    // The mMfcs map keeps track of the MFCs added to kernel.
    private Map<MfcKey, MfcValue> mMfcs = new LinkedHashMap<>();

    private static final class MulticastRoutingConfigIif {
        // mapping of oif name to MulticastRoutingConfig
        Map<String, MulticastRoutingConfig> oifConfig =
                new HashMap<String, MulticastRoutingConfig>();
    }

    public RoutingCoordinatorService(@NonNull INetd netd) {
        this(netd, new Dependencies());
    }

    @VisibleForTesting
    public RoutingCoordinatorService(
            @NonNull INetd netd, Dependencies dependencies) {
        mNetd = netd;
        mDependencies = dependencies;
        mMulticastRoutingFD = initMulticastSocket();
        if (mMulticastRoutingFD == null) {
            mHandlerThread = null;
            mListenerThread = null;
            mListenerHandler = null;
            mHandler = null;
            mMulticastNocacheUpcallListener = null;
            return;
        }
        mHandlerThread = new HandlerThread("HandlerThread");
        mListenerThread = new HandlerThread("ListenerThread");
        mHandlerThread.start();
        mListenerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mListenerHandler = new Handler(mListenerThread.getLooper());
        mMulticastNocacheUpcallListener =
                new MulticastNocacheUpcallListener(mListenerHandler,
                         mMulticastRoutingFD.getFileDescriptor());
        mListenerHandler.post(() -> mMulticastNocacheUpcallListener.start());
        mHandler.postDelayed(() -> checkMfcsExpiration(), MFC_INACTIVE_CHECK_INTERVAL_MS);
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
        mNetd.networkAddRouteParcel(netId, toRouteInfoParcel(route));
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
        mNetd.networkRemoveRouteParcel(netId, toRouteInfoParcel(route));
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
    private static final class ForwardingPair {
        @NonNull public final String fromIface;
        @NonNull public final String toIface;
        ForwardingPair(@NonNull final String fromIface, @NonNull final String toIface) {
            this.fromIface = fromIface;
            this.toIface = toIface;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (!(o instanceof ForwardingPair)) return false;

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
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *         cause of the failure.
     */
    public void addInterfaceForward(final String fromIface, final String toIface)
            throws ServiceSpecificException, RemoteException {
        Objects.requireNonNull(fromIface);
        Objects.requireNonNull(toIface);
        Log.i(TAG, "Adding interface forward " + fromIface + " → " + toIface);
        synchronized (mIfacesLock) {
            if (mForwardedInterfaces.size() == 0) {
                mNetd.ipfwdEnableForwarding("RoutingCoordinator");
            }
            final ForwardingPair fwp = new ForwardingPair(fromIface, toIface);
            if (mForwardedInterfaces.contains(fwp)) {
                throw new IllegalStateException("Forward already exists between ifaces "
                        + fromIface + " → " + toIface);
            }
            mForwardedInterfaces.add(fwp);
            // Enables NAT for v4 and filters packets from unknown interfaces
            mNetd.tetherAddForward(fromIface, toIface);
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
        Log.i(TAG, "Removing interface forward " + fromIface + " → " + toIface);
        synchronized (mIfacesLock) {
            final ForwardingPair fwp = new ForwardingPair(fromIface, toIface);
            if (!mForwardedInterfaces.contains(fwp)) {
                // This can happen when an upstream was unregisteredAfterReplacement. The forward
                // is removed immediately when the upstream is destroyed, but later when the
                // network actually disconnects CS does not know that and it asks for removal
                // again.
                // This can also happen if the network was destroyed before being set as an
                // upstream, because then CS does not set up the forward rules seeing how the
                // interface was removed anyway.
                // Either way, this is benign.
                Log.i(TAG, "No forward set up between interfaces " + fromIface + " → " + toIface);
                return;
            }
            mForwardedInterfaces.remove(fwp);
            try {
                mNetd.ipfwdRemoveInterfaceForward(fromIface, toIface);
            } catch (RemoteException | ServiceSpecificException e) {
                Log.e(TAG, "Exception in ipfwdRemoveInterfaceForward", e);
            }
            try {
                mNetd.tetherRemoveForward(fromIface, toIface);
            } catch (RemoteException | ServiceSpecificException e) {
                Log.e(TAG, "Exception in tetherRemoveForward", e);
            }
            if (mForwardedInterfaces.size() == 0) {
                mNetd.ipfwdDisableForwarding("RoutingCoordinator");
            }
        }
    }

    private void UpdateMfcs() {
        ArrayList<MfcKey> keysToRemove = new ArrayList<>();
        mMfcs.forEach((key, value) -> {
                MfcValue updatedValue =
                        addOrUpdateMfc(key.getIifVirtualIndex(),
                                       key.getSourceAddress(),
                                       key.getDestinationAddress());
                if (updatedValue == null) {
                    keysToRemove.add(key);
                }
            });
        for (MfcKey keyToRemove : keysToRemove) {
            removeMfc(keyToRemove);
        }
        refreshMfcInactiveDuration();
    }

    private void joinGroup(FileDescriptor fd, int ifIndex, byte[] address) {
        try {
            mDependencies.joinGroup(fd, ifIndex, address);
        } catch (IOException e) {
            Log.e(TAG, "failed to join group: " + e);
        }
    }

    private void leaveGroup(FileDescriptor fd, int ifIndex, byte[] address) {
        try {
            mDependencies.leaveGroup(fd, ifIndex, address);
        } catch (IOException e) {
            Log.e(TAG, "failed to leave group: " + e);
        }
    }

    private void doApplyMulticastRoutingConfig(
            final String iifName, final String oifName, final MulticastRoutingConfig newConfig) {

        // Make sure iif and oif are added as multicast forwarding interfaces;
        maybeAddVirtualInterfaceIndexNameMapping(iifName);
        maybeAddVirtualInterfaceIndexNameMapping(oifName);

        final MulticastRoutingConfig oldConfig = getMulticastRoutingConfig(iifName, oifName);

        if (oldConfig.equals(newConfig)) return;

        int oldMode = oldConfig.getForwardingMode();
        int newMode = newConfig.getForwardingMode();
        byte[] anyAddr = new byte[16];
        int[] oifs = new int[1];
        oifs[0] = getVirtualInterfaceIndex(oifName);
        int iifIndex = mDependencies.getInterfaceIndex(iifName);

        // When new addresses are added to FORWARD_SELECTED mode, join these multicast groups
        // on their upstream interface, so upstream multicast routers know about the subscription.
        // When addresses are removed from FORWARD_SELECTED mode, leave the multicast groups.
        if (oldMode != newMode) {
            if (newMode == FORWARD_SELECTED) {
                ArrayList<Inet6Address> listeningAddresses =
                        new ArrayList<>(newConfig.getListeningAddresses());
                for (int x = 0; x < listeningAddresses.size(); x++) {
                    byte[] groupAddressBytes = listeningAddresses.get(x).getAddress();
                    joinGroup(mMulticastRoutingFD.getFileDescriptor(), iifIndex, groupAddressBytes);
                }
            } else if (oldMode == FORWARD_SELECTED) {
                ArrayList<Inet6Address> listeningAddresses =
                        new ArrayList<>(oldConfig.getListeningAddresses());
                for (int x = 0; x < listeningAddresses.size(); x++) {
                    leaveGroup(
                            mMulticastRoutingFD.getFileDescriptor(),
                            iifIndex,
                            listeningAddresses.get(x).getAddress());
                }
            }
        } else if (newMode == FORWARD_SELECTED) {
            final CompareResult<Inet6Address> addressDiff =
                    new CompareResult<>(
                            oldConfig.getListeningAddresses(), newConfig.getListeningAddresses());
            for (final Inet6Address added : addressDiff.added) {
                joinGroup(mMulticastRoutingFD.getFileDescriptor(), iifIndex, added.getAddress());
            }
            for (final Inet6Address removed : addressDiff.removed) {
                leaveGroup(mMulticastRoutingFD.getFileDescriptor(), iifIndex, removed.getAddress());
            }
        }

        setMulticastRoutingConfig(iifName, oifName, newConfig);
        Log.d(
                TAG,
                "Applied multicast routing config for iif "
                        + iifName
                        + " to oif "
                        + oifName
                        + " with Config "
                        + newConfig.toString());

        // Update existing MFCs to make sure they align with the updated configuration
        UpdateMfcs();
    }

    /**
     * Apply multicast routing configuration
     *
     * @param iifName name of the incoming interface
     * @param oifName name of the outgoing interface
     * @param newConfig the multicast routing configuration to be applied from iif to oif
     */
    public void applyMulticastRoutingConfig(
            final String iifName, final String oifName, final MulticastRoutingConfig newConfig) {
        if (mMulticastRoutingFD == null) return;
        mHandler.post(() -> doApplyMulticastRoutingConfig(iifName, oifName, newConfig));
    }

    private void doRemoveInterfaceFromMulticastRouting(final String ifName) {
        final Integer virtualIndex = getVirtualInterfaceIndex(ifName);
        if (virtualIndex == null) return;

        removeInterfaceFromConfigs(ifName);
        UpdateMfcs();
        try {
            mDependencies.removeMulticastInterface(mMulticastRoutingFD.getFileDescriptor(),
                    virtualIndex);
        } catch (IOException e) {
            Log.e(TAG, "failed to remove multicast virtual interface", e);
            return;
        }
        removeVirtualInterfaceIndexNameMapping(virtualIndex, ifName);
    }

    /**
     * Removes an network interface from multicast routing.
     *
     * Remove the network interface from multicast configs and remove it from the list of
     * multicast routing interfaces in the kernel
     *
     * @param ifName name of the interface that should be removed
     */
    public void removeInterfaceFromMulticastRouting(final String ifName) {
        if (mMulticastRoutingFD == null) return;
        mHandler.post(() -> doRemoveInterfaceFromMulticastRouting(ifName));
    }

    private int getNextAvailableVirtualIndex() {
        int res = 0;
        for (int i = 0; i < mVirtualInterfaceNameMap.size(); i++) {
            if (mVirtualInterfaceNameMap.keyAt(i) == res) {
                res++;
            }
            else {
                break;
            }
        }
        return res;
    }

    private void addVirtualInterfaceIndexNameMapping(int virtualIndex, String name) {
        mVirtualInterfaceNameMap.put(virtualIndex, name);
        mVirtualInterfaceIndexMap.put(name, virtualIndex);
    }

    private void removeVirtualInterfaceIndexNameMapping(int virtualIndex, String name) {
        mVirtualInterfaceNameMap.remove(virtualIndex);
        mVirtualInterfaceIndexMap.remove(name);
    }

    @VisibleForTesting
    public Integer getVirtualInterfaceIndex(String ifName) {
        return mVirtualInterfaceIndexMap.get(ifName);
    }

    @VisibleForTesting
    public String getInterfaceName(int virtualIndex) {
        return mVirtualInterfaceNameMap.get(virtualIndex);
    }

    private void maybeAddVirtualInterfaceIndexNameMapping(String ifName) {
        if (mVirtualInterfaceIndexMap.get(ifName) == null) {
            int nextVirtualIndex = getNextAvailableVirtualIndex();
            int ifIndex = mDependencies.getInterfaceIndex(ifName);
            try {
                mDependencies.addMulticastInterface(
                        mMulticastRoutingFD.getFileDescriptor(), nextVirtualIndex, ifIndex);
            } catch (IOException e) {
                Log.e(TAG, "failed to add multicast virtual interface", e);
                return;
            }
            addVirtualInterfaceIndexNameMapping(nextVirtualIndex, ifName);
        }
    }

    private MulticastRoutingConfigIif getMulticastRoutingConfigsForIif(String iifName) {
        return mMulticastRoutingConfigs.get(iifName);
    }

    private MulticastRoutingConfig getMulticastRoutingConfig(String iifName, String oifName) {
        MulticastRoutingConfigIif configs = getMulticastRoutingConfigsForIif(iifName);
        final MulticastRoutingConfig defaultConfig = MulticastRoutingConfig.CONFIG_FORWARD_NONE;
        if (configs == null) {
            return defaultConfig;
        } else {
            return configs.oifConfig.getOrDefault(oifName, defaultConfig);
        }
    }

    private void setMulticastRoutingConfig(
            final String iifName, final String oifName, final MulticastRoutingConfig config) {
        MulticastRoutingConfigIif iifConfigs = getMulticastRoutingConfigsForIif(iifName);
        if (iifConfigs == null) {
            iifConfigs = new MulticastRoutingConfigIif();
            mMulticastRoutingConfigs.put(iifName, iifConfigs);
        }
        iifConfigs.oifConfig.put(oifName, config);
    }

    private void removeInterfaceFromConfigs(final String ifName) {
        // remove as iif
        mMulticastRoutingConfigs.remove(ifName);
        // remove as oif
        for (Map.Entry<String, MulticastRoutingConfigIif> entry :
                mMulticastRoutingConfigs.entrySet()) {
            entry.getValue().oifConfig.remove(ifName);
        }
    }
    /**
     * An multicast forwarding cache (MFC) entry holds a multicast forwarding
     * route where packet from incoming interface(iif) with source address(S)
     * to group address (G) are forwarded to outgoing interfaces(oifs).
     *
     * iif, S and G identifies an MFC entry. For example an MFC1 is added:
     * [iif1, S1, G1, Oifs1]
     * Adding another MFC2 of [iif1, S1, G1, Oifs2] to the kernel overwrites
     * MFC1.
     */
    private class MfcKey {
        private int mIifVirtualIdx;
        private Inet6Address mSrcAddr;
        private Inet6Address mDstAddr;

        public MfcKey(int iif, Inet6Address src, Inet6Address dst) {
            mIifVirtualIdx = iif;
            mSrcAddr = src;
            mDstAddr = dst;
        }

        public boolean equals(Object other) {
            if (other == this) {
                return true;
            } else if (!(other instanceof MfcKey)) {
                return false;
            } else {
                MfcKey otherKey = (MfcKey) other;
                return mIifVirtualIdx == otherKey.mIifVirtualIdx
                        && mSrcAddr.equals(otherKey.mSrcAddr)
                        && mDstAddr.equals(otherKey.mDstAddr);
            }
        }

        public int hashCode() {
            return Objects.hash(mIifVirtualIdx, mSrcAddr, mDstAddr);
        }

        public String toString() {
            final StringJoiner resultJoiner = new StringJoiner(" ", "{", "}");

            resultJoiner.add("iifVirtualIndex:");
            resultJoiner.add(Integer.toString(mIifVirtualIdx));

            resultJoiner.add("sourceAddress:");
            resultJoiner.add(mSrcAddr.toString());

            resultJoiner.add("destinationAddress:");
            resultJoiner.add(mDstAddr.toString());

            return resultJoiner.toString();
        }

        public int getIifVirtualIndex() {
            return mIifVirtualIdx;
        }

        public Inet6Address getSourceAddress() {
            return mSrcAddr;
        }

        public Inet6Address getDestinationAddress() {
            return mDstAddr;
        }
    }

    private class MfcValue {
        private int[] mOifVirtualIdxes;
        // the duration from last used to now in seconds
        private long mInactiveDuration;

        public MfcValue(long expires) {
            int[] oifs = {};
            mOifVirtualIdxes = oifs;
            mInactiveDuration = expires;
        }

        public MfcValue(int oif, long expires) {
            int[] oifs = new int[] {oif};
            mOifVirtualIdxes = oifs;
            mInactiveDuration = expires;
        }

        public MfcValue(int[] oifs, long expires) {
            mOifVirtualIdxes = oifs;
            mInactiveDuration = expires;
        }

        public boolean equals(Object other) {
            if (other == this) {
                return true;
            } else if (!(other instanceof MfcValue)) {
                return false;
            } else {
                MfcValue otherValue = (MfcValue) other;
                return Arrays.equals(mOifVirtualIdxes, otherValue.mOifVirtualIdxes);
            }
        }

        public int hashCode() {
            return Arrays.hashCode(mOifVirtualIdxes);
        }

        public int[] getOifIndexes() {
            return mOifVirtualIdxes;
        }

        public void setInactiveDuration(long expires) {
            mInactiveDuration = expires;
        }

        public long getInactiveDuration() {
            return mInactiveDuration;
        }

        public String toString() {
            final StringJoiner resultJoiner = new StringJoiner(" ", "{", "}");

            resultJoiner.add("oifVirtualIdxes:");
            resultJoiner.add(Arrays.toString(mOifVirtualIdxes));

            resultJoiner.add("InactiveDuration:");
            resultJoiner.add(Long.toString(mInactiveDuration));

            return resultJoiner.toString();
        }
    }

    private void addOrUpdateMfc(MfcKey key) {
        addOrUpdateMfc(key.getIifVirtualIndex(), key.getSourceAddress(),
                key.getDestinationAddress());
    }

    /*
     * Returns the MFC value for the given MFC key according to current
     * multicast routing config. If the MFC should be removed return null.
     */
    private MfcValue computeMfcValue(int iif, Inet6Address src, Inet6Address dst) {
        final MfcKey key = new MfcKey(iif, src, dst);
        final int dstScope = getGroupAddressScope(dst);
        ArrayList<Integer> forwardingOifs = new ArrayList<>();

        MulticastRoutingConfigIif iifConfig =
                getMulticastRoutingConfigsForIif(getInterfaceName(iif));

        if (iifConfig == null) {
            // An iif may have been removed from multicast routing, in this
            // case remove the MFC directly
            return null;
        }

        for (var config : iifConfig.oifConfig.entrySet()) {
            if ((config.getValue().getForwardingMode() == FORWARD_WITH_MIN_SCOPE
                    && config.getValue().getMinimumScope() <= dstScope)
                ||
                (config.getValue().getForwardingMode() == FORWARD_SELECTED
                    && config.getValue().getListeningAddresses().contains(dst))
            ) {
                forwardingOifs.add(getVirtualInterfaceIndex(config.getKey()));
            }
        }
        int[] oifs = forwardingOifs.stream().mapToInt(i -> i).toArray();

        MfcValue value = new MfcValue(oifs, 0);
        return value;
    }

    /**
     * Given the iif, source address and group destination address,
     * add an MFC entry or update the existing MFC according to the
     * multicast routing config.
     *
     * Note that if a packet has no matching MFC entry in the kernel,
     * kernel creates an unresolved route and notifies multicast socket
     * with a NOCACHE upcall message. The unresolved route is kept for
     * no less than 10s. If packets with the same source and destination
     * arrives before the 10s timeout, they will not be notified. Thus
     * we need to add a 'blocking' MFC even if the oif list is empty.
     * When the multicast configs changes, the 'blocking' MFC will be
     * updated to a 'forwarding' MFC so that corresponding multicast
     * traffic can be forwarded instantly.
     */
    private MfcValue addOrUpdateMfc(
            int iif, Inet6Address src, Inet6Address dst) {
        final MfcKey key = new MfcKey(iif, src, dst);
        final MfcValue value = mMfcs.get(key);
        final MfcValue updatedValue = computeMfcValue(iif, src, dst);

        if (updatedValue == null || updatedValue.equals(value)) {
            return updatedValue;
        }
        String operation = "Added";
        if (value != null && !updatedValue.equals(value)) {
            operation = "Updated";
        }

        try {
            mDependencies.addMulticastForwardingCache(
                    mMulticastRoutingFD.getFileDescriptor(),
                    key.getIifVirtualIndex(),
                    updatedValue.getOifIndexes(),
                    key.getSourceAddress().getAddress(),
                    key.getDestinationAddress().getAddress());
        } catch (IOException e) {
            Log.e(TAG, "failed to add MFC: " + e);
            return updatedValue;
        }
        mMfcs.put(key, updatedValue);
        Log.d(TAG,
                operation + " MFC key: " + key + " value: " + updatedValue);
        return updatedValue;
    }

    private void checkMfcsExpiration() {
        // Check if there are inactive MFCs that can be removed
        refreshMfcInactiveDuration();
        maybeExpireMfcs();
        mHandler.postDelayed(() -> checkMfcsExpiration(), MFC_INACTIVE_CHECK_INTERVAL_MS);
    }

    private void checkMfcEntriesLimit() {
        // If the max number of MFC entries is reached, remove one MFC entry
        if (mMfcs.size() == MFC_MAX_NUMBER_OF_ENTRIES) {
            MfcKey firstMfcKey = mMfcs.entrySet().iterator().next().getKey();
            removeMfc(firstMfcKey);
        }
    }

    private void refreshMfcInactiveDuration() {
        try {
            FileDescriptor fd = NetlinkUtils.netlinkSocketForProto(NETLINK_ROUTE);
            RtNetlinkMulticastRouteDumpMessage.sendNetlinkDumpRequest(fd);
            RtNetlinkMulticastRouteDumpMessage.processNetlinkDumpMessages(fd,
                    (iif, source, destination, expires) -> {
                        final MfcKey akey = new MfcKey(iif, source, destination);
                        MfcValue value = mMfcs.get(akey);
                        if (value == null) {
                            Log.e(TAG, "Can't find kernel returned MFC!");
                        } else {
                            value.setInactiveDuration(expires);
                        }
                    });
        } catch (InterruptedIOException | ErrnoException e) {
            Log.e(TAG, "Failed to dump multicast routes");
        }
        // sort the mMfc according to inactive duration
        List<Map.Entry<MfcKey, MfcValue>> list = new ArrayList<>(mMfcs.entrySet());
        Collections.sort(list,
                new Comparator<Map.Entry<MfcKey, MfcValue>>() {
                    public int compare(Map.Entry<MfcKey, MfcValue> entry1,
                            Map.Entry<MfcKey, MfcValue> entry2) {
                                return Long.compare(entry2.getValue().getInactiveDuration(),
                                        entry1.getValue().getInactiveDuration());
                            }
                });
        mMfcs.clear();
        for (Map.Entry<MfcKey, MfcValue> entry : list) {
            mMfcs.put(entry.getKey(), entry.getValue());
        }
    }

    // Remove MFC entry from mMfcs map and the kernel if exists.
    private void removeMfc(MfcKey key) {
        if (!mMfcs.containsKey(key)) return;

        MfcValue value = mMfcs.get(key);
        try {
            mDependencies.removeMulticastForwardingCache(
                    mMulticastRoutingFD.getFileDescriptor(),
                    key.getIifVirtualIndex(),
                    key.getSourceAddress().getAddress(),
                    key.getDestinationAddress().getAddress());
        } catch (IOException e) {
            Log.e(TAG, "failed to remove MFC: " + e);
            return;
        }
        mMfcs.remove(key);
        Log.d(TAG, "Removed MFC key: " + key.toString() + " value: " + value.toString());
    }

    private void maybeExpireMfcs() {
        List<MfcKey> expiredMfcKeys =
                mMfcs.entrySet().stream()
                .filter(e -> (e.getValue().getInactiveDuration() >
                        MFC_INACTIVE_EXPIRE_TIMEOUT_USER_HZ))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        for (MfcKey key : expiredMfcKeys) {
            removeMfc(key);
        }
    }

    private ParcelFileDescriptor initMulticastSocket() {
        ParcelFileDescriptor pfd;
        try {
            pfd = mDependencies.createMulticastRoutingSocket();
            Log.i(TAG, "socket created for multicast routing: " + pfd.getFd());
        } catch (IOException e) {
            Log.e(TAG, "failed to create multicast socket: " + e);
            pfd = null;
        }
        return pfd;
    }

    private int getGroupAddressScope(Inet6Address address) {
        return address.getAddress()[1] & 0xf;
    }

    private void handleMulticastNocacheUpcall(final StructMrt6Msg mrt6Msg) {
        final int dstScope = getGroupAddressScope(mrt6Msg.mDst);
        final int iifVid = mrt6Msg.mMif;

        // add MFC to forward the packet or add blocking MFC to not forward the packet
        addOrUpdateMfc(iifVid, mrt6Msg.mSrc, mrt6Msg.mDst);

        checkMfcEntriesLimit();
    }

    private final class MulticastNocacheUpcallListener extends PacketReader {
        private final FileDescriptor mFd;

        public MulticastNocacheUpcallListener(Handler h, FileDescriptor fd) {
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

            mHandler.post(() -> handleMulticastNocacheUpcall(mrt6Msg));
        }
    }

    @VisibleForTesting
    public static class Dependencies {
        // uses library 'service-connectivity' for native functions

        public ParcelFileDescriptor createMulticastRoutingSocket() throws IOException {
            return ParcelFileDescriptor.adoptFd(nativeCreateMulticastSocket());
        }

        public void addMulticastInterface(FileDescriptor fd, int virtualIfIndex, int ifIndex)
                throws IOException {
            nativeAddMif(fd, virtualIfIndex, ifIndex);
        }

        public void removeMulticastInterface(FileDescriptor fd, int virtualIfIndex)
                throws IOException {
            nativeRemoveMif(fd, virtualIfIndex);
        }

        public void addMulticastForwardingCache(
                FileDescriptor fd, int iif, int[] oifs, byte[] src, byte[] dst)
                throws IOException {
            nativeAddMfc(fd, iif, oifs, src, dst);
        }

        public void removeMulticastForwardingCache(
                FileDescriptor fd, int iif, byte[] src, byte[] dst)
                throws IOException {
            nativeRemoveMfc(fd, iif, src, dst);
        }

        public void joinGroup(FileDescriptor fd, int ifIndex, byte[] addr) throws IOException {
            nativeJoinGroup(fd, ifIndex, addr);
        }

        public void leaveGroup(FileDescriptor fd, int ifIndex, byte[] addr) throws IOException {
            nativeLeaveGroup(fd, ifIndex, addr);
        }

        public Integer getInterfaceIndex(String ifName) {
            try {
                NetworkInterface ni = NetworkInterface.getByName(ifName);
                return ni.getIndex();
            } catch (NullPointerException | SocketException e) {
                return null;
            }
        }
    }

    private static native int nativeCreateMulticastSocket() throws IOException;

    private static native void nativeAddMif(FileDescriptor fd, int virtualIfIndex, int ifIndex)
            throws IOException;

    private static native void nativeRemoveMif(FileDescriptor fd, int virtualIfIndex)
            throws IOException;

    private static native void nativeAddMfc(
            FileDescriptor fd, int iif, int[] oifs, byte[] src, byte[] dst)
            throws IOException;

    private static native void nativeRemoveMfc(
            FileDescriptor fd, int iif, byte[] src, byte[] dst)
            throws IOException;

    private static native void nativeJoinGroup(FileDescriptor fd, int ifIndex, byte[] addr)
            throws IOException;

    private static native void nativeLeaveGroup(FileDescriptor fd, int ifIndex, byte[] addr)
            throws IOException;
}
