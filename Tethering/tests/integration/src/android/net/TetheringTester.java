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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import android.net.dhcp.DhcpAckPacket;
import android.net.dhcp.DhcpOfferPacket;
import android.net.dhcp.DhcpPacket;
import android.os.Handler;
import android.os.HandlerThread;
import android.system.Os;
import android.util.Log;

import com.android.testutils.HandlerUtils;
import com.android.testutils.TapPacketReader;

import java.io.FileDescriptor;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * 
 */
public class TetheringTester {
    private static final String TAG = TetheringTester.class.getSimpleName();
    private static final int TIMEOUT_MS = 5000;
    private static final int PACKET_READ_TIMEOUT_MS = 100;
    private static final int DHCP_DISCOVER_ATTEMPTS = 10;
    private static final byte[] DHCP_REQUESTED_PARAMS = new byte[] {
            DhcpPacket.DHCP_SUBNET_MASK,
            DhcpPacket.DHCP_ROUTER,
            DhcpPacket.DHCP_DNS_SERVER,
            DhcpPacket.DHCP_LEASE_TIME,
    };
    private static final String DHCP_HOSTNAME = "testhostname";

    private final MacAddress mMacAddr;
    private final TestNetworkInterface mDownstreamIface;
    private final TestNetworkInterface mUpstreamIface;
    private final TapPacketReader mDownstreamReader;
    private final TapPacketReader mUpstreamReader;
    private InetAddress mIpv4Addr;
    private InetAddress mIpv6Addr;
    private HandlerThread mHandlerThread;
    private Handler mHandler;

    private TetheringTester(boolean testIpv4, boolean testIpv6, TestNetworkInterface downstream,
            TestNetworkInterface upstream) throws Exception {
        byte[] macAddr = new byte[6];
        new Random().nextBytes(macAddr);
        mMacAddr = MacAddress.fromBytes(macAddr);

        mHandlerThread = new HandlerThread(getClass().getSimpleName());
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mDownstreamReader = makePacketReader(downstream);
        mUpstreamReader = makePacketReader(upstream);
        if (testIpv4) {
            DhcpResults dhcpResults = runDhcp(mDownstreamReader, macAddr);
            mIpv4Addr = dhcpResults.ipAddress.getAddress();
        }

        if (testIpv6) {
            // run RS RA
            mIpv6Addr = null;
        }

        mDownstreamIface = downstream;
        mUpstreamIface = upstream;
    }

    private TapPacketReader makePacketReader(TestNetworkInterface iface) throws Exception {
        NetworkInterface nif = NetworkInterface.getByName(iface.getInterfaceName());
        assertNotNull("Can't get NetworkInterface object for " + iface.getInterfaceName(), nif);

        FileDescriptor fd = iface.getFileDescriptor().getFileDescriptor();
        final TapPacketReader reader = new TapPacketReader(mHandler, fd, nif.getMTU());
        mHandler.post(() -> reader.start());
        HandlerUtils.waitForIdle(mHandler, TIMEOUT_MS);
        return reader;
    }

    public static TetheringTester setupTetheringTester(boolean testIpv4, boolean testIpv6,
            TestNetworkInterface downstream, TestNetworkInterface upstream) throws Exception {
        return new TetheringTester(testIpv4, testIpv6, downstream, upstream);
    }

    public static DhcpResults runDhcp(TapPacketReader pktHandler, byte[] clientMacAddr)
            throws Exception {
        // We have to retransmit DHCP requests because IpServer declares itself to be ready before
        // its DhcpServer is actually started. TODO: fix this race and remove this loop.
        DhcpPacket offerPacket = null;
        for (int i = 0; i < DHCP_DISCOVER_ATTEMPTS; i++) {
            Log.d(TAG, "Sending DHCP discover");
            sendDhcpDiscover(pktHandler, clientMacAddr);
            offerPacket = getNextDhcpPacket(pktHandler);
            if (offerPacket instanceof DhcpOfferPacket) break;
        }
        if (!(offerPacket instanceof DhcpOfferPacket)) {
            throw new TimeoutException("No DHCPOFFER received on interface within timeout");
        }

        sendDhcpRequest(pktHandler, offerPacket, clientMacAddr);
        DhcpPacket ackPacket = getNextDhcpPacket(pktHandler);
        if (!(ackPacket instanceof DhcpAckPacket)) {
            throw new TimeoutException("No DHCPACK received on interface within timeout");
        }

        return ackPacket.toDhcpResults();
    }

