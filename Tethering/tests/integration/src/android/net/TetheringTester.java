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

import static android.net.InetAddresses.parseNumericAddress;
import static android.system.OsConstants.IPPROTO_ICMPV6;

import static com.android.net.module.util.NetworkStackConstants.ETHER_TYPE_IPV6;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ECHO_REPLY_TYPE;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ECHO_REQUEST_TYPE;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_NEIGHBOR_SOLICITATION;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ND_OPTION_PIO;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ND_OPTION_SLLA;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ND_OPTION_TLLA;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ROUTER_ADVERTISEMENT;
import static com.android.net.module.util.NetworkStackConstants.IPV6_ADDR_ALL_NODES_MULTICAST;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import android.net.dhcp.DhcpAckPacket;
import android.net.dhcp.DhcpOfferPacket;
import android.net.dhcp.DhcpPacket;
import android.os.Handler;
import android.os.HandlerThread;
import android.system.Os;
import android.util.Log;

import com.android.net.module.util.Struct;
import com.android.net.module.util.structs.EthernetHeader;
import com.android.net.module.util.structs.Icmpv6Header;
import com.android.net.module.util.structs.Ipv6Header;
import com.android.net.module.util.structs.LlaOption;
import com.android.net.module.util.structs.PrefixInformationOption;
import com.android.net.module.util.structs.RaHeader;
import com.android.net.module.util.Ipv6Utils;
import com.android.testutils.HandlerUtils;
import com.android.testutils.TapPacketReader;

import java.io.FileDescriptor;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

public final class TetheringTester {
    private static final String TAG = TetheringTester.class.getSimpleName();
    private static final int TIMEOUT_MS = 5000;
    private static final int PACKET_READ_TIMEOUT_MS = 100;
    private static final int IPV6_ADDR_BITS = 128;
    private static final int DHCP_DISCOVER_ATTEMPTS = 10;
    private static final byte[] DHCP_REQUESTED_PARAMS = new byte[] {
            DhcpPacket.DHCP_SUBNET_MASK,
            DhcpPacket.DHCP_ROUTER,
            DhcpPacket.DHCP_DNS_SERVER,
            DhcpPacket.DHCP_LEASE_TIME,
    };
    private static final InetAddress LINK_LOCAL = parseNumericAddress("fe80::1");

    public static final String DHCP_HOSTNAME = "testhostname";

    private final MacAddress mMacAddr;
    private final TestNetworkInterface mDownstreamIface;
    private final TestNetworkInterface mUpstreamIface;
    private final TapPacketReader mDownstreamReader;
    private final TapPacketReader mUpstreamReader;
    private MacAddress mRouterMacAddr;
    private InetAddress mIpv4Addr;
    private InetAddress mIpv6Addr;
    private HandlerThread mHandlerThread;
    private Handler mHandler;

    private TetheringTester(TestNetworkInterface downstream, TestNetworkInterface upstream)
            throws Exception {
        byte[] macAddr = new byte[6];
        new Random().nextBytes(macAddr);
        mMacAddr = MacAddress.fromBytes(macAddr);

        mHandlerThread = new HandlerThread(getClass().getSimpleName());
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mDownstreamReader = makePacketReader(downstream);
        mUpstreamReader = makePacketReader(upstream);

        DhcpResults dhcpResults = runDhcp(mDownstreamReader, macAddr);
        mIpv4Addr = dhcpResults.ipAddress.getAddress();

        ensureHasRouterMacAddress();
        mIpv6Addr = runSlaac();

        mDownstreamIface = downstream;
        mUpstreamIface = upstream;
    }

    public MacAddress getMacAddress() {
        return mMacAddr;
    }

    public MacAddress getRouterMacAddress() {
        return mRouterMacAddr;
    }

    public Inet4Address getIp4Address() {
        return (Inet4Address) mIpv4Addr;
    }

