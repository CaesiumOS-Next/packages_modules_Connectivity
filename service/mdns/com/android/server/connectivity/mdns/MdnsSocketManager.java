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
import android.os.Handler;
import android.os.HandlerThread;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/*** The {@link MdnsSocketManager} manages the multiple sockets for mDns discovery or resolution */
public class MdnsSocketManager {
    private static final String TAG = MdnsSocketManager.class.getSimpleName();
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private final Context mContext;
    private final MulticastLock mMulticastLock;
    private final Map<Network, MdnsSocketClient> mNetworkSocketClients = new ArrayMap<>();
    private final Map<MdnsServiceBrowserListener, Network> mRequestedNetworks = new ArrayMap<>();
    private final Set<MdnsSocketClient> mActiveClients = new ArraySet<>();
    private MdnsSocketClient.Callback mCallback = null;
    private final Handler mHandler;

    public MdnsSocketManager(Context context, MulticastLock multicastLock) {
        mContext = context;
        mMulticastLock = multicastLock;
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper());
    }

    /*** Listen callbacks for sockets creation or removal */
    public void startMonitorMulticastNetworks() {
        final ConnectivityManager connectivityManager =
                mContext.getSystemService(ConnectivityManager.class);
        final NetworkCallback callback = new NetworkCallback() {
            @Override
            public void onLost(Network network) {
                removeSocketClient(network);
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
                if (interfaces.isEmpty()) {
                    removeSocketClient(new Network(INetd.LOCAL_NET_ID));
                } else {
                    createSocketClient(new Network(INetd.LOCAL_NET_ID), interfaces.get(0));
                }
            }
        };
        tetheringManager.registerTetheringEventCallback(mHandler::post, callback1);
    }

    private void createSocketClient(Network network, String interfaceName) {
        MdnsSocketClient client = mNetworkSocketClients.get(network);
        if (client == null) {
            try {
                final NetworkInterface networkInterface = NetworkInterface.getByName(interfaceName);
                if (networkInterface != null
                        && MulticastNetworkInterfaceProvider.canScanOnInterface(networkInterface)) {
                    if (DBG) {
                        Log.d(TAG, "Create socket on network:" + network
                                + " with interfaceName:" + interfaceName);
                    }
                    client = new MdnsSocketClient(mContext, mMulticastLock,
                            new NetworkInterfaceWrapper(networkInterface), network);
                    client.setCallback(mCallback);
                    mNetworkSocketClients.put(network, client);
                }
            } catch (NullPointerException | SocketException e) {
                Log.e(TAG, "Can not get NetworkInterface from given name=" + interfaceName);
                return;
            }
        }
    }

    private void removeSocketClient(Network network) {
        final MdnsSocketClient client = mNetworkSocketClients.get(network);
        if (client != null) {
            client.stopDiscovery();
        }
        mNetworkSocketClients.remove(network);
    }

    /*** Set callback for receiving mDns response */
    public void setCallback(MdnsSocketClient.Callback callback) {
        mCallback = callback;
        for (MdnsSocketClient client : mNetworkSocketClients.values()) {
            client.setCallback(callback);
        }
    }

    private boolean isClientNetworkMatched(Network requestNetwork, Network clientNetwork) {
        return requestNetwork == null || requestNetwork == clientNetwork;
    }

    /*** Start mDns discovery on given search options */
    public void startDiscovery(MdnsServiceBrowserListener listener, Network network)
            throws IOException {
        boolean discoveryStarted = false;

        for (MdnsSocketClient client : mNetworkSocketClients.values()) {
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
            boolean keepDiscovering = false;
            for (Network remainingNetwork : mRequestedNetworks.values()) {
                if (isClientNetworkMatched(remainingNetwork, client.getNetwork())) {
                    keepDiscovering = true;
                    break;
                }
            }
            if (!keepDiscovering) {
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
}
