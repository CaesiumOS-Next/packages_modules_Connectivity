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

import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.util.List;

/**
 * {@link MdnsSocket} provides a similar interface to {@link MulticastSocket} and binds to all
 * available multi-cast network interfaces.
 *
 * @see MulticastSocket for javadoc of each public method.
 */
// TODO(b/177655645): Resolve nullness suppression.
@SuppressWarnings("nullness")
public class MdnsSocket {
    private static final InetSocketAddress MULTICAST_IPV4_ADDRESS =
            new InetSocketAddress(MdnsConstants.getMdnsIPv4Address(), MdnsConstants.MDNS_PORT);
    private static final InetSocketAddress MULTICAST_IPV6_ADDRESS =
            new InetSocketAddress(MdnsConstants.getMdnsIPv6Address(), MdnsConstants.MDNS_PORT);
    private static boolean sIsOnIPv6OnlyNetwork = false;
    private final MulticastNetworkInterfaceProvider mMulticastNetworkInterfaceProvider;
    private final MulticastSocket mMulticastSocket;

    public MdnsSocket(
            @NonNull MulticastNetworkInterfaceProvider multicastNetworkInterfaceProvider, int port)
            throws IOException {
        mMulticastNetworkInterfaceProvider = multicastNetworkInterfaceProvider;
        mMulticastNetworkInterfaceProvider.startWatchingConnectivityChanges();
        mMulticastSocket = createMulticastSocket(port);
        // RFC Spec: https://tools.ietf.org/html/rfc6762
        // Time to live is set 255, which is similar to the jMDNS implementation.
        mMulticastSocket.setTimeToLive(255);

        // TODO (changed when importing code): consider tagging the socket for data usage
        sIsOnIPv6OnlyNetwork = false;
    }

    public void send(DatagramPacket packet) throws IOException {
        List<NetworkInterfaceWrapper> networkInterfaces =
                mMulticastNetworkInterfaceProvider.getMulticastNetworkInterfaces();
        for (NetworkInterfaceWrapper networkInterface : networkInterfaces) {
            mMulticastSocket.setNetworkInterface(networkInterface.getNetworkInterface());
            mMulticastSocket.send(packet);
        }
    }

    public void receive(DatagramPacket packet) throws IOException {
        mMulticastSocket.receive(packet);
    }

    public void joinGroup() throws IOException {
        List<NetworkInterfaceWrapper> networkInterfaces =
                mMulticastNetworkInterfaceProvider.getMulticastNetworkInterfaces();
        InetSocketAddress multicastAddress = MULTICAST_IPV4_ADDRESS;
        if (mMulticastNetworkInterfaceProvider.isOnIpV6OnlyNetwork(networkInterfaces)) {
            sIsOnIPv6OnlyNetwork = true;
            multicastAddress = MULTICAST_IPV6_ADDRESS;
        } else {
            sIsOnIPv6OnlyNetwork = false;
        }
        for (NetworkInterfaceWrapper networkInterface : networkInterfaces) {
            mMulticastSocket.joinGroup(multicastAddress, networkInterface.getNetworkInterface());
        }
    }

    public void leaveGroup() throws IOException {
        List<NetworkInterfaceWrapper> networkInterfaces =
                mMulticastNetworkInterfaceProvider.getMulticastNetworkInterfaces();
        InetSocketAddress multicastAddress = MULTICAST_IPV4_ADDRESS;
        if (mMulticastNetworkInterfaceProvider.isOnIpV6OnlyNetwork(networkInterfaces)) {
            multicastAddress = MULTICAST_IPV6_ADDRESS;
        }
        for (NetworkInterfaceWrapper networkInterface : networkInterfaces) {
            mMulticastSocket.leaveGroup(multicastAddress, networkInterface.getNetworkInterface());
        }
    }

    public void close() {
        // This is a race with the use of the file descriptor (b/27403984).
        mMulticastSocket.close();
        mMulticastNetworkInterfaceProvider.stopWatchingConnectivityChanges();
    }

    @VisibleForTesting
    MulticastSocket createMulticastSocket(int port) throws IOException {
        return new MulticastSocket(port);
    }

    public boolean isOnIPv6OnlyNetwork() {
        return sIsOnIPv6OnlyNetwork;
    }
}
