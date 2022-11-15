/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.Manifest.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS;
import static android.Manifest.permission.DUMP;
import static android.Manifest.permission.MANAGE_TEST_NETWORKS;
import static android.Manifest.permission.NETWORK_SETTINGS;
import static android.Manifest.permission.TETHER_PRIVILEGED;
import static android.content.pm.PackageManager.FEATURE_WIFI;
import static android.net.InetAddresses.parseNumericAddress;
import static android.net.TetheringManager.CONNECTIVITY_SCOPE_GLOBAL;
import static android.net.TetheringManager.CONNECTIVITY_SCOPE_LOCAL;
import static android.net.TetheringManager.TETHERING_ETHERNET;
import static android.net.TetheringTester.TestDnsPacket;
import static android.net.TetheringTester.isExpectedIcmpPacket;
import static android.net.TetheringTester.isExpectedTcpPacket;
import static android.net.TetheringTester.isExpectedUdpDnsPacket;
import static android.net.TetheringTester.isExpectedUdpPacket;
import static android.system.OsConstants.ICMP_ECHO;
import static android.system.OsConstants.ICMP_ECHOREPLY;
import static android.system.OsConstants.IPPROTO_ICMP;
import static android.system.OsConstants.IPPROTO_IP;
import static android.system.OsConstants.IPPROTO_IPV6;
import static android.system.OsConstants.IPPROTO_TCP;
import static android.system.OsConstants.IPPROTO_UDP;

