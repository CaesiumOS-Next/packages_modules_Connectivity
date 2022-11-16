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
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

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
 */
public class MdnsSocketManager implements MdnsSocketController {
    private static final String TAG = MdnsSocketManager.class.getSimpleName();
    private static final boolean DBG = MdnsDiscoveryManager.DBG;
    private static final int EVENT_SEND_PACKET = 1;
    private static final int EVENT_RECEIVE_PACKET = 2;
    private static final int RECEIVER_BUFFER_SIZE = 2048;

    @NonNull private final Handler mHandler;
    @NonNull private final MulticastLock mMulticastLock;
    @NonNull private final MdnsSocketProvider mSocketProvider;
    private final MdnsResponseDecoder mResponseDecoder;

    @GuardedBy("this")
    private final Map<MdnsServiceBrowserListener, InterfaceSocketCallback> mRequestedNetworks =
            new ArrayMap<>();
    @GuardedBy("this")
    private final Map<MdnsInterfaceSocket, Network> mActiveNetworkSockets = new ArrayMap<>();
    @GuardedBy("this")
    private MdnsSocketController.Callback mCallback = null;
    private final byte[] mReceiverBuffer = new byte[RECEIVER_BUFFER_SIZE];
    private int mReceivedPacketNumber = 0;

    public MdnsSocketManager(@NonNull Looper looper, @NonNull MdnsSocketProvider provider,
            @NonNull MulticastLock multicastLock) {
        mHandler = new SocketManagerHandler(looper);
        mSocketProvider = provider;
        mMulticastLock = multicastLock;
        mResponseDecoder = new MdnsResponseDecoder(new MdnsResponseDecoder.Clock(), null);
    }

    private class InterfaceSocketCallback implements MdnsSocketProvider.SocketCallback {
        @Override
        public synchronized void onSocketCreated(@NonNull Network network,
                @NonNull MdnsInterfaceSocket socket, @NonNull List<LinkAddress> addresses) {
            // Acquire a lock if there is an active socket.
            if (mActiveNetworkSockets.isEmpty()) {
                mMulticastLock.acquire();
            }
            mActiveNetworkSockets.put(socket, network);
        }

        @Override
        public synchronized void onInterfaceDestroyed(@NonNull Network network,
                @NonNull MdnsInterfaceSocket socket) {
            mActiveNetworkSockets.remove(socket);
            // Release the lock if no active sockets.
            if (mActiveNetworkSockets.isEmpty()) {
                mMulticastLock.release();
            }
        }
    }

    private class SocketManagerHandler extends Handler {
        SocketManagerHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            switch (msg.what) {
                case EVENT_SEND_PACKET: {
                    final DatagramPacket packet = (DatagramPacket) msg.obj;
                    handleSendPacket(packet);
                    break;
                }
                case EVENT_RECEIVE_PACKET: {
                    handleReceivePacket();
                    break;
                }
            }
        }
    }

    /*** Set callback for receiving mDns response */
    @Override
    public synchronized void setCallback(@Nullable MdnsSocketController.Callback callback) {
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
    public synchronized void startDiscovery(@NonNull MdnsServiceBrowserListener listener,
            @Nullable Network network) {
        if (DBG) Log.d(TAG, "startDiscovery: network=" + network);
        final InterfaceSocketCallback callback = new InterfaceSocketCallback();
        mRequestedNetworks.put(listener, callback);
        mSocketProvider.requestSocket(network, callback);
    }

    /*** Stop mDns discovery */
    @Override
    public synchronized void stopDiscovery(@NonNull MdnsServiceBrowserListener listener) {
        if (!mRequestedNetworks.containsKey(listener)) {
            Log.e(TAG, "Can not stop discovery with unknown listener=" + listener);
            return;
        }

        final InterfaceSocketCallback callback = mRequestedNetworks.remove(listener);
        mSocketProvider.unrequestSocket(callback);
    }

    private void sendMdnsPacket(DatagramPacket packet, MdnsInterfaceSocket socket) {
        try {
            socket.send(packet);
        } catch (IOException e) {
            Log.e(TAG, "Failed to send a mDNS packet.", e);
        }
    }

    private void handleSendPacket(@NonNull DatagramPacket packet) {
        final boolean isIpv6 = ((InetSocketAddress) packet.getSocketAddress()).getAddress()
                instanceof Inet6Address;
        final boolean isIpv4 = ((InetSocketAddress) packet.getSocketAddress()).getAddress()
                instanceof Inet4Address;
        for (MdnsInterfaceSocket socket : mActiveNetworkSockets.keySet()) {
            if ((isIpv6 && socket.hasJoinedIpv6()) || (isIpv4 && socket.hasJoinedIpv4())) {
                sendMdnsPacket(packet, socket);
            }
        }
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_RECEIVE_PACKET));
    }

    private void handleReceivePacket() {
        final DatagramPacket packet = new DatagramPacket(mReceiverBuffer, mReceiverBuffer.length);
        for (Map.Entry<MdnsInterfaceSocket, Network> entry : mActiveNetworkSockets.entrySet()) {
            try {
                final MdnsInterfaceSocket socket = entry.getKey();
                socket.receive(packet);
                processResponsePacket(
                        packet,
                        socket.getInterfaceIndex(),
                        entry.getValue());
            } catch (IOException e) {
                Log.e(TAG, "Failed to receive mDNS packets.", e);
            }
        }
    }

    private void processResponsePacket(@NonNull DatagramPacket packet, int interfaceIndex,
            @NonNull Network network) {
        int packetNumber = ++mReceivedPacketNumber;

        List<MdnsResponse> responses = new LinkedList<>();
        int errorCode = mResponseDecoder.decode(packet, responses, interfaceIndex, network);
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
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_SEND_PACKET, packet));
    }

    /** Sends a mDNS request packet that asks for unicast response. */
    @Override
    public void sendUnicastPacket(@NonNull DatagramPacket packet) {
        // TODO: Separate multicast and unicast packet.
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_SEND_PACKET, packet));
    }
}
