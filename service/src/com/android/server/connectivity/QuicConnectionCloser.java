/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.connectivity;

import static android.system.OsConstants.ENOENT;
import static android.system.OsConstants.SOL_SOCKET;
import static android.system.OsConstants.SO_SNDTIMEO;

import android.annotation.NonNull;
import android.net.Network;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructTimeval;
import android.util.ArrayMap;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.netlink.InetDiagMessage;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class QuicConnectionCloser {
    private static final String TAG = QuicConnectionCloser.class.getSimpleName();

    private static final int SOCKET_WRITE_TIMEOUT_MS = 100;

    // Map from socket cookie to QUIC connection close information
    private final Map<Long, QuicConnectionCloseInfo>
            mRegisteredQuicConnectionCloseInfos = new ArrayMap<>();

    // The maximum number of QUIC connection close information entries that can be registered
    // concurrently.
    private static final int MAX_REGISTERED_QUIC_CONNECTION_CLOSE_INFO = 1000;

    @NonNull
    private final Dependencies mDeps;

    /**
     * Class to store the necessary information for closing a QUIC connection.
     */
    public static class QuicConnectionCloseInfo {
        public final int uid;
        public final int netId;
        public final long cookie;
        public final InetSocketAddress src;
        public final InetSocketAddress dst;
        public final byte[] payload;

        public QuicConnectionCloseInfo(int uid, int netId, long cookie, InetSocketAddress src,
                InetSocketAddress dst, byte[] payload) {
            this.uid = uid;
            this.netId = netId;
            this.cookie = cookie;
            this.src = src;
            this.dst = dst;
            this.payload = payload;
        }

        @Override
        public String toString() {
            return "QuicConnectionCloseInfo{"
                    + "uid: " + uid
                    + ", netId: " + netId
                    + ", cookie: " + cookie
                    + ", src: " + src
                    + ", dst: " + dst
                    + ", payload length: " + payload.length
                    + "}";
        }
    }

    public static class Dependencies {
        /**
         * Call {@link Os#if_nametoindex}
         */
        public int ifNameToIndex(final String ifname) {
            return Os.if_nametoindex(ifname);
        }

        /**
         * Send a UDP packet with the specified source and destination address and port over the
         * specified network.
         *
         * @param network The {@link Network} over which the UDP packet will be sent.
         * @param src     The source {@link InetSocketAddress} of the UDP packet to be sent.
         * @param dst     The destination {@link InetSocketAddress} of the UDP packet to be sent.
         * @param payload The UDP payload to be sent.
         */
        public void sendQuicConnectionClosePayload(final Network network,
                final InetSocketAddress src, final InetSocketAddress dst, final byte[] payload)
                throws IOException, ErrnoException {
            final DatagramSocket socket = new DatagramSocket(src);
            network.bindSocket(socket);
            socket.connect(dst);
            Os.setsockoptTimeval(socket.getFileDescriptor$(), SOL_SOCKET, SO_SNDTIMEO,
                    StructTimeval.fromMillis(SOCKET_WRITE_TIMEOUT_MS));
            Os.write(socket.getFileDescriptor$(), payload, 0 /* byteOffset */, payload.length);
        }

        /**
         * Call {@link InetDiagMessage#destroyUdpSocket}
         */
        public void destroyUdpSocket(final InetSocketAddress src, final InetSocketAddress dst,
                final int ifindex, final long cookie)
                throws SocketException, InterruptedIOException, ErrnoException {
            InetDiagMessage.destroyUdpSocket(src, dst, ifindex, cookie);
        }
    }

    public QuicConnectionCloser() {
        this(new Dependencies());
    }

    @VisibleForTesting
    public QuicConnectionCloser(final Dependencies deps) {
        mDeps = deps;
    }

    /**
     * Close registered QUIC connection by uids
     *
     * @param uids target uids to close QUIC connections
     * @param networkForNetId {@link SparseArray} from NetId to registered {@link NetworkAgentInfo}
     */
    public void closeQuicConnectionByUids(final Set<Integer> uids,
            final SparseArray<NetworkAgentInfo> networkForNetId) {
        for (Iterator<Map.Entry<Long, QuicConnectionCloseInfo>> it =
                mRegisteredQuicConnectionCloseInfos.entrySet().iterator(); it.hasNext();) {
            final QuicConnectionCloseInfo info = it.next().getValue();
            if (uids.contains(info.uid)) {
                closeQuicConnection(info, networkForNetId, true /* destroySocket */);
                it.remove();
            }
        }
    }

    private void closeQuicConnection(final QuicConnectionCloseInfo info,
            final SparseArray<NetworkAgentInfo> networkForNetId,
            final boolean destroySocket) {
        Log.d(TAG, "Close QUIC socket for " + info + ", destroySocket=" + destroySocket);
        final NetworkAgentInfo nai = networkForNetId.get(info.netId);
        if (nai == null || !nai.linkProperties.getAddresses().contains(info.src.getAddress())) {
            // The device should not send a packet with an unused source address.
            return;
        }

        if (destroySocket) {
            final String iface = nai.linkProperties.getInterfaceName();
            final int ifindex = iface == null ? 0 : mDeps.ifNameToIndex(iface);
            if (ifindex == 0) {
                // Interface is unknown or already removed
                return;
            }

            try {
                mDeps.destroyUdpSocket(info.src, info.dst, ifindex, info.cookie);
            } catch (ErrnoException | SocketException | InterruptedIOException e) {
                if (e instanceof ErrnoException && ((ErrnoException) e).errno == ENOENT) {
                    // This can happen if the socket is already closed, but unregister message is
                    // not processed yet.
                    return;
                }
                Log.e(TAG, "Failed to destroy QUIC socket for " + info + ": " + e);
                return;
            }
        }

        try {
            mDeps.sendQuicConnectionClosePayload(nai.network(), info.src, info.dst, info.payload);
        } catch (ErrnoException | IOException e) {
            Log.e(TAG, "Failed to send registered QUIC connection close payload for "
                    + info + ": " + e);
        }
    }

    /**
     * Close QUIC connection if the registered socket was destroyed
     *
     * @param inetDiagMessage {@link InetDiagMessage} received from kernel
     * @param networkForNetId {@link SparseArray} from NetId to registered {@link NetworkAgentInfo}
     */
    public void handleUdpSocketDestroy(final InetDiagMessage inetDiagMessage,
            final SparseArray<NetworkAgentInfo> networkForNetId) {
        final long cookie = inetDiagMessage.inetDiagMsg.id.cookie;
        final QuicConnectionCloseInfo info = mRegisteredQuicConnectionCloseInfos.remove(cookie);
        if (info == null) {
            // Destroyed socket is not registered or already unregistered.
            return;
        }

        // App registered a QUIC connection close payload and this socket, but the socket was
        // closed before the socket was unregistered.
        // This can happen if the app crashes or is killed.
        closeQuicConnection(info, networkForNetId, false /* destroySocket */);
    }

    /**
     * Register the QUIC connection close information
     *
     * @param info {@link QuicConnectionCloseInfo} to register
     */
    public void handleRegisterQuicConnectionCloseInfo(final QuicConnectionCloseInfo info) {
        if (mRegisteredQuicConnectionCloseInfos.size()
                >= MAX_REGISTERED_QUIC_CONNECTION_CLOSE_INFO) {
            Log.e(TAG, "Failed to register QUIC connection close information."
                    + " number of registered information exceeded "
                    + MAX_REGISTERED_QUIC_CONNECTION_CLOSE_INFO);
            return;
        }
        mRegisteredQuicConnectionCloseInfos.put(info.cookie, info);
    }


    /**
     * Unregister the QUIC connection close information
     *
     * @param cookie cookie of the socket whose connection close information should be unregistered
     */
    public void handleUnregisterQuicConnectionCloseInfo(final long cookie) {
        mRegisteredQuicConnectionCloseInfos.remove(cookie);
    }

    /**
     * Dump QUIC connection closer information
     */
    public void dump(final IndentingPrintWriter pw) {
        pw.println("Registered QUIC connection close information: "
                + mRegisteredQuicConnectionCloseInfos.size());
        pw.increaseIndent();
        for (QuicConnectionCloseInfo info: mRegisteredQuicConnectionCloseInfos.values()) {
            pw.println(info);
        }
        pw.decreaseIndent();
    }
}
