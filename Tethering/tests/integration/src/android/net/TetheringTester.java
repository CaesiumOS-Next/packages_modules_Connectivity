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

package android.net;

import android.net.dhcp.DhcpAckPacket;
import android.net.dhcp.DhcpOfferPacket;
import android.net.dhcp.DhcpPacket;
import android.util.Log;

import com.android.testutils.TapPacketReader;

import java.net.Inet4Address;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * A class simulate tethered client. When caller create TetheringTester, it would connect to
 * tethering module that do the dhcp and slaac to obtain ipv4 and ipv6 address. Then caller can
 * send/receive packets by this class.
 */
public final class TetheringTester {
    private static final String TAG = TetheringTester.class.getSimpleName();
    private static final int PACKET_READ_TIMEOUT_MS = 100;
    private static final int DHCP_DISCOVER_ATTEMPTS = 10;
    private static final byte[] DHCP_REQUESTED_PARAMS = new byte[] {
            DhcpPacket.DHCP_SUBNET_MASK,
            DhcpPacket.DHCP_ROUTER,
            DhcpPacket.DHCP_DNS_SERVER,
            DhcpPacket.DHCP_LEASE_TIME,
    };

    public static final String DHCP_HOSTNAME = "testhostname";

    /** Simulate dhcp client to obtain ipv4 address. */
    public static DhcpResults runDhcp(TapPacketReader fdHolder, byte[] clientMacAddr)
            throws Exception {
        // We have to retransmit DHCP requests because IpServer declares itself to be ready before
        // its DhcpServer is actually started. TODO: fix this race and remove this loop.
        DhcpPacket offerPacket = null;
        for (int i = 0; i < DHCP_DISCOVER_ATTEMPTS; i++) {
            Log.d(TAG, "Sending DHCP discover");
            sendDhcpDiscover(fdHolder, clientMacAddr);
            offerPacket = getNextDhcpPacket(fdHolder);
            if (offerPacket instanceof DhcpOfferPacket) break;
        }
        if (!(offerPacket instanceof DhcpOfferPacket)) {
            throw new TimeoutException("No DHCPOFFER received on interface within timeout");
        }

        sendDhcpRequest(fdHolder, offerPacket, clientMacAddr);
        DhcpPacket ackPacket = getNextDhcpPacket(fdHolder);
        if (!(ackPacket instanceof DhcpAckPacket)) {
            throw new TimeoutException("No DHCPACK received on interface within timeout");
        }

        return ackPacket.toDhcpResults();
    }

    private static void sendDhcpDiscover(TapPacketReader fdHolder,
            byte[] macAddress) throws Exception {
        ByteBuffer packet = DhcpPacket.buildDiscoverPacket(DhcpPacket.ENCAP_L2,
                new Random().nextInt() /* transactionId */, (short) 0 /* secs */,
                macAddress,  false /* unicast */, DHCP_REQUESTED_PARAMS,
                false /* rapid commit */,  DHCP_HOSTNAME);
        fdHolder.sendResponse(packet);
    }

    private static void sendDhcpRequest(TapPacketReader fdHolder, DhcpPacket offerPacket,
            byte[] macAddress) throws Exception {
        DhcpResults results = offerPacket.toDhcpResults();
        Inet4Address clientIp = (Inet4Address) results.ipAddress.getAddress();
        Inet4Address serverIdentifier = results.serverAddress;
        ByteBuffer packet = DhcpPacket.buildRequestPacket(DhcpPacket.ENCAP_L2,
                0 /* transactionId */, (short) 0 /* secs */, DhcpPacket.INADDR_ANY /* clientIp */,
                false /* broadcast */, macAddress, clientIp /* requestedIpAddress */,
                serverIdentifier, DHCP_REQUESTED_PARAMS, DHCP_HOSTNAME);
        fdHolder.sendResponse(packet);
    }

    private static DhcpPacket getNextDhcpPacket(TapPacketReader reader) {
        return getNextMatchedPacket(reader, (p) -> {
            try {
                return DhcpPacket.decodeFullPacket(p, p.length, DhcpPacket.ENCAP_L2);
            } catch (DhcpPacket.ParseException e) {
                // Not a DHCP packet.
            }

            return null;
        });
    }

    private static <R> R getNextMatchedPacket(TapPacketReader reader, Function<byte[], R> match) {
        byte[] packet;
        R result;
        while ((packet = reader.popPacket(PACKET_READ_TIMEOUT_MS)) != null) {
            result = expect.apply(packet);

            if (result != null) return result;
        }

        return null;
    }
}
