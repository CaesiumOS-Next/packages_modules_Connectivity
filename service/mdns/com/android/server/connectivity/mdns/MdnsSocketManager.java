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
import android.net.LinkProperties;
import android.net.Network;
import android.net.wifi.WifiManager.MulticastLock;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.util.List;
import java.util.Map;
import java.util.Set;

/*** The {@link MdnsSocketManager} manages the multiple sockets for mDns discovery / resolution */
public class MdnsSocketManager {
    private static final String TAG = MdnsSocketManager.class.getSimpleName();
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private final Context mContext;
    private final MulticastNetworkInterfaceProvider mInterfaceProvider;
    private final MulticastLock mMulticastLock;
    private final Map<String, MdnsSocketClient> mInterfaceSocketClients = new ArrayMap<>();
    private MdnsSocketClient.Callback mCallback = null;

    public MdnsSocketManager(Context context, MulticastNetworkInterfaceProvider provider,
            MulticastLock multicastLock) {
        mContext = context;
        mInterfaceProvider = provider;
        mMulticastLock = multicastLock;
    }

    private Network findNetworkFromNetworkInterface(NetworkInterfaceWrapper nwInterface) {
        final ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
        if (cm == null) {
            Log.wtf(TAG, "No ConnectivityManager!");
            return null;
        }
        final Network[] networks = cm.getAllNetworks();
        if (networks == null || networks.length == 0) {
            Log.e(TAG, "No available networks");
            return null;
        }

        final String ifaceName = nwInterface.getNetworkInterface().getName();
        for (Network network : networks) {
            final LinkProperties lp = cm.getLinkProperties(network);
            if (lp != null) {
                if (ifaceName.equals(lp.getInterfaceName())) {
                    return network;
                }
            }
        }
        if (DBG) Log.d(TAG, "Interface does not match any of network.");
        return null;
    }

    /*** Create a socket on each interface that are available for mDns  */
    private void createSocketClients() {
        final List<NetworkInterfaceWrapper> nwIfaceWraps =
                mInterfaceProvider.updateAndGetMulticastNetworkInterfaces();
        for (NetworkInterfaceWrapper nwIfceWrap : nwIfaceWraps) {
            final String name = nwIfceWrap.getNetworkInterface().getName();
            MdnsSocketClient client = mInterfaceSocketClients.get(name);
            if (client == null) {
                final Network network = findNetworkFromNetworkInterface(nwIfceWrap);
                client = new MdnsSocketClient(mContext, mMulticastLock, nwIfceWrap, network);
                if (mCallback != null) {
                    client.setCallback(mCallback);
                }
                if (DBG) Log.d(TAG, "Create socket on interface:" + name);
                mInterfaceSocketClients.put(name, client);
            }
        }
    }

    /*** Set callback for receiving mDns response */
    public void setCallback(MdnsSocketClient.Callback callback) {
        mCallback = callback;
        for (MdnsSocketClient client : mInterfaceSocketClients.values()) {
            client.setCallback(callback);
        }
    }

    /*** Start mDns discovery on each socket */
    public void startDiscovery() throws IOException {
        boolean discoveryStarted = false;
        // Check all available interfaces and create socket if necessary.
        createSocketClients();

        for (MdnsSocketClient client : mInterfaceSocketClients.values()) {
            try {
                client.startDiscovery();
                discoveryStarted = true;
            } catch (IOException e) {
                Log.e(TAG, "Can not start discovery on client=" + client);
            }
        }

        if (!discoveryStarted) throw new IOException("Can not start discovery on all clients");
    }

    private void clearSocketClientsIfNeed() {
        final List<NetworkInterfaceWrapper> nwIfaceWraps =
                mInterfaceProvider.updateAndGetMulticastNetworkInterfaces();
        final Set<String> clientNames = new ArraySet<>(mInterfaceSocketClients.keySet());
        for (String clientName : clientNames) {
            boolean interfaceExisted = false;
            for (NetworkInterfaceWrapper nwIfaceWrap : nwIfaceWraps) {
                final String ifaceName = nwIfaceWrap.getNetworkInterface().getName();
                if (ifaceName.equals(clientName)) {
                    interfaceExisted = true;
                    break;
                }
            }
            if (!interfaceExisted) {
                if (DBG) Log.d(TAG, "interface:" + clientName + " is not existed. Removed!");
                mInterfaceSocketClients.remove(clientName);
            }
        }
    }

    /*** Stop mDns discovery on each socket */
    public void stopDiscovery() {
        for (MdnsSocketClient client : mInterfaceSocketClients.values()) {
            client.stopDiscovery();
        }
        clearSocketClientsIfNeed();
    }

    /*** Send multicast packet to each socket */
    public void sendMulticastPacket(@NonNull DatagramPacket packet) {
        for (MdnsSocketClient client : mInterfaceSocketClients.values()) {
            client.sendMulticastPacket(packet);
        }
    }

    /*** Send unicast packet to each socket */
    public void sendUnicastPacket(DatagramPacket packet) {
        for (MdnsSocketClient client : mInterfaceSocketClients.values()) {
            client.sendUnicastPacket(packet);
        }
    }
}
