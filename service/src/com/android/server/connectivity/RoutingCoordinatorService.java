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
import static android.system.OsConstants.AF_INET6;
import static android.system.OsConstants.IPPROTO_ICMPV6;
import static android.system.OsConstants.IPPROTO_IPV6;
import static android.system.OsConstants.SOCK_CLOEXEC;
import static android.system.OsConstants.SOCK_NONBLOCK;
import static android.system.OsConstants.SOCK_RAW;

import static com.android.net.module.util.NetdUtils.toRouteInfoParcel;

import android.annotation.NonNull;
import android.net.INetd;
import android.net.IRoutingCoordinator;
import android.net.MulticastRoutingConfig;
import android.net.NetworkUtils;
import android.net.RouteInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.system.ErrnoException;
import android.system.Os;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;
import androidx.annotation.RequiresApi;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.CollectionUtils;
import com.android.net.module.util.LinkPropertiesUtils.CompareResult;
import com.android.net.module.util.netlink.NetlinkUtils;
import com.android.net.module.util.netlink.RtNetlinkRouteMessage;
import com.android.net.module.util.PacketReader;
import com.android.net.module.util.Struct;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
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
 * At present this is just a wrapper for netd methods along with support for multicast
 * routing, but it will be used to host some more coordination logic in the near future.
 * It can be used to pull up some of the routing logic from netd into Java land.
 *
 * The multicast routing part of this class (i.e. applyMulticastRoutingConfig and
 * removeInterfaceFromMulticastRouting) is thread-safe.
 *
 * The rest part of this class is not thread-safe. Clients are responsible for their
 * own synchronization.
 */
public class RoutingCoordinatorService extends IRoutingCoordinator.Stub {
    private static final String TAG = RoutingCoordinatorService.class.getSimpleName();
    private static final int ICMP6_FILTER = 1;
    private static final int MRT6_INIT = 200;
    private static final int MRT6_ADD_MIF = 202;
    private static final int MRT6_DEL_MIF = 203;
    private static final int MRT6_ADD_MFC = 204;
    private static final int MRT6_DEL_MFC = 205;
    private static final int ONE = 1;
    private final INetd mNetd;
    private final Dependencies mDependencies;

    private final HandlerThread mHandlerThread;
    private final HandlerThread mListenerThread;
    private final Handler mListenerHandler;
    private final Handler mHandler;
    private final MulticastNocacheUpcallListener mMulticastNoCacheUpcallListener;
    private final FileDescriptor mMulticastRoutingFd;
    private final MulticastSocket mMulticastSocket; // For joinGroup and leaveGroup

    private final int MFC_INACTIVE_CHECK_INTERVAL_MS = 60000;
    // The duration between last used to now of a multicast forwarding cache is returned
    // with netlink messages by the kernel in the unit of clocks defined by `USER_HZ`
    private final int USER_HZ = 100;
    // number of user clocks in 300 seconds
    private final int MFC_INACTIVE_EXPIRE_TIMEOUT_USER_HZ = 300 * USER_HZ;
    @VisibleForTesting
    public final int MFC_MAX_NUMBER_OF_ENTRIES = 10000;

    // Tracks if checking for inactive MFC has been scheduled
    private boolean mMfcPollingScheduled = false;
    private Map<String, Integer> mVirtualInterfaceIndexMap = new HashMap<>();
    private SparseArray<String> mVirtualInterfaceNameMap = new SparseArray<>();

    private Map<String, MulticastRoutingConfigIif> mMulticastRoutingConfigs =
            new HashMap<String, MulticastRoutingConfigIif>();

    // The mMfcs map keeps track of the MFCs added to kernel
    private LinkedHashMap<MfcKey, MfcValue> mMfcs = new LinkedHashMap<>();

    private static final class MulticastRoutingConfigIif {
        // mapping of oif name to MulticastRoutingConfig
        public Map<String, MulticastRoutingConfig> oifConfig =
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
        mMulticastRoutingFd = mDependencies.createMulticastRoutingSocket();
        mHandlerThread = new HandlerThread("HandlerThread");
        mListenerThread = new HandlerThread("ListenerThread");
        mHandlerThread.start();
        mListenerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mListenerHandler = new Handler(mListenerThread.getLooper());
        mMulticastNoCacheUpcallListener =
                new MulticastNocacheUpcallListener(mListenerHandler,
                         mMulticastRoutingFd);
        mMulticastSocket = createMulticastSocket();
        if (mMulticastRoutingFd == null) {
            return;
        }
        mListenerHandler.post(() -> mMulticastNoCacheUpcallListener.start());
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

