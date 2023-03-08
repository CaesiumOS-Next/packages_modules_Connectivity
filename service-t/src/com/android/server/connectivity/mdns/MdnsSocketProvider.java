/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.connectivity.mdns;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.INetd;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.TetheringManager;
import android.net.TetheringManager.TetheringEventCallback;
import android.os.Handler;
import android.os.Looper;
import android.system.OsConstants;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.LinkPropertiesUtils.CompareResult;
import com.android.net.module.util.ip.NetlinkMonitor;
import com.android.net.module.util.netlink.NetlinkConstants;
import com.android.net.module.util.netlink.NetlinkMessage;
import com.android.net.module.util.netlink.RtNetlinkAddressMessage;
import com.android.net.module.util.netlink.StructIfaddrMsg;
import com.android.server.connectivity.mdns.util.MdnsLogger;

import java.io.IOException;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@link MdnsSocketProvider} manages the multiple sockets for mDns.
 *
 * <p>This class is not thread safe, it is intended to be used only from the looper thread.
 * However, the constructor is an exception, as it is called on another thread;
 * therefore for thread safety all members of this class MUST either be final or initialized
 * to their default value (0, false or null).
 *
 */
public class MdnsSocketProvider {
    private static final String TAG = MdnsSocketProvider.class.getSimpleName();
    private static final boolean DBG = MdnsDiscoveryManager.DBG;
    // This buffer size matches what MdnsSocketClient uses currently.
    // But 1440 should generally be enough because of standard Ethernet.
    // Note: mdnsresponder mDNSEmbeddedAPI.h uses 8940 for Ethernet jumbo frames.
    private static final int READ_BUFFER_SIZE = 2048;
    private static final MdnsLogger LOGGER = new MdnsLogger(TAG);
    private static final int IFACE_IDX_NOT_EXIST = -1;
    @NonNull private final Context mContext;
    @NonNull private final Looper mLooper;
    @NonNull private final Handler mHandler;
    @NonNull private final Dependencies mDependencies;
    @NonNull private final NetworkCallback mNetworkCallback;
    @NonNull private final TetheringEventCallback mTetheringEventCallback;
    @NonNull private final NetlinkMonitor mNetlinkMonitor;
    private final ArrayMap<Network, SocketInfo> mNetworkSockets = new ArrayMap<>();
    private final ArrayMap<String, SocketInfo> mTetherInterfaceSockets = new ArrayMap<>();
    private final ArrayMap<Network, LinkProperties> mActiveNetworksLinkProperties =
            new ArrayMap<>();
    private final ArrayMap<SocketCallback, Network> mCallbacksToRequestedNetworks =
            new ArrayMap<>();
    private final List<String> mLocalOnlyInterfaces = new ArrayList<>();
    private final List<String> mTetheredInterfaces = new ArrayList<>();
    private final SparseArray<LinkProperties> mIfaceIdxToLinkProperties = new SparseArray<>();
    private final ArrayMap<String, Integer> mIfaceNameToIndexMap = new ArrayMap<>();
    private final byte[] mPacketReadBuffer = new byte[READ_BUFFER_SIZE];
    private boolean mMonitoringSockets = false;
    private boolean mRequestStop = false;

    public MdnsSocketProvider(@NonNull Context context, @NonNull Looper looper) {
        this(context, looper, new Dependencies());
    }

    MdnsSocketProvider(@NonNull Context context, @NonNull Looper looper,
            @NonNull Dependencies deps) {
        mContext = context;
        mLooper = looper;
        mHandler = new Handler(looper);
        mDependencies = deps;
        mNetworkCallback = new NetworkCallback() {
            @Override
            public void onLost(Network network) {
                mActiveNetworksLinkProperties.remove(network);
                removeSocket(network, null /* interfaceName */);
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                handleLinkPropertiesChanged(network, lp);
            }
        };
        mTetheringEventCallback = new TetheringEventCallback() {
            @Override
            public void onLocalOnlyInterfacesChanged(@NonNull List<String> interfaces) {
                handleTetherInterfacesChanged(mLocalOnlyInterfaces, interfaces);
            }

            @Override
            public void onTetheredInterfacesChanged(@NonNull List<String> interfaces) {
                handleTetherInterfacesChanged(mTetheredInterfaces, interfaces);
            }
        };

        mNetlinkMonitor = mDependencies.getSocketNetlinkMonitor(mHandler,
                new NetLinkMessageProcessor());
    }

