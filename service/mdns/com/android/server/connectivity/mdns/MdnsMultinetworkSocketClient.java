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

import static com.android.server.connectivity.mdns.MdnsSocketProvider.ensureRunningOnHandlerThread;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.LinkAddress;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
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
 * The {@link MdnsMultinetworkSocketClient} manages the multinetwork socket for mDns
 *
 *  * <p>This class is not thread safe.
 */
public class MdnsMultinetworkSocketClient implements MdnsSocketClientBase {
    private static final String TAG = MdnsMultinetworkSocketClient.class.getSimpleName();
    private static final boolean DBG = MdnsDiscoveryManager.DBG;

    @NonNull private final Handler mHandler;
    @NonNull private final MdnsSocketProvider mSocketProvider;
    @NonNull private final MdnsResponseDecoder mResponseDecoder;

    private final Map<MdnsServiceBrowserListener, InterfaceSocketCallback> mRequestedNetworks =
            new ArrayMap<>();
    private final ArrayMap<MdnsInterfaceSocket, Network> mActiveNetworkSockets = new ArrayMap<>();
    private final ArrayMap<MdnsInterfaceSocket, ReadPacketHandler> mSocketPacketHandlers =
            new ArrayMap<>();
    private MdnsSocketClientBase.Callback mCallback = null;
    private int mReceivedPacketNumber = 0;

    public MdnsMultinetworkSocketClient(@NonNull Looper looper,
            @NonNull MdnsSocketProvider provider) {
        mHandler = new Handler(looper);
        mSocketProvider = provider;
        mResponseDecoder = new MdnsResponseDecoder(
                new MdnsResponseDecoder.Clock(), null /* serviceType */);
    }

    private class InterfaceSocketCallback implements MdnsSocketProvider.SocketCallback {
        @Override
        public void onSocketCreated(@NonNull Network network,
                @NonNull MdnsInterfaceSocket socket, @NonNull List<LinkAddress> addresses) {
            ReadPacketHandler handler = mSocketPacketHandlers.get(socket);
            if (handler == null) {
                handler = new ReadPacketHandler(network, socket.getInterfaceIndex());
                mSocketPacketHandlers.put(socket, handler);
            }
            socket.addPacketHandler(handler);
            mActiveNetworkSockets.put(socket, network);
        }

        @Override
        public void onInterfaceDestroyed(@NonNull Network network,
                @NonNull MdnsInterfaceSocket socket) {
            mSocketPacketHandlers.remove(socket);
            mActiveNetworkSockets.remove(socket);
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
    public void setCallback(@Nullable MdnsSocketClientBase.Callback callback) {
        ensureRunningOnHandlerThread(mHandler);
        mCallback = callback;
    }

    /***
     * Notify that the given network is requested for mdns discovery / resolution
     *
     * @param listener the listener for discovery.
     * @param network the target network for discovery. Null means discovery on all possible
     *                interfaces.
     */
    @Override
    public void notifyNetworkRequested(@NonNull MdnsServiceBrowserListener listener,
            @Nullable Network network) {
        ensureRunningOnHandlerThread(mHandler);
        if (DBG) Log.d(TAG, "notifyNetworkRequested: network=" + network);
        final InterfaceSocketCallback callback = new InterfaceSocketCallback();
        mRequestedNetworks.put(listener, callback);
        mSocketProvider.requestSocket(network, callback);
    }

    /*** Notify that the network is unrequested */
    @Override
    public void notifyNetworkUnrequested(@NonNull MdnsServiceBrowserListener listener) {
        ensureRunningOnHandlerThread(mHandler);
        final InterfaceSocketCallback callback = mRequestedNetworks.remove(listener);
        if (callback == null) {
            Log.e(TAG, "Can not be unrequested with unknown listener=" + listener);
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
        mHandler.post(() -> sendMdnsPacket(packet));
    }

    /** Sends a mDNS request packet that asks for unicast response. */
    @Override
    public void sendUnicastPacket(@NonNull DatagramPacket packet) {
        // TODO: Separate unicast packet.
        mHandler.post(() -> sendMdnsPacket(packet));
    }
}