    private void checkOnHandlerThread() {
        if (Looper.myLooper() != mHandlerThread.getLooper()) {
            Log.wtf(TAG, "Must be on the handler thread!");
        }
    }

    private void doApplyIPv6MulticastRoutingConfig(
            final String iifName, final String oifName, final MulticastRoutingConfig newConfig) {
        checkOnHandlerThread();
        if (mMulticastRoutingFd == null) return;

        // Make sure iif and oif are added as multicast forwarding interfaces
        maybeAddVirtualInterfaceIndexNameMapping(iifName);
        maybeAddVirtualInterfaceIndexNameMapping(oifName);

        final MulticastRoutingConfig oldConfig = getMulticastRoutingConfig(iifName, oifName);

        if (oldConfig.equals(newConfig)) return;

        int oldMode = oldConfig.getForwardingMode();
        int newMode = newConfig.getForwardingMode();
        int[] oifs = new int[1];
        oifs[0] = getVirtualInterfaceIndex(oifName);
        int iifIndex = mDependencies.getInterfaceIndex(iifName);

        // When new addresses are added to FORWARD_SELECTED mode, join these multicast groups
        // on their upstream interface, so upstream multicast routers know about the subscription.
        // When addresses are removed from FORWARD_SELECTED mode, leave the multicast groups.
        if (oldMode != newMode) {
            if (newMode == FORWARD_SELECTED) {
                List<Inet6Address> listeningAddresses =
                        new ArrayList<>(newConfig.getListeningAddresses());
                joinGroups(mMulticastSocket, iifIndex, listeningAddresses);
            } else if (oldMode == FORWARD_SELECTED) {
                List<Inet6Address> listeningAddresses =
                        new ArrayList<>(oldConfig.getListeningAddresses());
                leaveGroups(mMulticastSocket, iifIndex,
                        listeningAddresses);
            }
        } else if (newMode == FORWARD_SELECTED) {
            final CompareResult<Inet6Address> addressDiff =
                    new CompareResult<>(
                            oldConfig.getListeningAddresses(), newConfig.getListeningAddresses());
            joinGroups(mMulticastSocket, iifIndex, addressDiff.added);
            leaveGroups(mMulticastSocket, iifIndex, addressDiff.removed);
        }

        setMulticastRoutingConfig(iifName, oifName, newConfig);
        Log.d(
                TAG,
                "Applied multicast routing config for iif "
                        + iifName
                        + " to oif "
                        + oifName
                        + " with Config "
                        + newConfig);

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
        mHandler.post(() -> doApplyIPv6MulticastRoutingConfig(iifName, oifName, newConfig));
    }

    private void doRemoveInterfaceFromMulticastRouting(final String ifName) {
        checkOnHandlerThread();
        if (mMulticastRoutingFd == null) return;
        final Integer virtualIndex = getVirtualInterfaceIndex(ifName);
        if (virtualIndex == null) return;

        removeInterfaceFromConfigs(ifName);
        UpdateMfcs();
        removeVirtualInterfaceIndexNameMapping(virtualIndex, ifName);
        try {
            mDependencies.removeMulticastInterface(mMulticastRoutingFd,
                    virtualIndex);
        } catch (ErrnoException e) {
            Log.e(TAG, "failed to remove multicast virtual interface", e);
        }
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
        checkOnHandlerThread();
        mVirtualInterfaceNameMap.put(virtualIndex, name);
        mVirtualInterfaceIndexMap.put(name, virtualIndex);
    }

