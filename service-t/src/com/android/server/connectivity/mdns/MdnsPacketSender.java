/*
 * Copyright (C) 2023 The Android Open Source Project
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
import android.annotation.RequiresApi;
import android.os.Build;
import android.os.Looper;

import com.android.net.module.util.SharedLog;
import com.android.server.connectivity.mdns.util.MdnsUtils;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;

/**
 * A class that handles sending mDNS packets to a {@link MulticastSocket}
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public class MdnsPacketSender implements MdnsSender {
    static final boolean DBG = MdnsAdvertiser.DBG;

    @NonNull
    final MdnsInterfaceSocket mSocket;
    @NonNull
    final Looper mLooper;
    @NonNull
    final byte[] mPacketCreationBuffer;
    @NonNull
    final SharedLog mSharedLog;

    public MdnsPacketSender(@NonNull Looper looper, @NonNull MdnsInterfaceSocket socket,
            @NonNull byte[] packetCreationBuffer, @NonNull SharedLog sharedLog) {
        mLooper = looper;
        mSocket = socket;
        mPacketCreationBuffer = packetCreationBuffer;
        mSharedLog = sharedLog;
    }

    /**
     * Send a packet immediately.
     *
     * Must be called on the looper thread used by the {@link MdnsPacketSender}.
     */
    public int sendNow(@NonNull MdnsPacket packet, @NonNull InetSocketAddress destination,
            boolean onlyUseIpv6OnIpv6OnlyNetworks, boolean expectUnicastResponse)
            throws IOException {
        ensureRunningOnLooperThread(mLooper);
        final boolean isIpv6 = destination.getAddress() instanceof Inet6Address;
        final boolean isIpv4 = destination.getAddress() instanceof Inet4Address;
        final boolean shouldQueryIpv6 = !onlyUseIpv6OnIpv6OnlyNetworks || !mSocket.hasJoinedIpv4();
        if (!((isIpv6 && mSocket.hasJoinedIpv6() && shouldQueryIpv6)
                || (isIpv4 && mSocket.hasJoinedIpv4()))) {
            // Skip sending if the socket has not joined the v4/v6 group (there was no address)
            return PACKET_NOT_SENT;
        }
        final byte[] outBuffer = MdnsUtils.createRawDnsPacket(mPacketCreationBuffer, packet);
        mSocket.send(new DatagramPacket(outBuffer, 0, outBuffer.length, destination));
        return PACKET_SENT;
    }

    /** Get the packetCreationBuffer */
    public byte[] getPacketCreationBuffer() {
        return mPacketCreationBuffer;
    }
}
