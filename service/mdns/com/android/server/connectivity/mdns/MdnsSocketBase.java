/*
 * Copyright (C) 2021 The Android Open Source Project
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

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetSocketAddress;

/**
 * Base class for multicast socket.
 *
 * @hide
 */
public interface MdnsSocketBase {
    int INTERFACE_INDEX_UNSPECIFIED = -1;
    InetSocketAddress MULTICAST_IPV4_ADDRESS =
            new InetSocketAddress(MdnsConstants.getMdnsIPv4Address(), MdnsConstants.MDNS_PORT);
    InetSocketAddress MULTICAST_IPV6_ADDRESS =
            new InetSocketAddress(MdnsConstants.getMdnsIPv6Address(), MdnsConstants.MDNS_PORT);

    /*** Sends a datagram packet from this socket. */
    void send(@NonNull DatagramPacket packet) throws IOException;

    /*** Receives a datagram packet from this socket. */
    void receive(@NonNull DatagramPacket packet) throws IOException;

    /*** Joins the multicast groups. */
    void joinGroup() throws IOException;

    /*** Leaves the multicast groups. */
    void leaveGroup() throws IOException;

    /*** Closes this socket. */
    void close();

    /**
     * Returns the index of the network interface that this socket is bound to. If the interface
     * cannot be determined, returns -1.
     */
    default int getInterfaceIndex() {
        return INTERFACE_INDEX_UNSPECIFIED;
    }

    /*** Returns whether this socket has joined IPv4 group */
    default boolean hasJoinedIpv4() {
        return false;
    }

    /*** Returns whether this socket has joined IPv6 group */
    default boolean hasJoinedIpv6() {
        return false;
    }
}
