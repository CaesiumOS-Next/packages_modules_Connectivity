/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.networkstack.tethering;

import static com.android.networkstack.tethering.Tethering.NETID_UNSET;

import android.net.ConnectivityManager;
import android.net.IDnsResolver;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TetheringManager.TetheringRequest;
import android.net.ip.IpServer;
import android.util.ArrayMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.util.StateMachine;
import com.android.net.module.util.RoutingCoordinatorManager;
import com.android.net.module.util.SharedLog;
import com.android.networkstack.tethering.util.InterfaceSet;

/**
 * VmNetworkCoordinator tracks all the VM networks, which track a different upstream than tethered
 * networks with separate routing rules. The class is introduced to isolate such state changes from
 * tethered network.
 */
public class VmNetworkCoordinator {
    private static final String TAG = VmNetworkCoordinator.class.getSimpleName();
    private final IDnsResolver mDnsResolver;
    private final ArrayMap<Integer, IpServer> mVmIpServers;
    private final SharedLog mLog;
    private final RoutingCoordinatorManager mRoutingCoordinator;
    private final ArrayMap<Integer, VmUpstreamCallback> mVmUpstreamCallbacks;

    public VmNetworkCoordinator(IDnsResolver dnsResolver, SharedLog log,
            RoutingCoordinatorManager routingCoordinator) {
        mVmIpServers = new ArrayMap<>();
        mDnsResolver = dnsResolver;
        mLog = log.forSubComponent(TAG);
        mRoutingCoordinator = routingCoordinator;
        mVmUpstreamCallbacks = new ArrayMap<>();
    }

    /**
     * resets VmNetworkCoordinator to initial state, stop all currently active IpServers attached.
     */
    public void reset() {
        mVmUpstreamCallbacks.clear();
        mVmIpServers.forEach(((requestUid, ipServer) -> setVmUpstreamNetwork(null, ipServer)));
        mVmIpServers.clear();
    }

    /**
     * addVmUpstreamCallback adds a callback to the coordinator.
     *
     * @param callback the callback to be added.
     * @return true if the callback is added, false if a callback with same uid is already present.
     */
    public boolean addVmUpstreamCallback(@NonNull VmUpstreamCallback callback) {
        int requestUid = callback.getTetheringRequest().getUid();
        if (mVmUpstreamCallbacks.containsKey(requestUid)) {
            mLog.e("Add VM UpstreamCallback failed: " + callback);
            return false;
        }
        mVmUpstreamCallbacks.put(requestUid, callback);
        return true;
    }

    /**
     * gets VM callback given the UID of the request.
     *
     * @param requestUid to find corresponding IpServer
     * @return corresponding callback, null if none is found.
     */
    @Nullable
    public VmUpstreamCallback getVmUpstreamCallback(int requestUid) {
        return mVmUpstreamCallbacks.get(requestUid);
    }

    /**
     * removes the VM Callback
     *
     * @param callback to be removed
     * @return {@code true} if removed, {@code false} if not found.
     */
    public boolean removeVmUpstreamCallback(VmUpstreamCallback callback) {
        int requestUid = callback.getTetheringRequest().getUid();
        return mVmUpstreamCallbacks.remove(requestUid, callback);
    }

    /**
     * addVmIpServer adds an IpServer corresponding to a previously added VmUpstreamCallback.
     *
     * @param ipServer
     * @return {@code true} if the IpServer is added, {@code false} if either an IpServer with same
     *     uid is already present, or there is no corresponding callback.
     */
    public boolean addVmIpServer(@NonNull IpServer ipServer) {
        int requestUid = getRequestUidForIpServer(ipServer);
        if (!mVmUpstreamCallbacks.containsKey(requestUid)) {
            mLog.e("Missing VM UpstreamCallback for app " + requestUid);
            return false;
        }
        if (mVmIpServers.containsKey(requestUid)) {
            mLog.e("Duplicate IpServer already exist for app " + requestUid);
            return false;
        }
        mVmIpServers.put(requestUid, ipServer);
        return true;
    }