    private void removeVirtualInterfaceIndexNameMapping(int virtualIndex, String name) {
        checkOnHandlerThread();
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

    private Integer getVirtualInterfaceIndex(int physicalIndex) {
        String ifName = mDependencies.getInterfaceName(physicalIndex);
        return getVirtualInterfaceIndex(ifName);
    }

    private void maybeAddVirtualInterfaceIndexNameMapping(String ifName) {
        checkOnHandlerThread();
        if (mVirtualInterfaceIndexMap.get(ifName) == null) {
            int nextVirtualIndex = getNextAvailableVirtualIndex();
            int ifIndex = mDependencies.getInterfaceIndex(ifName);
            try {
                mDependencies.addMulticastInterface(
                        mMulticastRoutingFd, nextVirtualIndex, ifIndex);
            } catch (IOException e) {
                Log.e(TAG, "failed to add multicast virtual interface", e);
                return;
            }
            addVirtualInterfaceIndexNameMapping(nextVirtualIndex, ifName);
        }
    }

    private MulticastRoutingConfig getMulticastRoutingConfig(String iifName, String oifName) {
        MulticastRoutingConfigIif configs = mMulticastRoutingConfigs.get(iifName);
        final MulticastRoutingConfig defaultConfig = MulticastRoutingConfig.CONFIG_FORWARD_NONE;
        if (configs == null) {
            return defaultConfig;
        } else {
            return configs.oifConfig.getOrDefault(oifName, defaultConfig);
        }
    }

    private void setMulticastRoutingConfig(
            final String iifName, final String oifName, final MulticastRoutingConfig config) {
        checkOnHandlerThread();
        MulticastRoutingConfigIif iifConfigs = mMulticastRoutingConfigs.get(iifName);
        if (iifConfigs == null) {
            iifConfigs = new MulticastRoutingConfigIif();
            mMulticastRoutingConfigs.put(iifName, iifConfigs);
        }
        iifConfigs.oifConfig.put(oifName, config);
    }

    private void removeInterfaceFromConfigs(final String ifName) {
        checkOnHandlerThread();
        // remove as iif
        mMulticastRoutingConfigs.remove(ifName);
        // remove as oif
        for (Map.Entry<String, MulticastRoutingConfigIif> entry :
                mMulticastRoutingConfigs.entrySet()) {
            entry.getValue().oifConfig.remove(ifName);
        }
    }

    /**
     * A multicast forwarding cache (MFC) entry holds a multicast forwarding
     * route where packet from incoming interface(iif) with source address(S)
     * to group address (G) are forwarded to outgoing interfaces(oifs).
     *
     * iif, S and G identifies an MFC entry. For example an MFC1 is added:
     * [iif1, S1, G1, oifs1]
     * Adding another MFC2 of [iif1, S1, G1, oifs2] to the kernel overwrites
     * MFC1.
     */
    private static final class MfcKey {
        private final int mIifVirtualIdx;
        private final Inet6Address mSrcAddr;
        private final Inet6Address mDstAddr;

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

    private static final class MfcValue {
        private List<Integer> mOifVirtualIdxes;
        // the number of clocks ticks from the MFC last accessed in kernel to now
        private long mInactiveDuration;

        public MfcValue(List<Integer> oifs, long clocks) {
            mOifVirtualIdxes = oifs;
            mInactiveDuration = clocks;
        }

        public boolean equals(Object other) {
            if (other == this) {
                return true;
            } else if (!(other instanceof MfcValue)) {
                return false;
            } else {
                MfcValue otherValue = (MfcValue) other;
                return mOifVirtualIdxes.equals(otherValue.mOifVirtualIdxes);
            }
        }

        public int hashCode() {
            return mOifVirtualIdxes.hashCode();
        }

        public List<Integer> getOifIndexes() {
            return mOifVirtualIdxes;
        }

        public void setInactiveDuration(long clocks) {
            mInactiveDuration = clocks;
        }

        public long getInactiveDuration() {
            return mInactiveDuration;
        }

        public String toString() {
            final StringJoiner resultJoiner = new StringJoiner(" ", "{", "}");

            resultJoiner.add("oifVirtualIdxes:");
            resultJoiner.add(mOifVirtualIdxes.toString());

            resultJoiner.add("InactiveDuration(clocks):");
            resultJoiner.add(Long.toString(mInactiveDuration));

            return resultJoiner.toString();
        }
    }

    /*
     * Returns the MFC value for the given MFC key according to current
     * multicast routing config. If the MFC should be removed return null.
     */
    private MfcValue computeMfcValue(int iif, Inet6Address dst) {
        final int dstScope = getGroupAddressScope(dst);
        ArrayList<Integer> forwardingOifs = new ArrayList<>();

        MulticastRoutingConfigIif iifConfig =
                mMulticastRoutingConfigs.get(getInterfaceName(iif));

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

        return new MfcValue(forwardingOifs, 0);
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
        checkOnHandlerThread();
        final MfcKey key = new MfcKey(iif, src, dst);
        final MfcValue value = mMfcs.get(key);
        final MfcValue updatedValue = computeMfcValue(iif, dst);

        if (updatedValue == null || updatedValue.equals(value)) {
            return updatedValue;
        }

        try {
            mDependencies.addMulticastForwardingCache(
                    mMulticastRoutingFd,
                    key.getIifVirtualIndex(),
                    updatedValue.getOifIndexes(),
                    key.getSourceAddress(),
                    key.getDestinationAddress());
        } catch (IOException e) {
            Log.e(TAG, "failed to add MFC: " + e);
            return updatedValue;
        }
        mMfcs.put(key, updatedValue);
        String operation = "Added";
        if (value != null && !updatedValue.equals(value)) {
            operation = "Updated";
        }
        Log.d(TAG,
                operation + " MFC key: " + key + " value: " + updatedValue);
        return updatedValue;
    }

    private void checkMfcsExpiration() {
        mMfcPollingScheduled = false;
        checkOnHandlerThread();
        // Check if there are inactive MFCs that can be removed
        refreshMfcInactiveDuration();
        maybeExpireMfcs();
        if (mMfcs.size() > 0) {
            mHandler.postDelayed(() -> checkMfcsExpiration(), MFC_INACTIVE_CHECK_INTERVAL_MS);
            mMfcPollingScheduled = true;
        }
    }

    private void checkMfcEntriesLimit() {
        checkOnHandlerThread();
        // If the max number of MFC entries is reached, remove the first MFC entry. This can be
        // any entry, as if this entry is needed again there will be a NOCACHE upcall to add it
        // back.
        if (mMfcs.size() == MFC_MAX_NUMBER_OF_ENTRIES) {
            Log.w(TAG, "Reached max number of MFC entries " + MFC_MAX_NUMBER_OF_ENTRIES);
            MfcKey firstMfcKey = mMfcs.entrySet().iterator().next().getKey();
            removeMfc(firstMfcKey);
        }
    }

    private void refreshMfcInactiveDuration() {
        checkOnHandlerThread();
        try {
            FileDescriptor fd = NetlinkUtils.netlinkSocketForProto(NETLINK_ROUTE);
            RtNetlinkRouteMessage.sendNetlinkDumpRequest(fd);
            RtNetlinkRouteMessage.processNetlinkDumpMessages(fd,
                    (iifPhysicalIndex, source, destination, expires) -> {
                        final Integer iif = getVirtualInterfaceIndex(iifPhysicalIndex);
                        if (iif == null) {
                            Log.e(TAG, "Can't find kernel returned IIF " + iifPhysicalIndex);
                            return;
                        }
                        final MfcKey key = new MfcKey(iif, source, destination);
                        MfcValue value = mMfcs.get(key);
                        if (value == null) {
                            Log.e(TAG, "Can't find kernel returned MFC " + key);
                        } else {
                            value.setInactiveDuration(expires);
                        }
                    });
        } catch (InterruptedIOException | ErrnoException e) {
            Log.e(TAG, "Failed to dump multicast routes");
        }
    }

    // Remove MFC entry from mMfcs map and the kernel if exists.
    private void removeMfc(MfcKey key) {
        checkOnHandlerThread();
        if (!mMfcs.containsKey(key)) return;

        MfcValue value = mMfcs.get(key);
        try {
            mDependencies.removeMulticastForwardingCache(
                    mMulticastRoutingFd,
                    key.getIifVirtualIndex(),
                    key.getSourceAddress(),
                    key.getDestinationAddress());
        } catch (IOException e) {
            Log.e(TAG, "failed to remove MFC: " + e);
            return;
        }
        mMfcs.remove(key);
        Log.d(TAG, "Removed MFC key: " + key + " value: " + value);
    }

    private void maybeExpireMfcs() {
        checkOnHandlerThread();
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

    private void UpdateMfcs() {
        checkOnHandlerThread();
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

    private void joinGroups(MulticastSocket sock, int ifIndex, List<Inet6Address> addresses) {
        if (sock == null) {
            return;
        }
        for (Inet6Address address : addresses) {
            try {
                mDependencies.joinGroup(sock, ifIndex, address);
            } catch (IOException e) {
                Log.e(TAG, "failed to join group: " + e);
            }
        }
    }

    private void leaveGroups(MulticastSocket sock, int ifIndex, List<Inet6Address> addresses) {
        if (sock == null) {
            return;
        }
        for (Inet6Address address : addresses) {
            try {
                mDependencies.leaveGroup(sock, ifIndex, address);
            } catch (IOException e) {
                Log.e(TAG, "failed to leave group: " + e);
            }
        }
    }

    private MulticastSocket createMulticastSocket() {
        try {
            return new MulticastSocket();
        } catch (IOException e) {
            Log.e(TAG, "failed to create MulticastSocket", e);
            return null;
        }
    }

    private int getGroupAddressScope(Inet6Address address) {
        return address.getAddress()[1] & 0xf;
    }

    private void handleMulticastNocacheUpcall(final StructMrt6Msg mrt6Msg) {
        final int iifVid = mrt6Msg.mif;

        // add MFC to forward the packet or add blocking MFC to not forward the packet
        addOrUpdateMfc(iifVid, mrt6Msg.src, mrt6Msg.dst);
        // The list of MFCs can't be empty here, as a new one may just have been added
        // or an existing one was just updated. If there is no MFC check scheduled,
        // schedule one now
        if (!mMfcPollingScheduled) {
            mHandler.postDelayed(() -> checkMfcsExpiration(), MFC_INACTIVE_CHECK_INTERVAL_MS);
            mMfcPollingScheduled = true;
        }

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
            if (mrt6Msg.msgType != StructMrt6Msg.MRT6MSG_NOCACHE) {
                return;
            }

            Log.d(TAG, "Got nocache upcall in packetreader " + mrt6Msg);
            mHandler.post(() -> handleMulticastNocacheUpcall(mrt6Msg));
        }
    }

    @VisibleForTesting
    public static class Dependencies {
        public FileDescriptor createMulticastRoutingSocket(){
            FileDescriptor sock;
            byte[] filter = new byte[32]; // filter all ICMPv6 messages
            try {
                sock = Os.socket(AF_INET6, SOCK_RAW | SOCK_CLOEXEC | SOCK_NONBLOCK, IPPROTO_ICMPV6);
                Os.setsockoptInt(sock, IPPROTO_IPV6, MRT6_INIT, ONE);
                NetworkUtils.setsockoptBytes(sock, IPPROTO_ICMPV6, ICMP6_FILTER, filter);
            } catch (ErrnoException | IOException e) {
                Log.e(TAG, "failed to create multicast socket: " + e);
                return null;
            }
            Log.i(TAG, "socket created for multicast routing: " + sock);
            return sock;
        }

        public void addMulticastInterface(FileDescriptor fd, int virtualIfIndex, int ifIndex)
                throws IOException {
            final StructMif6ctl mif6ctl = new StructMif6ctl(virtualIfIndex,
                    (short) 0 /* mif6c_flags */, (short) 1 /* vifc_threshold */, ifIndex,
                    0 /* vifc_rate_limit */);
            final byte[] bytes = mif6ctl.writeToBytes();
            Log.d(TAG, "Added mifi " + virtualIfIndex + "to MIF");
            NetworkUtils.setsockoptBytes(fd, IPPROTO_IPV6, MRT6_ADD_MIF, bytes);
        }

        public void removeMulticastInterface(FileDescriptor fd, int virtualIfIndex)
                throws ErrnoException {
            Os.setsockoptInt(fd, IPPROTO_IPV6, MRT6_DEL_MIF, virtualIfIndex);
            Log.d(TAG, "Removed mifi " + virtualIfIndex + "from MIF");
        }

        public void addMulticastForwardingCache(
                FileDescriptor fd, int iif, List<Integer> oifs, Inet6Address src, Inet6Address dst)
                throws IOException {
            final StructMf6cctl mf6cctl = new StructMf6cctl(src, dst, iif, oifs);
            final byte[] bytes = mf6cctl.writeToBytes();
            NetworkUtils.setsockoptBytes(fd, IPPROTO_IPV6, MRT6_ADD_MFC, bytes);
        }

        public void removeMulticastForwardingCache(
                FileDescriptor fd, int iif, Inet6Address src, Inet6Address dst)
                throws IOException {
            List<Integer> oifs = new ArrayList<>();
            final StructMf6cctl mf6cctl = new StructMf6cctl(src, dst, iif, oifs);
            final byte[] bytes = mf6cctl.writeToBytes();
            NetworkUtils.setsockoptBytes(fd, IPPROTO_IPV6, MRT6_DEL_MFC, bytes);
        }

        public void joinGroup(MulticastSocket sock, int ifIndex, Inet6Address addr)
                throws IOException {
            InetSocketAddress address = new InetSocketAddress(addr, 0);
            sock.joinGroup(address, NetworkInterface.getByIndex(ifIndex));
        }

        public void leaveGroup(MulticastSocket sock, int ifIndex, Inet6Address addr)
                throws IOException {
            InetSocketAddress address = new InetSocketAddress(addr, 0);
            sock.leaveGroup(address, NetworkInterface.getByIndex(ifIndex));
        }

        public Integer getInterfaceIndex(String ifName) {
            try {
                NetworkInterface ni = NetworkInterface.getByName(ifName);
                return ni.getIndex();
            } catch (NullPointerException | SocketException e) {
                return null;
            }
        }

        public String getInterfaceName(int physicalIndex) {
            try {
                NetworkInterface ni = NetworkInterface.getByIndex(physicalIndex);
                return ni.getName();
            } catch (NullPointerException | SocketException e) {
                return null;
            }
        }
    }
}
