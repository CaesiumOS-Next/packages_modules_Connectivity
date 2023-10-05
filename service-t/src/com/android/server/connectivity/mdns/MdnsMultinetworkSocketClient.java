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

import static com.android.server.connectivity.mdns.util.MdnsUtils.ensureRunningOnLooperThread;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresApi;
import android.net.LinkAddress;
import android.net.Network;
import android.os.Build;
import android.os.Looper;
import android.util.ArrayMap;

import com.android.net.module.util.SharedLog;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * The {@link MdnsMultinetworkSocketClient} manages the multinetwork socket for mDns
 *
 *  * <p>This class is not thread safe.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public class MdnsMultinetworkSocketClient implements MdnsSocketClientBase {
    private static final String TAG = MdnsMultinetworkSocketClient.class.getSimpleName();
    private static final boolean DBG = MdnsDiscoveryManager.DBG;
    // Max-sized buffers to be used as temporary buffer to read/build packets. May be used by
    // multiple components, but only for self-contained operations in the looper thread, so not
    // concurrently.
    // TODO: set according to MTU. 1300 should fit for ethernet MTU 1500 with some overhead.
    private final byte[] mPacketCreationBuffer = new byte[1300];

    @NonNull private final Looper mLooper;
    @NonNull private final MdnsSocketProvider mSocketProvider;
    @NonNull private final SharedLog mSharedLog;

    private final ArrayMap<MdnsServiceBrowserListener, InterfaceSocketCallback> mSocketRequests =
            new ArrayMap<>();
    private final ArrayMap<SocketKey, ReadPacketHandler> mSocketPacketHandlers = new ArrayMap<>();
    private final ArrayMap<SocketKey, MdnsPacketSender> mSocketPacketSenders = new ArrayMap<>();
    private MdnsSocketClientBase.Callback mCallback = null;
    private int mReceivedPacketNumber = 0;

    public MdnsMultinetworkSocketClient(@NonNull Looper looper,
            @NonNull MdnsSocketProvider provider,
            @NonNull SharedLog sharedLog) {
        mLooper = looper;
        mSocketProvider = provider;
        mSharedLog = sharedLog;
    }

    private class InterfaceSocketCallback implements MdnsSocketProvider.SocketCallback {
        @NonNull
        private final SocketCreationCallback mSocketCreationCallback;
        @NonNull
        private final ArrayMap<SocketKey, MdnsInterfaceSocket> mActiveSockets = new ArrayMap<>();

        InterfaceSocketCallback(SocketCreationCallback socketCreationCallback) {
            mSocketCreationCallback = socketCreationCallback;
        }

        @Override
        public void onSocketCreated(@NonNull SocketKey socketKey,
                @NonNull MdnsInterfaceSocket socket, @NonNull List<LinkAddress> addresses) {
            // The socket may be already created by other request before, try to get the stored
            // ReadPacketHandler and MdnsPacketSender.
            ReadPacketHandler handler = mSocketPacketHandlers.get(socketKey);
            if (handler == null) {
                // First request to create this socket. Initial a ReadPacketHandler for this socket.
                handler = new ReadPacketHandler(socketKey);
                mSocketPacketHandlers.put(socketKey, handler);
            }
            socket.addPacketHandler(handler);

            MdnsPacketSender sender = mSocketPacketSenders.get(socketKey);
            if (sender == null) {
                // First request to create this socket. Initial a MdnsPacketSender for this socket.
                sender = new MdnsPacketSender(mLooper, socket, mPacketCreationBuffer, mSharedLog);
                mSocketPacketSenders.put(socketKey, sender);
            }
            mActiveSockets.put(socketKey, socket);
            mSocketCreationCallback.onSocketCreated(socketKey, sender);
        }

        @Override
        public void onInterfaceDestroyed(@NonNull SocketKey socketKey,
                @NonNull MdnsInterfaceSocket socket) {
            notifySocketDestroyed(socketKey);
            maybeCleanupPacketHandlerAndSender(socketKey);
        }

        private void notifySocketDestroyed(@NonNull SocketKey socketKey) {
            mActiveSockets.remove(socketKey);
            if (!isSocketActive(socketKey)) {
                mSocketCreationCallback.onSocketDestroyed(socketKey);
            }
        }

        void onNetworkUnrequested() {
            for (int i = mActiveSockets.size() - 1; i >= 0; i--) {
                // Iterate from the end so the socket can be removed
                final SocketKey socketKey = mActiveSockets.keyAt(i);
                notifySocketDestroyed(socketKey);
                maybeCleanupPacketHandlerAndSender(socketKey);
            }
        }
    }

    private boolean isSocketActive(@NonNull SocketKey socketKey) {
        for (int i = 0; i < mSocketRequests.size(); i++) {
            final InterfaceSocketCallback ifaceSocketCallback = mSocketRequests.valueAt(i);
            if (ifaceSocketCallback.mActiveSockets.containsKey(socketKey)) {
                return true;
            }
        }
        return false;
    }

    private void maybeCleanupPacketHandlerAndSender(@NonNull SocketKey socketKey) {
        if (isSocketActive(socketKey)) return;
        mSocketPacketHandlers.remove(socketKey);
        mSocketPacketSenders.remove(socketKey);
    }

    private class ReadPacketHandler implements MulticastPacketReader.PacketHandler {
        @NonNull private final SocketKey mSocketKey;

        ReadPacketHandler(@NonNull SocketKey socketKey) {
            mSocketKey = socketKey;
        }

        @Override
        public void handlePacket(byte[] recvbuf, int length, InetSocketAddress src) {
            processResponsePacket(recvbuf, length, mSocketKey);
        }
    }

    /*** Set callback for receiving mDns response */
    @Override
    public void setCallback(@Nullable MdnsSocketClientBase.Callback callback) {
        ensureRunningOnLooperThread(mLooper);
        mCallback = callback;
    }

    /***
     * Notify that the given network is requested for mdns discovery / resolution
     *
     * @param listener the listener for discovery.
     * @param network the target network for discovery. Null means discovery on all possible
     *                interfaces.
     * @param socketCreationCallback the callback to notify socket creation.
     */
    @Override
    public void notifyNetworkRequested(@NonNull MdnsServiceBrowserListener listener,
            @Nullable Network network, @NonNull SocketCreationCallback socketCreationCallback) {
        ensureRunningOnLooperThread(mLooper);
        InterfaceSocketCallback callback = mSocketRequests.get(listener);
        if (callback != null) {
            throw new IllegalArgumentException("Can not register duplicated listener");
        }

        if (DBG) mSharedLog.v("notifyNetworkRequested: network=" + network);
        callback = new InterfaceSocketCallback(socketCreationCallback);
        mSocketRequests.put(listener, callback);
        mSocketProvider.requestSocket(network, callback);
    }

    /*** Notify that the network is unrequested */
    @Override
    public void notifyNetworkUnrequested(@NonNull MdnsServiceBrowserListener listener) {
        ensureRunningOnLooperThread(mLooper);
        final InterfaceSocketCallback callback = mSocketRequests.get(listener);
        if (callback == null) {
            mSharedLog.e("Can not be unrequested with unknown listener=" + listener);
            return;
        }
        callback.onNetworkUnrequested();
        // onNetworkUnrequested does cleanups based on mSocketRequests, only remove afterwards
        mSocketRequests.remove(listener);
        mSocketProvider.unrequestSocket(callback);
    }

    @Override
    public Looper getLooper() {
        return mLooper;
    }

    @Override
    public boolean supportsRequestingSpecificNetworks() {
        return true;
    }

    private void processResponsePacket(byte[] recvbuf, int length, @NonNull SocketKey socketKey) {
        int packetNumber = ++mReceivedPacketNumber;

        final MdnsPacket response;
        try {
            response = MdnsResponseDecoder.parseResponse(recvbuf, length);
        } catch (MdnsPacket.ParseException e) {
            if (e.code != MdnsResponseErrorCode.ERROR_NOT_RESPONSE_MESSAGE) {
                mSharedLog.e(e.getMessage(), e);
                if (mCallback != null) {
                    mCallback.onFailedToParseMdnsResponse(packetNumber, e.code, socketKey);
                }
            }
            return;
        }

        if (mCallback != null) {
            mCallback.onResponseReceived(response, socketKey);
        }
    }
}