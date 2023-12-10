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
import static android.system.OsConstants.EADDRINUSE;
import static android.system.OsConstants.IPPROTO_ICMPV6;
import static android.system.OsConstants.IPPROTO_IPV6;
import static android.system.OsConstants.SOCK_CLOEXEC;
import static android.system.OsConstants.SOCK_NONBLOCK;
import static android.system.OsConstants.SOCK_RAW;

import static com.android.net.module.util.NetdUtils.toRouteInfoParcel;

import android.annotation.NonNull;
import android.annotation.Nullable;
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
import android.util.ArrayMap;
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
import com.android.net.module.util.SocketUtils;
import com.android.net.module.util.Struct;
import com.android.net.module.util.structs.StructMif6ctl;
import com.android.net.module.util.structs.StructMf6cctl;
import com.android.net.module.util.structs.StructMrt6Msg;

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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
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
 * <p>At present this is just a wrapper for netd methods along with support for multicast
 * routing, but it will be used to host some more coordination logic in the near future.
 * It can be used to pull up some of the routing logic from netd into Java land.
 *
 * <p>Note that usage of this class is not thread-safe. All public methods must be called
 * from the same thread that the handler from {@code dependencies.getHandler} is associated.
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

    private final Handler mHandler;
    private final MulticastNocacheUpcallListener mMulticastNoCacheUpcallListener;
    @Nullable
    private final FileDescriptor mMulticastRoutingFd;

    @VisibleForTesting
    public static final int MFC_INACTIVE_CHECK_INTERVAL_MS = 60_000;
    @VisibleForTesting
    public static final int MFC_INACTIVE_TIMEOUT_MS = 300_000;
    @VisibleForTesting
    public static final int MFC_MAX_NUMBER_OF_ENTRIES = 1_000;

    // The kernel supports max 32 virtual interfaces per multicast routing table.
    private static final int MAX_NUM_OF_MULTICAST_VIRTUAL_INTERFACES = 32;

    // Tracks if checking for inactive MFC has been scheduled
    private boolean mMfcPollingScheduled = false;
    // Mapping from interface name to multicast virtual interface index
    private Map<String, Integer> mVirtualInterfaceIndexMap = new ArrayMap<>();
    // Mapping from multicast virtual interface index to interface name
    private SparseArray<String> mVirtualInterfaceNameMap =
            new SparseArray<>(MAX_NUM_OF_MULTICAST_VIRTUAL_INTERFACES);

    // mapping of iif to MulticastRoutingConfigIif
    private Map<String, PerInterfaceMulticastRoutingConfig> mMulticastRoutingConfigs =
            new HashMap<String, PerInterfaceMulticastRoutingConfig>();

    // Tracks the MFCs added to kernel. Using LinkedHashMap to keep the added order, so
    // when the number of MFCs reaches the max limit then the earliest added one is removed.
    private LinkedHashMap<MfcKey, MfcValue> mMfcs = new LinkedHashMap<>();

    private static final class PerInterfaceMulticastRoutingConfig {
        // mapping of oif name to MulticastRoutingConfig
        public Map<String, MulticastRoutingConfig> oifConfigs =
                new HashMap<String, MulticastRoutingConfig>();
    }

    @VisibleForTesting
    public RoutingCoordinatorService(Dependencies dependencies) {
        mNetd = dependencies.getNetd();
        mDependencies = dependencies;
        mMulticastRoutingFd = mDependencies.createMulticastRoutingSocket();
        mHandler = dependencies.getHandler();
        mMulticastNoCacheUpcallListener = maybeCreateMulticastUpcallListener();
        if (mMulticastNoCacheUpcallListener != null) {
            mHandler.post(() -> mMulticastNoCacheUpcallListener.start());
        }
    }

    private boolean isMulticastRoutingSupported() {
        return (mMulticastRoutingFd != null);
    }

    private MulticastNocacheUpcallListener maybeCreateMulticastUpcallListener() {
        if (!isMulticastRoutingSupported()) return null;

        return new MulticastNocacheUpcallListener(mHandler, mMulticastRoutingFd);
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
                // TODO: remove if no reports are observed from the below log
                Log.wtf(TAG, "Forward already exists between ifaces "
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
        if (Looper.myLooper() != mHandler.getLooper()) {
            Log.wtf(TAG, "Must be on the handler thread!");
        }
    }

    /**
     * Apply multicast routing configuration
     *
     * @param iifName name of the incoming interface
     * @param oifName name of the outgoing interface
     * @param newConfig the multicast routing configuration to be applied from iif to oif
     *
     * @throws MulticastRoutingException when failed to apply the config
     */
    public void applyMulticastRoutingConfig(
            final String iifName, final String oifName, final MulticastRoutingConfig newConfig) {
        checkOnHandlerThread();
        if (!isMulticastRoutingSupported()) return;

        if (newConfig.getForwardingMode() != FORWARD_NONE) {
            // Make sure iif and oif are added as multicast forwarding interfaces
            try {
                maybeAddVirtualInterfaceIndexNameMapping(iifName);
                maybeAddVirtualInterfaceIndexNameMapping(oifName);
            } catch (IllegalStateException e) {
                Log.e(TAG, "Failed to apply multicast routing config, ", e);
                return;
            }
        }

        final MulticastRoutingConfig oldConfig = getMulticastRoutingConfig(iifName, oifName);

        if (oldConfig.equals(newConfig)) return;

        int oldMode = oldConfig.getForwardingMode();
        int newMode = newConfig.getForwardingMode();
        int iifIndex = mDependencies.getInterfaceIndex(iifName);

        // When new addresses are added to FORWARD_SELECTED mode, join these multicast groups
        // on their upstream interface, so upstream multicast routers know about the subscription.
        // When addresses are removed from FORWARD_SELECTED mode, leave the multicast groups.
        final Set<Inet6Address> oldListeningAddresses = (oldMode == FORWARD_SELECTED) ?
                oldConfig.getListeningAddresses() : new ArraySet<>();
        final Set<Inet6Address> newListeningAddresses = (newMode == FORWARD_SELECTED) ?
                newConfig.getListeningAddresses() : new ArraySet<>();
        final CompareResult<Inet6Address> addressDiff =
                    new CompareResult<>(
                            oldListeningAddresses, newListeningAddresses);
        joinGroups(iifIndex, addressDiff.added);
        leaveGroups(iifIndex, addressDiff.removed);

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

        if (newConfig.getForwardingMode() == FORWARD_NONE) {
            if (!hasActiveMulticastConfig(iifName)) {
                removeInterfaceFromMulticastRouting(iifName);
            }
            if (!hasActiveMulticastConfig(oifName)) {
                removeInterfaceFromMulticastRouting(oifName);
            }
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
    @VisibleForTesting
    public void removeInterfaceFromMulticastRouting(final String ifName) {
        checkOnHandlerThread();
        if (!isMulticastRoutingSupported()) return;
        final Integer virtualIndex = getVirtualInterfaceIndex(ifName);
        if (virtualIndex == null) return;

        removeInterfaceFromConfigs(ifName);
        UpdateMfcs();
        removeVirtualInterfaceIndexNameMapping(virtualIndex, ifName);
        try {
            mDependencies.removeMulticastInterface(mMulticastRoutingFd,
                    virtualIndex);
        } catch (ErrnoException e) {
            Log.e(TAG, "failed to remove multicast virtual interface" + virtualIndex, e);
        }
    }

    private int getNextAvailableVirtualIndex() {
        if (mVirtualInterfaceNameMap.size() >= MAX_NUM_OF_MULTICAST_VIRTUAL_INTERFACES) {
            throw new IllegalStateException("Can't allocate new multicast virtual interface");
        }
        for (int i = 0; i < mVirtualInterfaceNameMap.size(); i++) {
            if (!mVirtualInterfaceNameMap.contains(i)) {
                return i;
            }
        }
        return mVirtualInterfaceNameMap.size();
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
        if (mVirtualInterfaceIndexMap.containsKey(ifName)) return;

        int nextVirtualIndex = getNextAvailableVirtualIndex();
        int ifIndex = mDependencies.getInterfaceIndex(ifName);
        try {
            mDependencies.addMulticastInterface(
                    mMulticastRoutingFd, nextVirtualIndex, ifIndex);
        } catch (ErrnoException e) {
            Log.e(TAG, "failed to add multicast virtual interface", e);
            return;
        }
        addVirtualInterfaceIndexNameMapping(nextVirtualIndex, ifName);
    }

    @VisibleForTesting
    public MulticastRoutingConfig getMulticastRoutingConfig(String iifName, String oifName) {
        PerInterfaceMulticastRoutingConfig configs = mMulticastRoutingConfigs.get(iifName);
        final MulticastRoutingConfig defaultConfig = MulticastRoutingConfig.CONFIG_FORWARD_NONE;
        if (configs == null) {
            return defaultConfig;
        } else {
            return configs.oifConfigs.getOrDefault(oifName, defaultConfig);
        }
    }

    private void setMulticastRoutingConfig(
            final String iifName, final String oifName, final MulticastRoutingConfig config) {
        checkOnHandlerThread();
        final boolean isConfigNone = config.equals(MulticastRoutingConfig.CONFIG_FORWARD_NONE);
        PerInterfaceMulticastRoutingConfig iifConfigs = mMulticastRoutingConfigs.get(iifName);
        if (iifConfigs == null && !isConfigNone ) {
            iifConfigs = new PerInterfaceMulticastRoutingConfig();
            mMulticastRoutingConfigs.put(iifName, iifConfigs);
        }
        if (isConfigNone) {
            iifConfigs.oifConfigs.remove(oifName);
        } else {
            iifConfigs.oifConfigs.put(oifName, config);
        }
    }

    // Returns whether an interface has multicast routing config
    private boolean hasActiveMulticastConfig(final String ifName) {
        for (var iifConfigEntry : mMulticastRoutingConfigs.entrySet()) {
            final String iifName = iifConfigEntry.getKey();
            for (var oifConfigEntry : iifConfigEntry.getValue().oifConfigs.entrySet()) {
                final String oifName = oifConfigEntry.getKey();
                if ((ifName == iifName || ifName == oifName)) {
                    // FORWARD_NONE configs are not saved in the config tables, so
                    // any existing config is an active multicast routing config
                    return true;
                }
            }
        }
        return false;
    }

    private void removeInterfaceFromConfigs(final String ifName) {
        checkOnHandlerThread();
        // remove as iif
        mMulticastRoutingConfigs.remove(ifName);
        // remove as oif
        for (Map.Entry<String, PerInterfaceMulticastRoutingConfig> entry :
                mMulticastRoutingConfigs.entrySet()) {
            entry.getValue().oifConfigs.remove(ifName);
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
            return "{iifVirtualIndex: " + Integer.toString(mIifVirtualIdx)
                    + ", sourceAddress: " + mSrcAddr.toString()
                    + ", destinationAddress: " + mDstAddr.toString()
                    + "}";
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
        private Set<Integer> mOifVirtualIdxes;
        // timestamp of when the mfc was last used in the kernel
        // (e.g. created, or used to forward a packet)
        private Instant mLastUsedAt;

        public MfcValue(Set<Integer> oifs, Instant timestamp) {
            mOifVirtualIdxes = oifs;
            mLastUsedAt = timestamp;
        }

        public boolean hasSameOifsAs(MfcValue other) {
            return this.mOifVirtualIdxes.equals(other.mOifVirtualIdxes);
        }

        public boolean equals(Object other) {
            if (other == this) {
                return true;
            } else if (!(other instanceof MfcValue)) {
                return false;
            } else {
                MfcValue otherValue = (MfcValue) other;
                return mOifVirtualIdxes.equals(otherValue.mOifVirtualIdxes)
                        && mLastUsedAt.equals(otherValue.mLastUsedAt);
            }
        }

        public int hashCode() {
            return Objects.hash(mOifVirtualIdxes, mLastUsedAt);
        }

        public Set<Integer> getOifIndexes() {
            return mOifVirtualIdxes;
        }

        public void setLastUsedAt(Instant timestamp) {
            mLastUsedAt = timestamp;
        }

        public Instant getLastUsedAt() {
            return mLastUsedAt;
        }

        public String toString() {
            return "{oifVirtualIdxes: " + mOifVirtualIdxes.toString()
                    + ", lastUsedAt: " + mLastUsedAt.toString()
                    + "}";
        }
    }

    /**
     * Returns the MFC value for the given MFC key according to current
     * multicast routing config. If the MFC should be removed return null.
     */
    private MfcValue computeMfcValue(int iif, Inet6Address dst) {
        final int dstScope = getGroupAddressScope(dst);
        Set<Integer> forwardingOifs = new ArraySet<>();

        PerInterfaceMulticastRoutingConfig iifConfig =
                mMulticastRoutingConfigs.get(getInterfaceName(iif));

        if (iifConfig == null) {
            // An iif may have been removed from multicast routing, in this
            // case remove the MFC directly
            return null;
        }

        for (var config : iifConfig.oifConfigs.entrySet()) {
            if ((config.getValue().getForwardingMode() == FORWARD_WITH_MIN_SCOPE
                    && config.getValue().getMinimumScope() <= dstScope)
                ||
                (config.getValue().getForwardingMode() == FORWARD_SELECTED
                    && config.getValue().getListeningAddresses().contains(dst))
            ) {
                forwardingOifs.add(getVirtualInterfaceIndex(config.getKey()));
            }
        }

        return new MfcValue(forwardingOifs, Instant.now(mDependencies.getClock()));
    }

    /**
     * Given the iif, source address and group destination address,
     * add an MFC entry or update the existing MFC according to the
     * multicast routing config. If such an MFC should not exist,
     * return null for caller of the function to remove it.
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

        if (updatedValue == null) {
            return null;
        }

        if (value != null && value.hasSameOifsAs(updatedValue)) {
            // no updates to make
            return value;
        }

        try {
            mDependencies.addMulticastForwardingCache(
                    mMulticastRoutingFd,
                    key.getIifVirtualIndex(),
                    updatedValue.getOifIndexes(),
                    key.getSourceAddress(),
                    key.getDestinationAddress());
        } catch (ErrnoException e) {
            Log.e(TAG, "failed to add MFC: " + e);
            return updatedValue;
        }
        mMfcs.put(key, updatedValue);
        String operation = (value == null ? "Added" : "Updated");
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
        final List<RtNetlinkRouteMessage> multicastRoutes =
                NetlinkUtils.getIpv6MulticastRoutes();

        for (var route : multicastRoutes) {
            if (!route.isResolved()) {
                continue; // Don't handle unresolved mfc, the kernel will recycle in 10s
            }
            Integer iif = getVirtualInterfaceIndex(route.getIifIndex());
            if (iif == null) {
                Log.e(TAG, "Can't find kernel returned IIF " + route.getIifIndex());
                return;
            }
            final MfcKey key = new MfcKey(iif, (Inet6Address) route.getSource().getAddress(),
                    (Inet6Address) route.getDestination().getAddress());
            MfcValue value = mMfcs.get(key);
            if (value == null) {
                Log.e(TAG, "Can't find kernel returned MFC " + key);
            } else {
                value.setLastUsedAt(Instant.now(mDependencies.getClock())
                        .minusMillis(route.getSinceLastUseMillis()));
            }
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
        } catch (ErrnoException e) {
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
                .filter(e -> (e.getValue().getLastUsedAt()
                        .plusMillis(MFC_INACTIVE_TIMEOUT_MS)
                        .isBefore(Instant.now(mDependencies.getClock()))))
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

    private void joinGroups(int ifIndex, List<Inet6Address> addresses) {
        for (Inet6Address address : addresses) {
            try {
                mDependencies.joinGroup(ifIndex, address);
            } catch (IOException e) {
                if (e.getCause() instanceof ErrnoException) {
                    ErrnoException ee = (ErrnoException) e.getCause();
                    if (ee.errno == EADDRINUSE) {
                        // If address is already joined, don't log as error
                        continue;
                    }
                }
                Log.e(TAG, "failed to join group: " + e);
            }
        }
    }

    private void leaveGroups(int ifIndex, List<Inet6Address> addresses) {
        for (Inet6Address address : addresses) {
            try {
                mDependencies.leaveGroup(ifIndex, address);
            } catch (IOException e) {
                Log.e(TAG, "failed to leave group: " + e);
            }
        }
    }

    private int getGroupAddressScope(Inet6Address address) {
        return address.getAddress()[1] & 0xf;
    }

    @VisibleForTesting
    public void handleMulticastNocacheUpcall(final StructMrt6Msg mrt6Msg) {
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
            final StructMrt6Msg mrt6Msg = StructMrt6Msg.parse(buf);
            if (mrt6Msg.msgType != StructMrt6Msg.MRT6MSG_NOCACHE) {
                return;
            }
            handleMulticastNocacheUpcall(mrt6Msg);
        }
    }

    /**
     * Dependencies of RoutingCoordinatorService, for test injections.
     */
    @VisibleForTesting
    public abstract static class Dependencies {
        private final MulticastSocket mMulticastSocket
                = createMulticastSocket(); // For joinGroup and leaveGroup
        private final Clock mClock = Clock.system(ZoneId.systemDefault());

        /**
          * Get netd.
          */
        public abstract INetd getNetd();

        /**
         * Get handler.
         */
        @NonNull
        public abstract Handler getHandler();

        public MulticastSocket createMulticastSocket() {
            try {
                return new MulticastSocket();
            } catch (IOException e) {
                Log.e(TAG, "failed to create MulticastSocket", e);
                return null;
            }
        }

        public FileDescriptor createMulticastRoutingSocket(){
            FileDescriptor sock = null;
            byte[] filter = new byte[32]; // filter all ICMPv6 messages
            try {
                sock = Os.socket(AF_INET6, SOCK_RAW | SOCK_CLOEXEC | SOCK_NONBLOCK, IPPROTO_ICMPV6);
                Os.setsockoptInt(sock, IPPROTO_IPV6, MRT6_INIT, ONE);
                NetworkUtils.setsockoptBytes(sock, IPPROTO_ICMPV6, ICMP6_FILTER, filter);
            } catch (ErrnoException e) {
                Log.e(TAG, "failed to create multicast socket: " + e);
                if (sock != null) {
                    SocketUtils.closeSocketQuietly(sock);
                }
                return null;
            }
            Log.i(TAG, "socket created for multicast routing: " + sock);
            return sock;
        }

        public void addMulticastInterface(FileDescriptor fd, int virtualIfIndex, int ifIndex)
                throws ErrnoException {
            final StructMif6ctl mif6ctl = new StructMif6ctl(virtualIfIndex,
                    (short) 0 /* mif6c_flags */, (short) 1 /* vifc_threshold */, ifIndex,
                    0 /* vifc_rate_limit */);
            final byte[] bytes = mif6ctl.writeToBytes();
            Log.d(TAG, "Added mifi " + virtualIfIndex + " to MIF");
            NetworkUtils.setsockoptBytes(fd, IPPROTO_IPV6, MRT6_ADD_MIF, bytes);
        }

        public void removeMulticastInterface(FileDescriptor fd, int virtualIfIndex)
                throws ErrnoException {
            Os.setsockoptInt(fd, IPPROTO_IPV6, MRT6_DEL_MIF, virtualIfIndex);
            Log.d(TAG, "Removed mifi " + virtualIfIndex + " from MIF");
        }

        public void addMulticastForwardingCache(
                FileDescriptor fd, int iif, Set<Integer> oifs, Inet6Address src, Inet6Address dst)
                throws ErrnoException {
            final StructMf6cctl mf6cctl = new StructMf6cctl(src, dst, iif, oifs);
            final byte[] bytes = mf6cctl.writeToBytes();
            NetworkUtils.setsockoptBytes(fd, IPPROTO_IPV6, MRT6_ADD_MFC, bytes);
        }

        public void removeMulticastForwardingCache(
                FileDescriptor fd, int iif, Inet6Address src, Inet6Address dst)
                throws ErrnoException {
            Set<Integer> oifs = new ArraySet<>();
            final StructMf6cctl mf6cctl = new StructMf6cctl(src, dst, iif, oifs);
            final byte[] bytes = mf6cctl.writeToBytes();
            NetworkUtils.setsockoptBytes(fd, IPPROTO_IPV6, MRT6_DEL_MFC, bytes);
        }

        public void joinGroup(int ifIndex, Inet6Address addr)
                throws IOException {
            if (mMulticastSocket == null) {
                return;
            }
            InetSocketAddress address = new InetSocketAddress(addr, 0);
            mMulticastSocket.joinGroup(address, NetworkInterface.getByIndex(ifIndex));
        }

        public void leaveGroup(int ifIndex, Inet6Address addr)
                throws IOException {
            if (mMulticastSocket == null) {
                return;
            }
            InetSocketAddress address = new InetSocketAddress(addr, 0);
            mMulticastSocket.leaveGroup(address, NetworkInterface.getByIndex(ifIndex));
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

        public Clock getClock() {
            return mClock;
        }
    }
}