import static com.android.net.module.util.ConnectivityUtils.isIPv6ULA;
import static com.android.net.module.util.HexDump.dumpHexString;
import static com.android.net.module.util.IpUtils.icmpChecksum;
import static com.android.net.module.util.IpUtils.ipChecksum;
import static com.android.net.module.util.NetworkStackConstants.ETHER_TYPE_IPV4;
import static com.android.net.module.util.NetworkStackConstants.ETHER_TYPE_IPV6;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ECHO_REPLY_TYPE;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ECHO_REQUEST_TYPE;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ROUTER_ADVERTISEMENT;
import static com.android.net.module.util.NetworkStackConstants.ICMP_CHECKSUM_OFFSET;
import static com.android.net.module.util.NetworkStackConstants.IPV4_CHECKSUM_OFFSET;
import static com.android.net.module.util.NetworkStackConstants.IPV4_HEADER_MIN_LEN;
import static com.android.net.module.util.NetworkStackConstants.IPV4_LENGTH_OFFSET;
import static com.android.net.module.util.NetworkStackConstants.TCPHDR_ACK;
import static com.android.net.module.util.NetworkStackConstants.TCPHDR_SYN;
import static com.android.testutils.DeviceInfoUtils.KVersion;
import static com.android.testutils.TestNetworkTrackerKt.initTestNetwork;
import static com.android.testutils.TestPermissionUtil.runAsShell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.app.UiAutomation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.EthernetManager.TetheredInterfaceCallback;
import android.net.EthernetManager.TetheredInterfaceRequest;
import android.net.TetheringManager.StartTetheringCallback;
import android.net.TetheringManager.TetheringEventCallback;
import android.net.TetheringManager.TetheringRequest;
import android.net.TetheringTester.TetheredDevice;
import android.net.cts.util.CtsNetUtils;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.VintfRuntimeInfo;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.BpfDump;
import com.android.net.module.util.Ipv6Utils;
import com.android.net.module.util.PacketBuilder;
import com.android.net.module.util.Struct;
import com.android.net.module.util.bpf.Tether4Key;
import com.android.net.module.util.bpf.Tether4Value;
import com.android.net.module.util.bpf.TetherStatsKey;
import com.android.net.module.util.bpf.TetherStatsValue;
import com.android.net.module.util.structs.EthernetHeader;
import com.android.net.module.util.structs.Icmpv4Header;
import com.android.net.module.util.structs.Ipv4Header;
import com.android.net.module.util.structs.Ipv6Header;
import com.android.net.module.util.structs.UdpHeader;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import com.android.testutils.DeviceInfoUtils;
import com.android.testutils.DumpTestUtils;
import com.android.testutils.HandlerUtils;
import com.android.testutils.TapPacketReader;
import com.android.testutils.TestNetworkTracker;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileDescriptor;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class BpfEthernetTetheringTest extends EthernetTetheringTestBase {
    @Rule
    public final DevSdkIgnoreRule mIgnoreRule = new DevSdkIgnoreRule();

    /**
     * BPF offload IPv4 UDP tethering test. Verify that UDP tethered packets are offloaded by BPF.
     * Minimum test requirement:
     * 1. S+ device.
     * 2. Tethering config enables tethering BPF offload.
     * 3. Kernel supports IPv4 UDP BPF offload. See #isUdpOffloadSupportedByKernel.
     *
     * TODO: consider enabling the test even tethering config disables BPF offload. See b/238288883
     */
    @Test
    @IgnoreUpTo(Build.VERSION_CODES.R)
    public void testTetherUdpV4() throws Exception {
        assumeTrue("Tethering config disabled BPF offload", isTetherConfigBpfOffloadEnabled());
        assumeKernelSupportBpfOffloadUdpV4();

        final TetheringTester tester = initTetheringTester(toList(TEST_IP4_ADDR),
                toList(TEST_IP4_DNS));
        final TetheredDevice tethered = tester.createTetheredDevice(TEST_MAC, false /* hasIpv6 */);

        // TODO: remove the connectivity verification for upstream connected notification race.
        // Because async upstream connected notification can't guarantee the tethering routing is
        // ready to use. Need to test tethering connectivity before testing.
        // For short term plan, consider using IPv6 RA to get MAC address because the prefix comes
        // from upstream. That can guarantee that the routing is ready. Long term plan is that
        // refactors upstream connected notification from async to sync.
        probeV4TetheringConnectivity(tester, tethered, false /* is4To6 */);

        final MacAddress srcMac = tethered.macAddr;
        final MacAddress dstMac = tethered.routerMacAddr;
        final InetAddress remoteIp = REMOTE_IP4_ADDR;
        final InetAddress tetheringUpstreamIp = TEST_IP4_ADDR.getAddress();
        final InetAddress clientIp = tethered.ipv4Addr;
        sendUploadPacketUdp(srcMac, dstMac, clientIp, remoteIp, tester, false /* is4To6 */);
        sendDownloadPacketUdp(remoteIp, tetheringUpstreamIp, tester, false /* is6To4 */);

        // Send second UDP packet in original direction.
        // The BPF coordinator only offloads the ASSURED conntrack entry. The "request + reply"
        // packets can make status IPS_SEEN_REPLY to be set. Need one more packet to make
        // conntrack status IPS_ASSURED_BIT to be set. Note the third packet needs to delay
        // 2 seconds because kernel monitors a UDP connection which still alive after 2 seconds
        // and apply ASSURED flag.
        // See kernel upstream commit b7b1d02fc43925a4d569ec221715db2dfa1ce4f5 and
        // nf_conntrack_udp_packet in net/netfilter/nf_conntrack_proto_udp.c
        Thread.sleep(UDP_STREAM_TS_MS);
        sendUploadPacketUdp(srcMac, dstMac, clientIp, remoteIp, tester, false /* is4To6 */);

        // Give a slack time for handling conntrack event in user space.
        Thread.sleep(UDP_STREAM_SLACK_MS);

        // [1] Verify IPv4 upstream rule map.
        final HashMap<Tether4Key, Tether4Value> upstreamMap = pollRawMapFromDump(
                Tether4Key.class, Tether4Value.class, DUMPSYS_RAWMAP_ARG_UPSTREAM4);
        assertNotNull(upstreamMap);
        assertEquals(1, upstreamMap.size());

        final Map.Entry<Tether4Key, Tether4Value> rule =
                upstreamMap.entrySet().iterator().next();

        final Tether4Key upstream4Key = rule.getKey();
        assertEquals(IPPROTO_UDP, upstream4Key.l4proto);
        assertTrue(Arrays.equals(tethered.ipv4Addr.getAddress(), upstream4Key.src4));
        assertEquals(LOCAL_PORT, upstream4Key.srcPort);
        assertTrue(Arrays.equals(REMOTE_IP4_ADDR.getAddress(), upstream4Key.dst4));
        assertEquals(REMOTE_PORT, upstream4Key.dstPort);

        final Tether4Value upstream4Value = rule.getValue();
        assertTrue(Arrays.equals(tetheringUpstreamIp.getAddress(),
                InetAddress.getByAddress(upstream4Value.src46).getAddress()));
        assertEquals(LOCAL_PORT, upstream4Value.srcPort);
        assertTrue(Arrays.equals(REMOTE_IP4_ADDR.getAddress(),
                InetAddress.getByAddress(upstream4Value.dst46).getAddress()));
        assertEquals(REMOTE_PORT, upstream4Value.dstPort);

        // [2] Verify stats map.
        // Transmit packets on both direction for verifying stats. Because we only care the
        // packet count in stats test, we just reuse the existing packets to increaes
        // the packet count on both direction.

        // Send packets on original direction.
        for (int i = 0; i < TX_UDP_PACKET_COUNT; i++) {
            sendUploadPacketUdp(srcMac, dstMac, clientIp, remoteIp, tester,
                    false /* is4To6 */);
        }

        // Send packets on reply direction.
        for (int i = 0; i < RX_UDP_PACKET_COUNT; i++) {
            sendDownloadPacketUdp(remoteIp, tetheringUpstreamIp, tester, false /* is6To4 */);
        }

        // Dump stats map to verify.
        final HashMap<TetherStatsKey, TetherStatsValue> statsMap = pollRawMapFromDump(
                TetherStatsKey.class, TetherStatsValue.class, DUMPSYS_RAWMAP_ARG_STATS);
        assertNotNull(statsMap);
        assertEquals(1, statsMap.size());

        final Map.Entry<TetherStatsKey, TetherStatsValue> stats =
                statsMap.entrySet().iterator().next();

        // TODO: verify the upstream index in TetherStatsKey.

        final TetherStatsValue statsValue = stats.getValue();
        assertEquals(RX_UDP_PACKET_COUNT, statsValue.rxPackets);
        assertEquals(RX_UDP_PACKET_COUNT * RX_UDP_PACKET_SIZE, statsValue.rxBytes);
        assertEquals(0, statsValue.rxErrors);
        assertEquals(TX_UDP_PACKET_COUNT, statsValue.txPackets);
        assertEquals(TX_UDP_PACKET_COUNT * TX_UDP_PACKET_SIZE, statsValue.txBytes);
        assertEquals(0, statsValue.txErrors);
    }
}