    /**
     * gets VM IpServer given the UID of the request.
     *
     * @param requestUid to find corresponding IpServer
     * @return corresponding IpServer, null if none is found.
     */
    @Nullable
    public IpServer getVmIpServer(int requestUid) {
        return mVmIpServers.get(requestUid);
    }

    /**
     * removes the VM IpServer
     *
     * @param ipServer to be removed
     * @return {@code true} if removed, {@code false} if not found.
     */
    public boolean removeVmIpServer(IpServer ipServer) {
        int requestUid = getRequestUidForIpServer(ipServer);
        return mVmIpServers.remove(requestUid, ipServer);
    }

    /**
     * check if there are VM IpServer.
     *
     * @return {@code true} if there is some, {@code false} if none.
     */
    public boolean hasVmIpServer() {
        return !mVmIpServers.isEmpty();
    }

    /**
     * handles Upstream callback for a VM
     * @param callbackVariant the callback action
     * @param requestUid the UID of the app made the tethering request
     * @param obj the {@link UpstreamNetworkState}.
     * @return true if callback is handled.
     */
    public boolean handleVmUpstreamCallback(int callbackVariant, int requestUid, Object obj) {
        mLog.i("DEBUG: handle callback: " + callbackVariant);
        final UpstreamNetworkState ns = (UpstreamNetworkState) obj;
        IpServer ipServer = mVmIpServers.get(requestUid);
        if (ipServer == null) {
            return false;
        }
        switch (callbackVariant) {
            case VmNetworkCoordinator.VmUpstreamCallback.EVENT_UPSTREAM_LOST:
                mRoutingCoordinator.removeUpstreamPrefix(ns.network);
                // TODO: handle lost of IPv6, BPF, offload.
                mLog.i("DEBUG: Received EVENT_UPSTREAM_LOST");
                setVmUpstreamNetwork(null, ipServer);
                break;
            case VmNetworkCoordinator.VmUpstreamCallback.EVENT_CAPABILITY_CHANGED:
                // TODO: Figure out if equivalent of TetheringNotificationUpdater is needed.
                // TODO: handle IPv6, BPF, offload.
                break;
            case VmNetworkCoordinator.VmUpstreamCallback.EVENT_LINK_PROPERTIES_CHANGED:
                mLog.i("DEBUG: Received EVENT_LINK_PROPERTIES_CHANGED. ns: " + ns);
                mRoutingCoordinator.updateUpstreamPrefix(
                        ns.linkProperties, ns.networkCapabilities, ns.network);
                setVmUpstreamNetwork(ns, ipServer);
                break;
            case VmNetworkCoordinator.VmUpstreamCallback.EVENT_UPSTREAM_SWITCHED:
                mLog.i("DEBUG: Received EVENT_UPSTREAM_SWITCHED. ns: " + ns);
                setVmUpstreamNetwork(ns, ipServer);
                break;
            default:
                mLog.e("Unknown VM_UPSTREAM_CALLBACK variant: " + callbackVariant);
                return false;
        }
        return true;
    }

    private int getRequestUidForIpServer(IpServer ipServer) {
        TetheringRequest request = ipServer.getTetheringRequest();
        if (request == null) {
            mLog.e("Cannot find tethering request for IPServer " + ipServer);
            // Using placeholder UID 1000 while b/216524590 is in development.
            return 1000;
        }
        return request.getUid();
    }

