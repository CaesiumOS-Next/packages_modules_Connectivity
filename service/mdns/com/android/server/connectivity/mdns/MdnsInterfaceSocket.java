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

import static com.android.server.connectivity.mdns.MdnsSocket.MULTICAST_IPV4_ADDRESS;
import static com.android.server.connectivity.mdns.MdnsSocket.MULTICAST_IPV6_ADDRESS;

import android.annotation.NonNull;
import android.net.util.SocketUtils;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.List;

/**
 * {@link MdnsInterfaceSocket} provides a similar interface to {@link MulticastSocket} and binds to
 * an available multicast network interfaces.
 *
 * <p>This isn't thread safe and should be always called on the same thread.
 *
 * @see MulticastSocket for javadoc of each public method.
 */
public class MdnsInterfaceSocket {
    private static final String TAG = MdnsInterfaceSocket.class.getSimpleName();
    @NonNull private final MulticastSocket mMulticastSocket;
    @NonNull private final NetworkInterface mNetworkInterface;
    private boolean mJoinedIpv4 = false;
    private boolean mJoinedIpv6 = false;

    public MdnsInterfaceSocket(@NonNull NetworkInterface networkInterface, int port)
            throws IOException {
        mNetworkInterface = networkInterface;
        mMulticastSocket = new MulticastSocket(port);
        // RFC Spec: https://tools.ietf.org/html/rfc6762. Time to live is set 255
        mMulticastSocket.setTimeToLive(255);
        mMulticastSocket.setNetworkInterface(networkInterface);

        // Bind socket to the interface for receiving from that interface only.
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.fromDatagramSocket(mMulticastSocket)) {
            SocketUtils.bindSocketToInterface(pfd.getFileDescriptor(), mNetworkInterface.getName());
        } catch (ErrnoException e) {
            throw new IOException("Error setting socket options", e);
        }
    }

    /*** Sends a datagram packet from this socket. */
    public void send(DatagramPacket packet) throws IOException {
        mMulticastSocket.send(packet);
    }

    /*** Receives a datagram packet from this socket. */
    public void receive(DatagramPacket packet) throws IOException {
        mMulticastSocket.receive(packet);
    }

    private <T extends InetAddress> boolean hasAddress(List<InterfaceAddress> addrs,
            Class<T> addrClass) {
        for (InterfaceAddress addr : addrs) {
            if (addrClass.isAssignableFrom(addr.getAddress().getClass())) {
                return true;
            }
        }
        return false;
    }

    /*** Joins both IPv4 and IPv6 multicast groups. */
    public void joinGroup() {
        maybeJoinIpv4();
        maybeJoinIpv6();
    }

    private boolean joinGroup(InetSocketAddress multicastAddress) {
        try {
            mMulticastSocket.joinGroup(multicastAddress, mNetworkInterface);
            return true;
        } catch (IOException e) {
            // The address may have just been removed
            Log.e(TAG, "Error joining multicast group for " + mNetworkInterface, e);
            return false;
        }
    }

    private void maybeJoinIpv4() {
        final boolean hasAddr = hasAddress(mNetworkInterface.getInterfaceAddresses(),
                Inet4Address.class);
        if (!mJoinedIpv4 && hasAddr) {
            mJoinedIpv4 = joinGroup(MULTICAST_IPV4_ADDRESS);
        } else if (!hasAddr) {
            // Lost IPv4 address
            mJoinedIpv4 = false;
        }
    }

    private void maybeJoinIpv6() {
        final boolean hasAddr = hasAddress(mNetworkInterface.getInterfaceAddresses(),
                Inet6Address.class);
        if (!mJoinedIpv6 && hasAddr) {
            mJoinedIpv6 = joinGroup(MULTICAST_IPV6_ADDRESS);
        } else if (!hasAddr) {
            // Lost IPv6 address
            mJoinedIpv6 = false;
        }
    }

    /*** Leaves all joined multicast groups. */
    public void leaveGroup() {
        if (mJoinedIpv4) {
            try {
                mMulticastSocket.leaveGroup(MULTICAST_IPV4_ADDRESS, mNetworkInterface);
            } catch (IOException e) {
                Log.e(TAG, "Error leaving IPv4 group for " + mNetworkInterface, e);
            }
            mJoinedIpv4 = false;
        }
        if (mJoinedIpv6) {
            try {
                mMulticastSocket.leaveGroup(MULTICAST_IPV6_ADDRESS, mNetworkInterface);
            } catch (IOException e) {
                Log.e(TAG, "Error leaving IPv4 group for " + mNetworkInterface, e);
            }
            mJoinedIpv6 = false;
        }
    }

    /*** Closes this socket. */
    public void close() {
        mMulticastSocket.close();
    }

    /**
     * Returns the index of the network interface that this socket is bound to. If the interface
     * cannot be determined, returns -1.
     */
    public int getInterfaceIndex() {
        return mNetworkInterface.getIndex();
    }

    /*** Returns whether this socket has joined IPv4 group */
    public boolean hasJoinedIpv4() {
        return mJoinedIpv4;
    }

    /*** Returns whether this socket has joined IPv6 group */
    public boolean hasJoinedIpv6() {
        return mJoinedIpv6;
    }
}
