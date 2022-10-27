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
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.INetd;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.TetheringManager;
import android.net.TetheringManager.TetheringEventCallback;
import android.net.wifi.WifiManager.MulticastLock;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.LinkPropertiesUtils.CompareResult;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/*** The {@link MdnsSocketManager} manages the multiple sockets for mDns discovery / resolution */
public class MdnsSocketManager {
    private static final String TAG = MdnsSocketManager.class.getSimpleName();
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private final Context mContext;
    private final MulticastLock mMulticastLock;
    private final Dependencies mDependencies;
    private final Map<Network, MdnsSocketClient> mNetworkSocketClients = new ArrayMap<>();
    private final Map<String, MdnsSocketClient> mLocalOnlyInterfaceSocketClients = new ArrayMap<>();
    private final Map<MdnsServiceBrowserListener, Network> mRequestedNetworks = new ArrayMap<>();
    private final Set<MdnsSocketClient> mActiveClients = new ArraySet<>();
    private MdnsSocketClient.Callback mCallback = null;
    private boolean mMonitoringMulticastNetworks = false;

    public MdnsSocketManager(Context context, MulticastLock multicastLock) {
        this(context, multicastLock, new Dependencies());
    }

    MdnsSocketManager(Context context, MulticastLock multicastLock, Dependencies deps) {
        mContext = context;
        mMulticastLock = multicastLock;
        mDependencies = deps;
    }

