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
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TetheringManager;
import android.net.TetheringManager.TetheringEventCallback;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.LinkPropertiesUtils.CompareResult;

import java.io.IOException;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/*** The {@link MdnsSocketProvider} manages the multiple sockets for mDns */
public class MdnsSocketProvider {
    private static final String TAG = MdnsSocketProvider.class.getSimpleName();
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    @NonNull private final Context mContext;
    @NonNull private final Dependencies mDependencies;
    @NonNull private final NetworkCallback mNetworkCallback;
    @NonNull private final TetheringEventCallback mTetheringEventCallback;
    private final Map<Network, MdnsInterfaceSocket> mNetworkSockets = new ArrayMap<>();
    private final Map<String, MdnsInterfaceSocket> mTetherInterfaceSockets = new ArrayMap<>();
    private final List<String> mLocalOnlyInterfaces = new ArrayList<>();
    private final List<String> mTetheredInterfaces = new ArrayList<>();
    private final Set<SocketCallback> mSocketCallbacks = new ArraySet<>();
    private boolean mMonitoringSockets = false;
    private ConnectivityManager mConnectivityManager;

    public MdnsSocketProvider(@NonNull Context context) {
        this(context, new Dependencies());
    }

    MdnsSocketProvider(@NonNull Context context, @NonNull Dependencies deps) {
        mContext = context;
        mDependencies = deps;
        mNetworkCallback = new NetworkCallback() {
            @Override
            public void onLost(Network network) {
                removeSocket(network, null);
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                final MdnsInterfaceSocket socket = mNetworkSockets.get(network);
                if (socket == null) {
                    createSocket(network, lp);
                } else {
                    notifyAddressesChanged(network, lp);
                }
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
    }

    /**
     * Dependencies of MdnsSocketProvider, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        /*** Get network interface by given interface name */
        public NetworkInterfaceWrapper getNetworkInterfaceByName(String interfaceName) throws
                SocketException, NullPointerException {
            final NetworkInterface ni = NetworkInterface.getByName(interfaceName);
            return ni == null ? null : new NetworkInterfaceWrapper(ni);
        }

        /*** Check whether given network interface can support mdns */
        public boolean canScanOnInterface(NetworkInterfaceWrapper networkInterface) {
            return MulticastNetworkInterfaceProvider.canScanOnInterface(networkInterface);
        }

        /*** Create a MdnsInterfaceSocket */
        public MdnsInterfaceSocket createMdnsInterfaceSocket(NetworkInterface networkInterface,
                int port) throws IOException {
            return new MdnsInterfaceSocket(networkInterface, port);
        }
    }

    private ConnectivityManager getConnectivityManager() {
        if (mConnectivityManager == null) {
            mConnectivityManager = mContext.getSystemService(ConnectivityManager.class);
        }
        return mConnectivityManager;
    }

    /*** Start monitoring sockets by listening callbacks for sockets creation or removal */
    public void startMonitoringSockets() {
        if (mMonitoringSockets) {
            Log.d(TAG, "Already monitoring sockets.");
            return;
        }
        if (DBG) Log.d(TAG, "Start monitoring sockets.");
        final ConnectivityManager connectivityManager = getConnectivityManager();
        connectivityManager.registerNetworkCallback(
                new NetworkRequest.Builder()
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
                        .build(),
                mNetworkCallback);

        final TetheringManager tetheringManager = mContext.getSystemService(TetheringManager.class);
        tetheringManager.registerTetheringEventCallback(r -> r.run(), mTetheringEventCallback);

        mMonitoringSockets = true;
    }

    /*** Stop monitoring sockets and unregister callbacks */
    public void stopMonitoringSockets() {
        if (!mMonitoringSockets) {
            Log.d(TAG, "Monitoring sockets hasn't been started.");
            return;
        }
        if (DBG) Log.d(TAG, "Stop monitoring sockets.");
        final ConnectivityManager connectivityManager = getConnectivityManager();
        connectivityManager.unregisterNetworkCallback(mNetworkCallback);

        final TetheringManager tetheringManager = mContext.getSystemService(TetheringManager.class);
        tetheringManager.unregisterTetheringEventCallback(mTetheringEventCallback);

        mMonitoringSockets = false;
    }

    private void handleTetherInterfacesChanged(List<String> current, List<String> updated) {
        final CompareResult<String> interfaceDiff = new CompareResult<>(
                current, updated);
        for (String name : interfaceDiff.added) {
            final LinkProperties lp = new LinkProperties();
            lp.setInterfaceName(name);
            createSocket(new Network(INetd.LOCAL_NET_ID), lp);
        }
        for (String name : interfaceDiff.removed) {
            removeSocket(new Network(INetd.LOCAL_NET_ID), name);
        }
        current.clear();
        current.addAll(updated);
    }

    private void createSocket(Network network, LinkProperties lp) {
        final String interfaceName = lp.getInterfaceName();
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
                    networkInterface.getNetworkInterface(), MdnsConstants.MDNS_PORT);
            if (network.netId == INetd.LOCAL_NET_ID) {
                mTetherInterfaceSockets.put(interfaceName, socket);
            } else {
                mNetworkSockets.put(network, socket);
            }
            notifySocketCreated(network, socket, lp.getLinkAddresses());
        } catch (NullPointerException | IOException e) {
            Log.e(TAG, "Create a socket failed with interface=" + interfaceName);
        }
    }

    private void removeSocket(Network network, String interfaceName) {
        final MdnsInterfaceSocket socket = network.netId == INetd.LOCAL_NET_ID
                ? mTetherInterfaceSockets.get(interfaceName)
                : mNetworkSockets.get(network);
        if (socket == null) return;

        if (network.netId == INetd.LOCAL_NET_ID) {
            mTetherInterfaceSockets.remove(interfaceName);
        } else {
            mNetworkSockets.remove(network);
        }
        notifyInterfaceDestroyed(network, socket);
    }

    private void notifySocketCreated(Network network, MdnsInterfaceSocket socket,
            List<LinkAddress> addresses) {
        for (SocketCallback cb : mSocketCallbacks) {
            cb.onSocketCreated(network, socket, addresses);
        }
    }

    private void notifyInterfaceDestroyed(Network network, MdnsInterfaceSocket socket) {
        for (SocketCallback cb : mSocketCallbacks) {
            cb.onInterfaceDestroyed(network, socket);
        }
    }

    private void notifyAddressesChanged(Network network, LinkProperties lp) {
        for (SocketCallback cb : mSocketCallbacks) {
            cb.onAddressesChanged(network, lp.getLinkAddresses());
        }
    }

    /**
     * Request a socket for given network.
     *
     * @param network the required network for a socket. Null means create sockets on all possible
     *                interfaces.
     * @param cb the callback to listen the socket creation.
     */
    public void requestSocket(@Nullable Network network, @NonNull SocketCallback cb) {
        mSocketCallbacks.add(cb);
        // If no required network, notify all current sockets.
        if (network == null) {
            for (Network nw : mNetworkSockets.keySet()) {
                final MdnsInterfaceSocket socket = mNetworkSockets.get(nw);
                final ConnectivityManager cm = getConnectivityManager();
                final List<LinkAddress> addresses = cm.getLinkProperties(nw).getLinkAddresses();
                cb.onSocketCreated(nw, socket, addresses);
            }

            for (String ifaceName : mTetherInterfaceSockets.keySet()) {
                final MdnsInterfaceSocket socket = mTetherInterfaceSockets.get(ifaceName);
                cb.onSocketCreated(
                        new Network(INetd.LOCAL_NET_ID), socket, new ArrayList<>() /* addresses */);
            }
            return;
        }

        final MdnsInterfaceSocket socket = mNetworkSockets.get(network);
        if (socket == null) {
            // No available socket for required network. Maybe the required network is not for mdns.
            return;
        }

        // Notify the socket for required network.
        final ConnectivityManager cm = getConnectivityManager();
        final List<LinkAddress> addresses = cm.getLinkProperties(network).getLinkAddresses();
        cb.onSocketCreated(network, socket, addresses);
    }

    /*** Unrequest the socket */
    public void unrequestSocket(@NonNull SocketCallback cb) {
        mSocketCallbacks.remove(cb);
    }

    /*** Callbacks for listening socket changes */
    public interface SocketCallback {
        /*** Notify the socket is created */
        default void onSocketCreated(@NonNull Network network, MdnsInterfaceSocket socket,
                List<LinkAddress> addresses) {}
        /*** Notify the interface is destroyed */
        default void onInterfaceDestroyed(@NonNull Network network, MdnsInterfaceSocket socket) {}
        /*** Notify the addresses is changed on the network */
        default void onAddressesChanged(@NonNull Network network, List<LinkAddress> addresses) {}
    }
}