    public Inet6Address getIp6Address() {
        return (Inet6Address) mIpv6Addr;
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

    /** Create a TetheringTester to send/receive packets. */
    public static TetheringTester setupTetheringTester(TestNetworkInterface downstream,
            TestNetworkInterface upstream) throws Exception {
        return new TetheringTester(downstream, upstream);
    }

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

    private static DhcpPacket getNextDhcpPacket(TapPacketReader reader) throws Exception {
        return expectPacket(reader, (p) -> {
            try {
                return DhcpPacket.decodeFullPacket(p, p.length, DhcpPacket.ENCAP_L2);
            } catch (DhcpPacket.ParseException e) {
                // Not a DHCP packet.
            }

            return null;
        });
    }

    private void ensureHasRouterMacAddress() throws Exception {
        // Couldn't get mac address from NetworkInterface after R. Assume router mac address never
        // change during the test, then obtain router mac address from RA.
        // TODO: Obtain router mac address from ARP instead.
        if (mRouterMacAddr != null) return;

        expectPacket(mDownstreamReader, (p) -> {
            getRaPrefixOptions(p);

            // Return null to read next packet if still don't have router mac address.
            return mRouterMacAddr != null ? p : null;
        });

        if (mRouterMacAddr == null) fail("Could not obtain router mac address from RA");
    }

    private void maybeObtainRouterMacAddress(EthernetHeader ethHdr) {
        if (mRouterMacAddr != null) return;

        mRouterMacAddr = ethHdr.srcMac;
    }

    private List<PrefixInformationOption> getRaPrefixOptions(byte[] packet) {
        ByteBuffer buf = ByteBuffer.wrap(packet);
        final EthernetHeader ethHdr = Struct.parse(EthernetHeader.class, buf);
        if (ethHdr.etherType != ETHER_TYPE_IPV6) return null;

        final Ipv6Header ipv6Hdr = Struct.parse(Ipv6Header.class, buf);
        if (ipv6Hdr.nextHeader != (byte) IPPROTO_ICMPV6) return null;

        final Icmpv6Header icmpv6Hdr = Struct.parse(Icmpv6Header.class, buf);
        if (icmpv6Hdr.type == (short) ICMPV6_ROUTER_ADVERTISEMENT) {
            maybeObtainRouterMacAddress(ethHdr);

            Struct.parse(RaHeader.class, buf);
            final ArrayList<PrefixInformationOption> pioList = new ArrayList<>();
            while (buf.position() < packet.length) {
                final int currentPos = buf.position();
                final int type = Byte.toUnsignedInt(buf.get());
                if (type == ICMPV6_ND_OPTION_PIO) {
                    final ByteBuffer pioBuf = ByteBuffer.wrap(buf.array(), currentPos,
                            Struct.getSize(PrefixInformationOption.class));
                    final PrefixInformationOption pio =
                            Struct.parse(PrefixInformationOption.class, pioBuf);
                    pioList.add(pio);

                    // Move ByteBuffer position to the next option.
                    buf.position(currentPos + Struct.getSize(PrefixInformationOption.class));
                }
            }
            return pioList;
        }

        return null;
    }

    public InetAddress runSlaac() throws Exception {
        sendRsPacket();

        final List<PrefixInformationOption> options = expectPacket(mDownstreamReader, (p) -> {
            return getRaPrefixOptions(p);
        });

        byte[] address = null;
        int length = 0;
        for (PrefixInformationOption pio : options) {
            if (pio.validLifetime > 0) {
                address = pio.prefix;
                length = pio.length;
                break;
            }
        }

        if (address == null) fail("Could not get ipv6 address");

        byte[] linkLocal = LINK_LOCAL.getAddress();
        for (int i = length; i < IPV6_ADDR_BITS; i++) {
            address[i] = linkLocal[i];
        }

        return InetAddress.getByAddress(address);
    }

    private void sendRsPacket() throws Exception {
        ByteBuffer slla = LlaOption.build((byte) ICMPV6_ND_OPTION_SLLA, mMacAddr);
        ByteBuffer rs = Ipv6Utils.buildRsPacket(mMacAddr, mRouterMacAddr, (Inet6Address) LINK_LOCAL,
                IPV6_ADDR_ALL_NODES_MULTICAST, slla);

        sendPacket(rs);
    }

    private static <R> R expectPacket(TapPacketReader reader, Function<byte[], R> expect) throws Exception {
        byte[] packet;
        R result;
        while ((packet = reader.popPacket(PACKET_READ_TIMEOUT_MS)) != null) {
            result = expect.apply(packet);

            if (result != null) return result;

            // If packet is RA, obtain router mac address if mRouterMacAddr is null.
            //getRaPrefixOptions(packet);
        }

        return null;
    }

    public void sendPacket(ByteBuffer packet) throws Exception {
        mDownstreamReader.sendResponse(packet);
    }

    public <R> R expectReceive(Function<byte[], R> expect) throws Exception {
        return expectPacket(mDownstreamReader, expect);
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

        public <R> R expectReceive(Function<byte[], R> expect) throws Exception {
            byte[] packet;
            R result;
            while ((packet = mResponder.popPacket(PACKET_READ_TIMEOUT_MS)) != null) {
                result = expect.apply(packet);

                if (result != null) return result;
            }

            fail("RemoteResponder do not receive expected packet");
            return null;
        }
    }
}