    /**
     * Dependencies of MdnsSocketManager, for injection in tests.
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

        /*** Create a MdnsSocketClient */
        public MdnsSocketClient createMdnsSocketClient(Context context, MulticastLock multicastLock,
                NetworkInterfaceWrapper networkInterface, Network network) {
            return new MdnsSocketClient(context, multicastLock, networkInterface, network);
        }
    }

    /*** Listen callbacks for sockets creation or removal */
    public void startMonitoringMulticastNetworks() {
        if (mMonitoringMulticastNetworks) {
            Log.d("TAG", "Already monitoring multicast networks");
            return;
        }

        final ConnectivityManager connectivityManager =
                mContext.getSystemService(ConnectivityManager.class);
        final NetworkCallback callback = new NetworkCallback() {
            @Override
            public void onLost(Network network) {
                removeSocketClient(network, null);
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                createSocketClient(network, lp.getInterfaceName());
            }

        };
        connectivityManager.registerNetworkCallback(new NetworkRequest.Builder().build(), callback);

        final TetheringManager tetheringManager = mContext.getSystemService(TetheringManager.class);
        final TetheringEventCallback callback1 = new TetheringEventCallback() {
            @Override
            public void onLocalOnlyInterfacesChanged(@NonNull List<String> interfaces) {
                handleLocalOnlyInterfacesChanged(interfaces);
            }
        };
        tetheringManager.registerTetheringEventCallback(r -> r.run(), callback1);

        mMonitoringMulticastNetworks = true;
    }

    private void handleLocalOnlyInterfacesChanged(List<String> interfaces) {
        final CompareResult<String> interfaceDiff = new CompareResult<>(
                mLocalOnlyInterfaceSocketClients.keySet(), interfaces);
        for (String name : interfaceDiff.added) {
            createSocketClient(new Network(INetd.LOCAL_NET_ID), name);
        }
        for (String name : interfaceDiff.removed) {
            removeSocketClient(new Network(INetd.LOCAL_NET_ID), name);
        }
    }

    private boolean isClientNetworkMatched(Network requestNetwork, Network clientNetwork) {
        return requestNetwork == null || requestNetwork.equals(clientNetwork);
    }

    private boolean matchRequestedNetwork(Network network) {
        for (Network requestedNetwork : mRequestedNetworks.values()) {
            if (isClientNetworkMatched(requestedNetwork, network)) {
                return true;
            }
        }
        return false;
    }

    private void createSocketClient(Network network, String interfaceName) {
        MdnsSocketClient client = network.netId == INetd.LOCAL_NET_ID
                ? mLocalOnlyInterfaceSocketClients.get(interfaceName)
                : mNetworkSocketClients.get(network);
        if (client == null) {
            try {
                final NetworkInterfaceWrapper networkInterface =
                        mDependencies.getNetworkInterfaceByName(interfaceName);
                if (networkInterface != null
                        && mDependencies.canScanOnInterface(networkInterface)) {
                    if (DBG) {
                        Log.d(TAG, "Create socket on network:" + network
                                + " with interfaceName:" + interfaceName);
                    }
                    client = mDependencies.createMdnsSocketClient(
                            mContext, mMulticastLock, networkInterface, network);
                    client.setCallback(mCallback);
                    if (network.netId == INetd.LOCAL_NET_ID) {
                        mLocalOnlyInterfaceSocketClients.put(interfaceName, client);
                    } else {
                        mNetworkSocketClients.put(network, client);
                    }
                    if (matchRequestedNetwork(network)) {
                        client.startDiscovery();
                        mActiveClients.add(client);
                    }
                }
            } catch (NullPointerException | IOException e) {
                Log.e(TAG, "Create socket client failed with interface=" + interfaceName);
                return;
            }
        }
    }

    private void removeSocketClient(Network network, String interfaceName) {
        final MdnsSocketClient client = network.netId == INetd.LOCAL_NET_ID
                ? mLocalOnlyInterfaceSocketClients.get(interfaceName)
                : mNetworkSocketClients.get(network);
        if (client != null) {
            client.stopDiscovery();
        }
        if (network.netId == INetd.LOCAL_NET_ID) {
            mLocalOnlyInterfaceSocketClients.remove(interfaceName);
        } else {
            mNetworkSocketClients.remove(network);
        }
    }

    /*** Set callback for receiving mDns response */
    public void setCallback(MdnsSocketClient.Callback callback) {
        mCallback = callback;
        for (MdnsSocketClient client : mNetworkSocketClients.values()) {
            client.setCallback(callback);
        }
    }

    /*** Start mDns discovery on given search options */
    public void startDiscovery(MdnsServiceBrowserListener listener, Network network)
            throws IOException {
        boolean discoveryStarted = false;

        final List<MdnsSocketClient> allSocketClients =
                new ArrayList<>(mNetworkSocketClients.values());
        allSocketClients.addAll(mLocalOnlyInterfaceSocketClients.values());

        for (MdnsSocketClient client : allSocketClients) {
            if (isClientNetworkMatched(network, client.getNetwork())) {
                try {
                    client.startDiscovery();
                    discoveryStarted = true;
                    mActiveClients.add(client);
                } catch (IOException e) {
                    Log.e(TAG, "Can not start discovery on client=" + client);
                }
            }
        }

        if (!discoveryStarted) throw new IOException("Can not start discovery on any of client.");
        mRequestedNetworks.put(listener, network);
    }

    /*** Stop mDns discovery on given search options */
    public void stopDiscovery(MdnsServiceBrowserListener listener) {
        if (!mRequestedNetworks.containsKey(listener)) {
            Log.e(TAG, "Can not stop discovery with unknown listener=" + listener);
            return;
        }

        mRequestedNetworks.remove(listener);
        final Set<MdnsSocketClient> clients = new ArraySet<>(mActiveClients);
        for (MdnsSocketClient client : clients) {
            final boolean stopDiscovering = !matchRequestedNetwork(client.getNetwork());
            if (stopDiscovering) {
                client.stopDiscovery();
                mActiveClients.remove(client);
            }
        }
    }

    /*** Send multicast packet to active sockets */
    public void sendMulticastPacket(@NonNull DatagramPacket packet) {
        for (MdnsSocketClient client : mActiveClients) {
            client.sendMulticastPacket(packet);
        }
    }

    /*** Send unicast packet to active sockets */
    public void sendUnicastPacket(DatagramPacket packet) {
        for (MdnsSocketClient client : mActiveClients) {
            client.sendUnicastPacket(packet);
        }
    }

    /*** Get the socket client for given network. For test only. */
    MdnsSocketClient getSocketClientForNetwork(Network network) {
        return mNetworkSocketClients.get(network);
    }

    /*** Get the socket client for given local only interface. For test only. */
    MdnsSocketClient getSocketClientForLocalOnlyInterface(String localOnlyInterfaceName) {
        return mLocalOnlyInterfaceSocketClients.get(localOnlyInterfaceName);
    }
}