    private void setVmUpstreamNetwork(UpstreamNetworkState ns, IpServer ipServer) {
        InterfaceSet ifaces = null;
        if (ns != null) {
            // Find the interface with the default IPv4 route. It may be the
            // interface described by linkProperties, or one of the interfaces
            // stacked on top of it.
            mLog.i("Looking for default routes on: " + ns.linkProperties);
            ifaces = TetheringInterfaceUtils.getTetheringInterfaces(ns);
            mLog.i("Found upstream interface(s): " + ifaces);
            ipServer.setDnsForwarders(ns.network.getNetId());
        } else {
            ipServer.setDnsForwarders(NETID_UNSET);
        }


        mLog.i(
                "DEBUG: Set US iface for DS "
                        + ipServer.interfaceName()
                        + "@setVmUpstreamNetwork: "
                        + ifaces);
        ipServer.sendMessage(IpServer.CMD_TETHER_CONNECTION_CHANGED, ifaces);
    }

    /**
     * VmUpstreamCallback tracks the upstream network for a VM instance.
     *
     * @hide
     */
    public static class VmUpstreamCallback extends ConnectivityManager.NetworkCallback {

        public static final int EVENT_UPSTREAM_LOST = 1;
        public static final int EVENT_CAPABILITY_CHANGED = 2;
        public static final int EVENT_LINK_PROPERTIES_CHANGED = 3;
        public static final int EVENT_UPSTREAM_SWITCHED = 4;
        private final TetheringRequest mTetheringRequest;
        private final EventListener mEventListener;
        private UpstreamNetworkState mNetworkState;
        private Network mUpstreamNetwork;

        public VmUpstreamCallback(TetheringRequest tetheringRequest, EventListener eventListener) {
            mTetheringRequest = tetheringRequest;
            mEventListener = eventListener;
        }

        @NonNull
        public TetheringRequest getTetheringRequest() {
            return mTetheringRequest;
        }

        @Override
        public void onAvailable(Network network) {
            if (!network.equals(mUpstreamNetwork)) {
                mNetworkState = new UpstreamNetworkState(null, null, network);
            }
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities newNc) {
            if (!network.equals(mNetworkState.network)
                    || newNc.equals(mNetworkState.networkCapabilities)) {
                // Ignore notifications about networks for which we have not yet
                // received onAvailable() (should never happen) and any duplicate
                // notifications (e.g. matching more than one of our callbacks).
                return;
            }
            mNetworkState = new UpstreamNetworkState(mNetworkState.linkProperties, newNc, network);
            if (network.equals(mUpstreamNetwork)) {
                mEventListener.onUpstreamEvent(
                        EVENT_CAPABILITY_CHANGED, mTetheringRequest.getUid(), mNetworkState);
            }
        }

        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties newLp) {
            if (!network.equals(mNetworkState.network)
                    || newLp.equals(mNetworkState.linkProperties)) {
                // Ignore notifications about networks for which we have not yet
                // received onAvailable() (should never happen) and any duplicate
                // notifications (e.g. matching more than one of our callbacks).
                return;
            }
            mNetworkState =
                    new UpstreamNetworkState(newLp, mNetworkState.networkCapabilities, network);
            if (network.equals(mUpstreamNetwork)) {
                mEventListener.onUpstreamEvent(
                        EVENT_LINK_PROPERTIES_CHANGED, mTetheringRequest.getUid(), mNetworkState);
            } else {
                mUpstreamNetwork = network;
                mEventListener.onUpstreamEvent(
                        EVENT_UPSTREAM_SWITCHED, mTetheringRequest.getUid(), mNetworkState);
            }
        }

        @Override
        public void onLost(Network network) {
            if (!network.equals(mNetworkState.network)) {
                // Ignore notifications about network which was not added by onAvailable.
                return;
            }
            mEventListener.onUpstreamEvent(
                    EVENT_UPSTREAM_LOST, mTetheringRequest.getUid(), mNetworkState);
            mNetworkState = null;
            mUpstreamNetwork = null;
        }

        /** An interface to notify upstream network changes. */
        public interface EventListener {
            /**
             * Notify the client of some event, format has to fit in {@link
             * StateMachine#sendMessage}
             */
            void onUpstreamEvent(int callbackVariant, int requestUid, Object obj);
        }
    }
}