    /**
     * Dependencies of MdnsSocketProvider, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        /*** Get network interface by given interface name */
        public NetworkInterfaceWrapper getNetworkInterfaceByName(@NonNull String interfaceName)
                throws SocketException {
            final NetworkInterface ni = NetworkInterface.getByName(interfaceName);
            return ni == null ? null : new NetworkInterfaceWrapper(ni);
        }

        /*** Check whether given network interface can support mdns */
        public boolean canScanOnInterface(@NonNull NetworkInterfaceWrapper networkInterface) {
            return MulticastNetworkInterfaceProvider.canScanOnInterface(networkInterface);
        }

        /*** Create a MdnsInterfaceSocket */
        public MdnsInterfaceSocket createMdnsInterfaceSocket(
                @NonNull NetworkInterface networkInterface, int port, @NonNull Looper looper,
                @NonNull byte[] packetReadBuffer) throws IOException {
            return new MdnsInterfaceSocket(networkInterface, port, looper, packetReadBuffer);
        }

        /*** Get network interface by given interface name */
        public int getNetworkInterfaceIndexByName(@NonNull final String ifaceName) {
            // Only resolve on non-stacked interfaces
            final NetworkInterface iface;
            try {
                iface = NetworkInterface.getByName(ifaceName);
            } catch (SocketException e) {
                Log.e(TAG, "Error querying interface", e);
                return IFACE_IDX_NOT_EXIST;
            }

            if (iface == null) {
                Log.e(TAG, "Interface not found: " + ifaceName);
                return IFACE_IDX_NOT_EXIST;
            }

            return iface.getIndex();
        }

