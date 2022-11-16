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
import android.net.LinkAddress;
import android.net.Network;
import android.net.wifi.WifiManager.MulticastLock;
import android.util.ArrayMap;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * The {@link MdnsSocketManager} manages the multiple socket for mDns
 *
 *  * <p>This class is not thread safe.
 */
public class MdnsSocketManager implements MdnsSocketController {
    private static final String TAG = MdnsSocketManager.class.getSimpleName();
    private static final boolean DBG = MdnsDiscoveryManager.DBG;
    private static final int EVENT_SEND_PACKET = 1;

    @NonNull private final MulticastLock mMulticastLock;
    @NonNull private final MdnsSocketProvider mSocketProvider;
    private final MdnsResponseDecoder mResponseDecoder;

    private final Map<MdnsServiceBrowserListener, InterfaceSocketCallback> mRequestedNetworks =
            new ArrayMap<>();
    private final ArrayMap<MdnsInterfaceSocket, Network> mActiveNetworkSockets = new ArrayMap<>();
    private MdnsSocketController.Callback mCallback = null;
    private int mReceivedPacketNumber = 0;

    public MdnsSocketManager(@NonNull MdnsSocketProvider provider,
            @NonNull MulticastLock multicastLock) {
        mSocketProvider = provider;
        mMulticastLock = multicastLock;
        mResponseDecoder = new MdnsResponseDecoder(
                new MdnsResponseDecoder.Clock(), null /* serviceType */);
    }

    private class InterfaceSocketCallback implements MdnsSocketProvider.SocketCallback {
        @Override
        public void onSocketCreated(@NonNull Network network,
                @NonNull MdnsInterfaceSocket socket, @NonNull List<LinkAddress> addresses) {
            socket.setPacketHandler(new ReadPacketHandler(network, socket.getInterfaceIndex()));
            // Acquire a lock if there is an active socket.
            if (mActiveNetworkSockets.isEmpty()) {
                mMulticastLock.acquire();
            }
            mActiveNetworkSockets.put(socket, network);
        }

        @Override
        public void onInterfaceDestroyed(@NonNull Network network,
                @NonNull MdnsInterfaceSocket socket) {
            mActiveNetworkSockets.remove(socket);
            // Release the lock if no active sockets.
            if (mActiveNetworkSockets.isEmpty()) {
                mMulticastLock.release();
            }
        }
    }

    private class ReadPacketHandler implements MulticastPacketReader.PacketHandler {
        private Network mNetwork;
        private int mInterfaceIndex;

        ReadPacketHandler(@NonNull Network network, int interfaceIndex) {
            mNetwork = network;
            mInterfaceIndex = interfaceIndex;
        }

        @Override
        public void handlePacket(byte[] recvbuf, int length, InetSocketAddress src) {
            processResponsePacket(recvbuf, length, mInterfaceIndex, mNetwork);
        }
    }

    /*** Set callback for receiving mDns response */
    @Override
    public void setCallback(@Nullable MdnsSocketController.Callback callback) {
        mCallback = callback;
    }

    /***
     * Start mDns discovery on given network.
     *
     * @param listener the listener for discovery.
     * @param network the target network for discovery. Null means discovery on all possible
     *                interfaces.
     */
    @Override
    public void startDiscovery(@NonNull MdnsServiceBrowserListener listener,
            @Nullable Network network) {
        if (DBG) Log.d(TAG, "startDiscovery: network=" + network);
        final InterfaceSocketCallback callback = new InterfaceSocketCallback();
        mRequestedNetworks.put(listener, callback);
        mSocketProvider.requestSocket(network, callback);
    }

    /*** Stop mDns discovery */
    @Override
    public void stopDiscovery(@NonNull MdnsServiceBrowserListener listener) {
        final InterfaceSocketCallback callback = mRequestedNetworks.remove(listener);
        if (callback == null) {
            Log.e(TAG, "Can not stop discovery with unknown listener=" + listener);
            return;
        }
        mSocketProvider.unrequestSocket(callback);
    }

    private void sendMdnsPacket(@NonNull DatagramPacket packet) {
        final boolean isIpv6 = ((InetSocketAddress) packet.getSocketAddress()).getAddress()
                instanceof Inet6Address;
        final boolean isIpv4 = ((InetSocketAddress) packet.getSocketAddress()).getAddress()
                instanceof Inet4Address;
        for (int i = 0; i < mActiveNetworkSockets.size(); i++) {
            final MdnsInterfaceSocket socket = mActiveNetworkSockets.keyAt(i);
            if ((isIpv6 && socket.hasJoinedIpv6()) || (isIpv4 && socket.hasJoinedIpv4())) {
                try {
                    socket.send(packet);
                } catch (IOException e) {
                    Log.e(TAG, "Failed to send a mDNS packet.", e);
                }
            }
        }
    }

    private void processResponsePacket(byte[] recvbuf, int length, int interfaceIndex,
            @NonNull Network network) {
        int packetNumber = ++mReceivedPacketNumber;

        List<MdnsResponse> responses = new LinkedList<>();
        int errorCode = mResponseDecoder.decode(
                recvbuf, length, responses, interfaceIndex, network);
        if (errorCode == MdnsResponseDecoder.SUCCESS) {
            for (MdnsResponse response : responses) {
                if (mCallback != null) {
                    mCallback.onResponseReceived(response);
                }
            }
        } else if (errorCode != MdnsResponseErrorCode.ERROR_NOT_RESPONSE_MESSAGE) {
            if (mCallback != null) {
                mCallback.onFailedToParseMdnsResponse(packetNumber, errorCode);
            }
        }
    }

    /*** Send multicast packet to active clients with IPv4 address */
    @Override
    public void sendMulticastPacket(@NonNull DatagramPacket packet) {
        sendMdnsPacket(packet);
    }

    /** Sends a mDNS request packet that asks for unicast response. */
    @Override
    public void sendUnicastPacket(@NonNull DatagramPacket packet) {
        // TODO: Separate multicast and unicast packet.
        sendMdnsPacket(packet);
    }
}
