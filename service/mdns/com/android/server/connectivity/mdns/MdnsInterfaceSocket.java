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

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.List;

/**
 * {@link MdnsInterfaceSocket} provides a similar interface to {@link MulticastSocket} and binds to
 * an available multi-cast network interfaces.
 *
 * @see MulticastSocket for javadoc of each public method.
 */
public class MdnsInterfaceSocket extends MdnsSocket {
    private static final String TAG = MdnsInterfaceSocket.class.getSimpleName();
    private final MulticastSocket mMulticastSocket;
    private final NetworkInterface mNetworkInterface;
    private boolean mJoinedIpv4;
    private boolean mJoinedIpv6;

    public MdnsInterfaceSocket(NetworkInterface networkInterface, int port) throws IOException {
        mNetworkInterface = networkInterface;
        mMulticastSocket = new MulticastSocket(port);
        // RFC Spec: https://tools.ietf.org/html/rfc6762
        // Time to live is set 255, which is similar to the jMDNS implementation.
        mMulticastSocket.setTimeToLive(255);
        mMulticastSocket.setNetworkInterface(networkInterface);
    }

    @Override
    public void send(DatagramPacket packet) throws IOException {
        mMulticastSocket.send(packet);
    }

    @Override
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

    @Override
    public void joinGroup() {
        maybeJoinIpv4();
        maybeJoinIpv6();
    }

    private boolean joinGroup(InetAddress multicastAddr) {
        try {
            mMulticastSocket.joinGroup(multicastAddr);
            return true;
        } catch (IOException e) {
            // The IPv4 address may have just been removed
            Log.e(TAG, "Error joining multicast group for " + mNetworkInterface, e);
            return false;
        }
    }

    private void maybeJoinIpv4() {
        final boolean hasAddr = hasAddress(mNetworkInterface.getInterfaceAddresses(),
                Inet4Address.class);
        if (!mJoinedIpv4 && hasAddr) {
            mJoinedIpv4 = joinGroup(MdnsConstants.getMdnsIPv4Address());
        } else if (!hasAddr) {
            // Lost IPv4 address
            mJoinedIpv4 = false;
        }
    }

    private void maybeJoinIpv6() {
        final boolean hasAddr = hasAddress(mNetworkInterface.getInterfaceAddresses(),
                Inet6Address.class);
        if (!mJoinedIpv6 && hasAddr) {
            mJoinedIpv6 = joinGroup(MdnsConstants.getMdnsIPv6Address());
        } else if (!hasAddr) {
            // Lost IPv6 address
            mJoinedIpv6 = false;
        }
    }

    @Override
    public void leaveGroup() throws IOException {
        if (mJoinedIpv4) {
            mMulticastSocket.leaveGroup(MdnsConstants.getMdnsIPv4Address());
            mJoinedIpv4 = false;
        }
        if (mJoinedIpv6) {
            mMulticastSocket.leaveGroup(MdnsConstants.getMdnsIPv6Address());
            mJoinedIpv6 = false;
        }
    }

    @Override
    public void close() {
        mMulticastSocket.close();
    }

    /**
     * Returns the index of the network interface that this socket is bound to. If the interface
     * cannot be determined, returns -1.
     */
    @Override
    public int getInterfaceIndex() {
        return mNetworkInterface.getIndex();
    }

    @Override
    public boolean isOnIPv6OnlyNetwork() {
        return !mJoinedIpv4 && mJoinedIpv6;
    }
}
