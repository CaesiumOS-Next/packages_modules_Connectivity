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
import android.net.LinkAddress;
import android.net.Network;
import android.net.wifi.WifiManager.MulticastLock;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.net.DatagramPacket;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@link MdnsSocketManager} manages the multiple socket clients for mDns
 */
public class MdnsSocketManager implements MdnsSocketProvider.SocketCallback {
    private static final String TAG = MdnsSocketManager.class.getSimpleName();
    private static final boolean DBG = MdnsDiscoveryManager.DBG;
    @NonNull private final Context mContext;
    @NonNull private final MulticastLock mMulticastLock;
    @NonNull private final MdnsSocketProvider mSocketProvider;
    @NonNull private final Dependencies mDependencies;
    @GuardedBy("this")
    private final Map<MdnsServiceBrowserListener, Network> mRequestedNetworks = new ArrayMap<>();
    @GuardedBy("this")
    private final Map<MdnsInterfaceSocket, MdnsSocketClient> mSocketToClientMap = new ArrayMap<>();
    @GuardedBy("this")
    private final Set<MdnsSocketClient> mActiveClients = new ArraySet<>();
    @GuardedBy("this")
    private MdnsSocketClient.Callback mCallback = null;

    public MdnsSocketManager(@NonNull Context context, @NonNull MdnsSocketProvider provider,
            @NonNull MulticastLock multicastLock) {
        this(context, provider, multicastLock, new Dependencies());
    }

    MdnsSocketManager(@NonNull Context context, @NonNull MdnsSocketProvider provider,
            @NonNull MulticastLock multicastLock, @NonNull Dependencies deps) {
        mContext = context;
        mSocketProvider = provider;
        mMulticastLock = multicastLock;
        mDependencies = deps;
    }

    /**
     * Dependencies of MdnsSocketManager, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        /*** Create a MdnsSocketClient */
        public MdnsSocketClient createMdnsSocketClient(Context context, MulticastLock multicastLock,
                MdnsSocketBase socket, Network network) {
            return new MdnsSocketClient(context, multicastLock, socket, network);
        }
    }

    private static boolean isClientNetworkMatched(Network requestNetwork, Network clientNetwork) {
        return requestNetwork == null || requestNetwork.equals(clientNetwork);
    }

    private synchronized boolean matchRequestedNetwork(Network network) {
        for (Network requestedNetwork : mRequestedNetworks.values()) {
            if (isClientNetworkMatched(requestedNetwork, network)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized void onSocketCreated(@NonNull Network network,
            @NonNull MdnsInterfaceSocket socket, @NonNull List<LinkAddress> addresses) {
        if (DBG) Log.d(TAG, "onSocketCreated: network=" + network + ", addresses=" + addresses);
        MdnsSocketClient client = mSocketToClientMap.get(socket);
        if (client == null) {
            client = mDependencies.createMdnsSocketClient(
                    mContext, mMulticastLock, socket, network);
            client.setCallback(mCallback);
            mSocketToClientMap.put(socket, client);
        }

        if (matchRequestedNetwork(network)) {
            try {
                if (DBG) Log.d(TAG, "Start discovery on the client.");
                client.startDiscovery();
                mActiveClients.add(client);
            } catch (IOException e) {
                Log.e(TAG, "Can not start discovery on client. e=" + e);
            }
        }
    }

    @Override
    public synchronized void onInterfaceDestroyed(@NonNull Network network,
            @NonNull MdnsInterfaceSocket socket) {
        final MdnsSocketClient client = mSocketToClientMap.get(socket);
        if (client == null) return;

        if (DBG) Log.d(TAG, "Stop discovery by interface destroyed on network:" + network);
        client.stopDiscovery();
        mActiveClients.remove(client);
        mSocketToClientMap.remove(socket);
    }

    /*** Set callback for receiving mDns response */
    public synchronized void setCallback(@Nullable MdnsSocketClient.Callback callback) {
        mCallback = callback;
        for (MdnsSocketClient client : mActiveClients) {
            client.setCallback(callback);
        }
    }

    /***
     * Start mDns discovery on given network.
     *
     * @param listener the listener for discovery.
     * @param network the target network for discovery. Null means discovery on all possible
     *                interfaces.
     */
    public synchronized void startDiscovery(@NonNull MdnsServiceBrowserListener listener,
            @Nullable Network network) {
        if (DBG) Log.d(TAG, "startDiscovery: network=" + network);
        mSocketProvider.requestSocket(network, this);
        mRequestedNetworks.put(listener, network);
    }

    /*** Stop mDns discovery */
    public synchronized void stopDiscovery(@NonNull MdnsServiceBrowserListener listener) {
        if (!mRequestedNetworks.containsKey(listener)) {
            Log.e(TAG, "Can not stop discovery with unknown listener=" + listener);
            return;
        }

        mRequestedNetworks.remove(listener);
        final Set<MdnsSocketClient> clients = new ArraySet<>(mActiveClients);
        for (MdnsSocketClient client : clients) {
            final boolean stopDiscovering = !matchRequestedNetwork(client.getNetwork());
            if (stopDiscovering) {
                if (DBG) {
                    Log.d(TAG, "Stop discovery on the client with network:"
                            + client.getNetwork());
                }
                client.stopDiscovery();
                mActiveClients.remove(client);
            }
        }
    }

    /*** Send multicast or unicast packet to active socket clients with IPv4 address */
    public synchronized void sendPacketToIpv4(@NonNull DatagramPacket packet, boolean unicast) {
        for (MdnsSocketClient client : mActiveClients) {
            if (!client.hasJoinedIpv4()) continue;

            if (unicast) {
                client.sendUnicastPacket(packet);
            } else {
                client.sendMulticastPacket(packet);
            }
        }
    }

    /*** Send multicast or unicast packet to active socket clients with IPv6 address */
    public synchronized void sendPacketToIpv6(@NonNull DatagramPacket packet, boolean unicast) {
        for (MdnsSocketClient client : mActiveClients) {
            if (!client.hasJoinedIpv6()) continue;

            if (unicast) {
                client.sendUnicastPacket(packet);
            } else {
                client.sendMulticastPacket(packet);
            }
        }
    }

    /*** Get the socket client for given socket. */
    @VisibleForTesting
    synchronized MdnsSocketClient getSocketClient(@NonNull MdnsInterfaceSocket socket) {
        return mSocketToClientMap.get(socket);
    }
}