        /*** Get a SocketNetlinkMonitor */
        public SocketNetlinkMonitor getSocketNetlinkMonitor(@NonNull final Handler handler,
                @NonNull final NetLinkMonitorCallBack cb) {
            return new SocketNetlinkMonitor(handler, cb);
        }
    }

    /**
     * The callback interface for the netlink monitor messages.
     */
    public interface NetLinkMonitorCallBack {
        /**
         * Handles the RtNetlinkAddressMessage.
         */
        void processRtNetlinkAddressMessage(RtNetlinkAddressMessage msg);
    }

    private class NetLinkMessageProcessor implements NetLinkMonitorCallBack {
        @Override
        public void processRtNetlinkAddressMessage(RtNetlinkAddressMessage msg) {
            final StructIfaddrMsg ifaddrMsg = msg.getIfaddrHeader();
            final LinkAddress la = new LinkAddress(msg.getIpAddress(), ifaddrMsg.prefixLen,
                    msg.getFlags(), ifaddrMsg.scope);
            if (!la.isPreferred()) {
                // Skip the unusable ip address.
                return;
            }

            switch (msg.getHeader().nlmsg_type) {
                case NetlinkConstants.RTM_NEWADDR:
                    updateInterfaceAddress(ifaddrMsg.index, la, true /* add */);
                    break;
                case NetlinkConstants.RTM_DELADDR:
                    updateInterfaceAddress(ifaddrMsg.index, la, false /* add */);
                    break;
                default:
                    Log.e(TAG, "Unknown rtnetlink address msg type " + msg.getHeader().nlmsg_type);
            }
        }

        private void updateInterfaceAddress(int ifaceIdx, @NonNull final LinkAddress newAddress,
                boolean add) {
            LinkProperties linkProperties;
            if (add) {
                linkProperties = mIfaceIdxToLinkProperties.get(ifaceIdx);
                if (linkProperties == null) {
                    linkProperties = new LinkProperties();
                    mIfaceIdxToLinkProperties.put(ifaceIdx, linkProperties);
                }
                linkProperties.addLinkAddress(newAddress);
            } else {
                linkProperties = mIfaceIdxToLinkProperties.get(ifaceIdx);
                if (linkProperties != null) {
                    linkProperties.removeLinkAddress(newAddress);
                    if (linkProperties.getLinkAddresses().isEmpty()) {
                        mIfaceIdxToLinkProperties.remove(ifaceIdx);
                    }
                }
            }
            if (linkProperties == null) {
                return;
            }
            maybeUpdateTetheringSocketAddress(ifaceIdx, linkProperties.getLinkAddresses());
        }
    }

    /*** Data class for storing socket related info  */
    private static class SocketInfo {
        final MdnsInterfaceSocket mSocket;
        final List<LinkAddress> mAddresses;

        SocketInfo(MdnsInterfaceSocket socket, List<LinkAddress> addresses) {
            mSocket = socket;
            mAddresses = new ArrayList<>(addresses);
        }
    }

    /**
     * The netlink monitor for MdnsSocketProvider.
     */
    public static class SocketNetlinkMonitor extends NetlinkMonitor {

        @NonNull final NetLinkMonitorCallBack mCb;

        SocketNetlinkMonitor(@NonNull final Handler handler,
                @NonNull final NetLinkMonitorCallBack cb) {
            super(handler, LOGGER.mLog, TAG, OsConstants.NETLINK_ROUTE,
                    NetlinkConstants.RTMGRP_IPV4_IFADDR | NetlinkConstants.RTMGRP_IPV6_IFADDR);
            mCb = cb;
        }

        @Override
        public void processNetlinkMessage(NetlinkMessage nlMsg, long whenMs) {
            if (nlMsg instanceof RtNetlinkAddressMessage) {
                mCb.processRtNetlinkAddressMessage((RtNetlinkAddressMessage) nlMsg);
            }
        }
    }

    /*** Ensure that current running thread is same as given handler thread */
    public static void ensureRunningOnHandlerThread(Handler handler) {
        if (handler.getLooper().getThread() != Thread.currentThread()) {
            throw new IllegalStateException(
                    "Not running on Handler thread: " + Thread.currentThread().getName());
        }
    }

    /*** Start monitoring sockets by listening callbacks for sockets creation or removal */
    public void startMonitoringSockets() {
        ensureRunningOnHandlerThread(mHandler);
        mRequestStop = false; // Reset stop request flag.
        if (mMonitoringSockets) {
            Log.d(TAG, "Already monitoring sockets.");
            return;
        }
        if (DBG) Log.d(TAG, "Start monitoring sockets.");
        mContext.getSystemService(ConnectivityManager.class).registerNetworkCallback(
                new NetworkRequest.Builder().clearCapabilities().build(),
                mNetworkCallback, mHandler);

        final TetheringManager tetheringManager = mContext.getSystemService(TetheringManager.class);
        tetheringManager.registerTetheringEventCallback(mHandler::post, mTetheringEventCallback);

        mMonitoringSockets = true;
    }

    /**
     * Start netlink monitor.
     */
    public void startNetLinkMonitor() {
        mHandler.post(mNetlinkMonitor::start);
    }

    private void maybeStopMonitoringSockets() {
        if (!mMonitoringSockets) return; // Already unregistered.
        if (!mRequestStop) return; // No stop request.

        // Only unregister the network callback if there is no socket request.
        if (mCallbacksToRequestedNetworks.isEmpty()) {
            mContext.getSystemService(ConnectivityManager.class)
                    .unregisterNetworkCallback(mNetworkCallback);

            final TetheringManager tetheringManager = mContext.getSystemService(
                    TetheringManager.class);
            tetheringManager.unregisterTetheringEventCallback(mTetheringEventCallback);

            mMonitoringSockets = false;
        }
    }

    /*** Request to stop monitoring sockets and unregister callbacks */
    public void requestStopWhenInactive() {
        ensureRunningOnHandlerThread(mHandler);
        if (!mMonitoringSockets) {
            Log.d(TAG, "Monitoring sockets hasn't been started.");
            return;
        }
        if (DBG) Log.d(TAG, "Try to stop monitoring sockets.");
        mRequestStop = true;
        maybeStopMonitoringSockets();
    }

    /*** Check whether the target network is matched current network */
    public static boolean isNetworkMatched(@Nullable Network targetNetwork,
            @NonNull Network currentNetwork) {
        return targetNetwork == null || targetNetwork.equals(currentNetwork);
    }

    private boolean matchRequestedNetwork(Network network) {
        return hasAllNetworksRequest()
                || mCallbacksToRequestedNetworks.containsValue(network);
    }

    private boolean hasAllNetworksRequest() {
        return mCallbacksToRequestedNetworks.containsValue(null);
    }

    private void handleLinkPropertiesChanged(Network network, LinkProperties lp) {
        mActiveNetworksLinkProperties.put(network, lp);
        if (!matchRequestedNetwork(network)) {
            if (DBG) {
                Log.d(TAG, "Ignore LinkProperties change. There is no request for the"
                        + " Network:" + network);
            }
            return;
        }

        final SocketInfo socketInfo = mNetworkSockets.get(network);
        if (socketInfo == null) {
            createSocket(network, lp);
        } else {
            // Update the addresses of this socket.
            final List<LinkAddress> addresses = lp.getLinkAddresses();
            socketInfo.mAddresses.clear();
            socketInfo.mAddresses.addAll(addresses);
            // Try to join the group again.
            socketInfo.mSocket.joinGroup(addresses);

            notifyAddressesChanged(network, socketInfo.mSocket, addresses);
        }
    }

    private void maybeUpdateTetheringSocketAddress(int ifaceIndex,
            @NonNull final List<LinkAddress> updatedAddresses) {
        for (int i = 0; i < mTetherInterfaceSockets.size(); ++i) {
            String tetheringInterfaceName = mTetherInterfaceSockets.keyAt(i);
            if (mIfaceNameToIndexMap.get(tetheringInterfaceName) == ifaceIndex) {
                final SocketInfo socketInfo = mTetherInterfaceSockets.valueAt(i);
                socketInfo.mAddresses.clear();
                socketInfo.mAddresses.addAll(updatedAddresses);
                socketInfo.mSocket.joinGroup(updatedAddresses);
                notifyAddressesChanged(new Network(INetd.LOCAL_NET_ID), socketInfo.mSocket,
                        updatedAddresses);
                return;
            }
        }
    }

    private LinkProperties createLPForTetheredInterface(@NonNull final String interfaceName,
            int ifaceIndex) {
        final LinkProperties linkProperties = new LinkProperties();
        linkProperties.setInterfaceName(interfaceName);
        List<LinkAddress> addresses;
        if (ifaceIndex == IFACE_IDX_NOT_EXIST) {
            addresses = new ArrayList<>();
        } else {
            LinkProperties cachedLinkProperties = mIfaceIdxToLinkProperties.get(ifaceIndex);
            if (cachedLinkProperties == null) {
                addresses = new ArrayList<>();
            } else {
                addresses = cachedLinkProperties.getLinkAddresses();
            }
        }
        linkProperties.setLinkAddresses(addresses);
        return linkProperties;
    }

    private void handleTetherInterfacesChanged(List<String> current, List<String> updated) {
        if (!hasAllNetworksRequest()) {
            // Currently, the network for tethering can not be requested, so the sockets for
            // tethering are only created if there is a request for all networks (interfaces).
            // Therefore, this change can skip if there is no such request.
            if (DBG) {
                Log.d(TAG, "Ignore tether interfaces change. There is no request for all"
                        + " networks.");
            }
            return;
        }

        final CompareResult<String> interfaceDiff = new CompareResult<>(
                current, updated);
        for (String name : interfaceDiff.added) {
            int ifaceIndex = mDependencies.getNetworkInterfaceIndexByName(name);
            createSocket(new Network(INetd.LOCAL_NET_ID),
                    createLPForTetheredInterface(name, ifaceIndex));
            if (ifaceIndex != IFACE_IDX_NOT_EXIST) {
                mIfaceNameToIndexMap.put(name, ifaceIndex);
            }
        }
        for (String name : interfaceDiff.removed) {
            removeSocket(new Network(INetd.LOCAL_NET_ID), name);
            mIfaceNameToIndexMap.remove(name);
        }
        current.clear();
        current.addAll(updated);
    }

    private void createSocket(Network network, LinkProperties lp) {
        final String interfaceName = lp.getInterfaceName();
        if (interfaceName == null) {
            Log.e(TAG, "Can not create socket with null interface name.");
            return;
        }

        try {
            final NetworkInterfaceWrapper networkInterface =
                    mDependencies.getNetworkInterfaceByName(interfaceName);
            if (networkInterface == null || !mDependencies.canScanOnInterface(networkInterface)) {
                return;
            }

            if (DBG) {
                Log.d(TAG, "Create a socket on network:" + network
                        + " with interfaceName:" + interfaceName);
            }
            final MdnsInterfaceSocket socket = mDependencies.createMdnsInterfaceSocket(
                    networkInterface.getNetworkInterface(), MdnsConstants.MDNS_PORT, mLooper,
                    mPacketReadBuffer);
            final List<LinkAddress> addresses = lp.getLinkAddresses();
            if (network.netId == INetd.LOCAL_NET_ID) {
                mTetherInterfaceSockets.put(interfaceName, new SocketInfo(socket, addresses));
            } else {
                mNetworkSockets.put(network, new SocketInfo(socket, addresses));
            }
            // Try to join IPv4/IPv6 group.
            socket.joinGroup(addresses);

            // Notify the listeners which need this socket.
            notifySocketCreated(network, socket, addresses);
        } catch (IOException e) {
            Log.e(TAG, "Create a socket failed with interface=" + interfaceName, e);
        }
    }

    private void removeSocket(Network network, String interfaceName) {
        final SocketInfo socketInfo = network.netId == INetd.LOCAL_NET_ID
                ? mTetherInterfaceSockets.remove(interfaceName)
                : mNetworkSockets.remove(network);
        if (socketInfo == null) return;

        socketInfo.mSocket.destroy();
        notifyInterfaceDestroyed(network, socketInfo.mSocket);
    }

    private void notifySocketCreated(Network network, MdnsInterfaceSocket socket,
            List<LinkAddress> addresses) {
        for (int i = 0; i < mCallbacksToRequestedNetworks.size(); i++) {
            final Network requestedNetwork = mCallbacksToRequestedNetworks.valueAt(i);
            if (isNetworkMatched(requestedNetwork, network)) {
                mCallbacksToRequestedNetworks.keyAt(i).onSocketCreated(network, socket, addresses);
            }
        }
    }

    private void notifyInterfaceDestroyed(Network network, MdnsInterfaceSocket socket) {
        for (int i = 0; i < mCallbacksToRequestedNetworks.size(); i++) {
            final Network requestedNetwork = mCallbacksToRequestedNetworks.valueAt(i);
            if (isNetworkMatched(requestedNetwork, network)) {
                mCallbacksToRequestedNetworks.keyAt(i).onInterfaceDestroyed(network, socket);
            }
        }
    }

    private void notifyAddressesChanged(Network network, MdnsInterfaceSocket socket,
            List<LinkAddress> addresses) {
        for (int i = 0; i < mCallbacksToRequestedNetworks.size(); i++) {
            final Network requestedNetwork = mCallbacksToRequestedNetworks.valueAt(i);
            if (isNetworkMatched(requestedNetwork, network)) {
                mCallbacksToRequestedNetworks.keyAt(i)
                        .onAddressesChanged(network, socket, addresses);
            }
        }
    }

    private void retrieveAndNotifySocketFromNetwork(Network network, SocketCallback cb) {
        final SocketInfo socketInfo = mNetworkSockets.get(network);
        if (socketInfo == null) {
            final LinkProperties lp = mActiveNetworksLinkProperties.get(network);
            if (lp == null) {
                // The requested network is not existed. Maybe wait for LinkProperties change later.
                if (DBG) Log.d(TAG, "There is no LinkProperties for this network:" + network);
                return;
            }
            createSocket(network, lp);
        } else {
            // Notify the socket for requested network.
            cb.onSocketCreated(network, socketInfo.mSocket, socketInfo.mAddresses);
        }
    }

    private void retrieveAndNotifySocketFromInterface(String interfaceName, SocketCallback cb) {
        final SocketInfo socketInfo = mTetherInterfaceSockets.get(interfaceName);
        if (socketInfo == null) {
            int ifaceIndex = mDependencies.getNetworkInterfaceIndexByName(interfaceName);
            createSocket(
                    new Network(INetd.LOCAL_NET_ID),
                    createLPForTetheredInterface(interfaceName, ifaceIndex));
            if (ifaceIndex != IFACE_IDX_NOT_EXIST) {
                mIfaceNameToIndexMap.put(interfaceName, ifaceIndex);
            }
        } else {
            // Notify the socket for requested network.
            cb.onSocketCreated(
                    new Network(INetd.LOCAL_NET_ID), socketInfo.mSocket, socketInfo.mAddresses);
        }
    }

    /**
     * Request a socket for given network.
     *
     * @param network the required network for a socket. Null means create sockets on all possible
     *                networks (interfaces).
     * @param cb the callback to listen the socket creation.
     */
    public void requestSocket(@Nullable Network network, @NonNull SocketCallback cb) {
        ensureRunningOnHandlerThread(mHandler);
        mCallbacksToRequestedNetworks.put(cb, network);
        if (network == null) {
            // Does not specify a required network, create sockets for all possible
            // networks (interfaces).
            for (int i = 0; i < mActiveNetworksLinkProperties.size(); i++) {
                retrieveAndNotifySocketFromNetwork(mActiveNetworksLinkProperties.keyAt(i), cb);
            }

            for (String localInterface : mLocalOnlyInterfaces) {
                retrieveAndNotifySocketFromInterface(localInterface, cb);
            }

            for (String tetheredInterface : mTetheredInterfaces) {
                retrieveAndNotifySocketFromInterface(tetheredInterface, cb);
            }
        } else {
            retrieveAndNotifySocketFromNetwork(network, cb);
        }
    }

    /*** Unrequest the socket */
    public void unrequestSocket(@NonNull SocketCallback cb) {
        ensureRunningOnHandlerThread(mHandler);
        mCallbacksToRequestedNetworks.remove(cb);
        if (hasAllNetworksRequest()) {
            // Still has a request for all networks (interfaces).
            return;
        }

        // Check if remaining requests are matched any of sockets.
        for (int i = mNetworkSockets.size() - 1; i >= 0; i--) {
            final Network network = mNetworkSockets.keyAt(i);
            if (matchRequestedNetwork(network)) continue;
            final SocketInfo info = mNetworkSockets.removeAt(i);
            info.mSocket.destroy();
            // Still notify to unrequester for socket destroy.
            cb.onInterfaceDestroyed(network, info.mSocket);
        }

        // Remove all sockets for tethering interface because these sockets do not have associated
        // networks, and they should invoke by a request for all networks (interfaces). If there is
        // no such request, the sockets for tethering interface should be removed.
        for (int i = mTetherInterfaceSockets.size() - 1; i >= 0; i--) {
            final SocketInfo info = mTetherInterfaceSockets.valueAt(i);
            info.mSocket.destroy();
            // Still notify to unrequester for socket destroy.
            cb.onInterfaceDestroyed(new Network(INetd.LOCAL_NET_ID), info.mSocket);
        }
        mTetherInterfaceSockets.clear();

        // Try to unregister network callback.
        maybeStopMonitoringSockets();
    }

    /*** Callbacks for listening socket changes */
    public interface SocketCallback {
        /*** Notify the socket is created */
        default void onSocketCreated(@NonNull Network network, @NonNull MdnsInterfaceSocket socket,
                @NonNull List<LinkAddress> addresses) {}
        /*** Notify the interface is destroyed */
        default void onInterfaceDestroyed(@NonNull Network network,
                @NonNull MdnsInterfaceSocket socket) {}
        /*** Notify the addresses is changed on the network */
        default void onAddressesChanged(@NonNull Network network,
                @NonNull MdnsInterfaceSocket socket, @NonNull List<LinkAddress> addresses) {}
    }
}