    private static void sendDhcpDiscover(TapPacketReader pktHandler, byte[] macAddress) throws Exception {
        ByteBuffer packet = DhcpPacket.buildDiscoverPacket(DhcpPacket.ENCAP_L2,
                new Random().nextInt() /* transactionId */, (short) 0 /* secs */,
                macAddress,  false /* unicast */, DHCP_REQUESTED_PARAMS,
                false /* rapid commit */,  DHCP_HOSTNAME);
        pktHandler.sendResponse(packet);
    }

    private static void sendDhcpRequest(TapPacketReader pktHandler, DhcpPacket offerPacket, byte[] macAddress)
            throws Exception {
        DhcpResults results = offerPacket.toDhcpResults();
        Inet4Address clientIp = (Inet4Address) results.ipAddress.getAddress();
        Inet4Address serverIdentifier = results.serverAddress;
        ByteBuffer packet = DhcpPacket.buildRequestPacket(DhcpPacket.ENCAP_L2,
                0 /* transactionId */, (short) 0 /* secs */, DhcpPacket.INADDR_ANY /* clientIp */,
                false /* broadcast */, macAddress, clientIp /* requestedIpAddress */,
                serverIdentifier, DHCP_REQUESTED_PARAMS, DHCP_HOSTNAME);
        pktHandler.sendResponse(packet);
    }

    private static DhcpPacket getNextDhcpPacket(TapPacketReader pktHandler) throws ParseException {
        byte[] packet;
        while ((packet = getNextReceivedPacket(pktHandler)) != null) {
            try {
                return DhcpPacket.decodeFullPacket(packet, packet.length, DhcpPacket.ENCAP_L2);
            } catch (DhcpPacket.ParseException e) {
                // Not a DHCP packet. Continue.
            }
        }
        return null;
    }

    public void sendPacket(ByteBuffer packet) throws Exception {
        mDownstreamReader.sendResponse(packet);
    }

    public void expectRecieve(Predicate<byte[]> expect) throws Exception {
        byte[] packet;
        while ((packet = getNextReceivedPacket(mDownstreamReader)) != null) {
            Log.d(TAG, "Receive packet: " +  dumpRawPacket(packet));

            if (expect.test(packet)) return true;
        }

        fail("Do not receive expected packet from RemoteResponder");
    }

    public void shutDown() {
        mHandler.post(() -> mDownstreamReader.stop());
        mHandler.post(() -> mUpstreamReader.stop());
        mHandlerThread.quitSafely();
    }

    public RemoteResponder createRemoteResponder(InetAddress v4Addr, InetAddress v6Addr) {
        return new RemoteResponder(v4Addr, v6Addr, mUpstreamReader);
    }

    public static class RemoteResponder {
        private final TapPacketReader mResponder;
        private final InetAddress mIpv4Addr;
        private final InetAddress mIpv6Addr;

        private RemoteResponder(InetAddress v4Addr, InetAddress v6Addr, TapPacketReader responder) {
            mIpv4Addr = v4Addr;
            mIpv6Addr = v6Addr;
            mResponder = responder;
        }

        public void sendPacket(ByteBuffer packet) throws Exception {
            mResponder.sendResponse(packet);
        }

        public void expectRecieve(Predicate<byte[]> expect) throws Exception {
            byte[] packet;
            while ((packet = getNextReceivedPacket(mResponder)) != null) {
                Log.d(TAG, "Receive packet: " +  dumpRawPacket(packet));

                if (expect.test(packet)) return true;
            }
            fail("RemoteResponder do not receive expected packet");
        }

    }

    private static byte[] getNextReceivedPacket(TapPacketReader reader) throws Excpetion {
        return reader.popPacket(PACKET_READ_TIMEOUT_MS);
    }

    private static String dumpRawPacket(byte[] a) {
        StringBuilder sb = new StringBuilder(a.length * 2);
        for(byte b: a) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
