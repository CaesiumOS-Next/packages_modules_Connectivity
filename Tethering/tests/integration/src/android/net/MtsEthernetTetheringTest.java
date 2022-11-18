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

package android.net;

import static android.Manifest.permission.DUMP;
import static android.system.OsConstants.IPPROTO_UDP;

import static com.android.testutils.DeviceInfoUtils.KVersion;
import static com.android.testutils.TestPermissionUtil.runAsShell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.net.TetheringTester.TetheredDevice;
import android.os.Build;
import android.os.VintfRuntimeInfo;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.net.module.util.BpfDump;
import com.android.net.module.util.Struct;
import com.android.net.module.util.bpf.Tether4Key;
import com.android.net.module.util.bpf.Tether4Value;
import com.android.net.module.util.bpf.TetherStatsKey;
import com.android.net.module.util.bpf.TetherStatsValue;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import com.android.testutils.DeviceInfoUtils;
import com.android.testutils.DumpTestUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class MtsEthernetTetheringTest extends EthernetTetheringTestBase {
    @Rule
    public final DevSdkIgnoreRule mIgnoreRule = new DevSdkIgnoreRule();

    private static final String TAG = MtsEthernetTetheringTest.class.getSimpleName();

    private static final int DUMP_POLLING_MAX_RETRY = 100;
    private static final int DUMP_POLLING_INTERVAL_MS = 50;
    // Kernel treats a confirmed UDP connection which active after two seconds as stream mode.
    // See upstream commit b7b1d02fc43925a4d569ec221715db2dfa1ce4f5.
    private static final int UDP_STREAM_TS_MS = 2000;
    // Give slack time for waiting UDP stream mode because handling conntrack event in user space
    // may not in precise time. Used to reduce the flaky rate.
    private static final int UDP_STREAM_SLACK_MS = 500;
    // Per RX UDP packet size: iphdr (20) + udphdr (8) + payload (2) = 30 bytes.
    private static final int RX_UDP_PACKET_SIZE = 30;
    private static final int RX_UDP_PACKET_COUNT = 456;
    // Per TX UDP packet size: ethhdr (14) + iphdr (20) + udphdr (8) + payload (2) = 44 bytes.
    private static final int TX_UDP_PACKET_SIZE = 44;
    private static final int TX_UDP_PACKET_COUNT = 123;
    // Give slack time for waiting neighbor event because handling neighbor event in user space
    // may not in precise time. Used to reduce the flaky rate.
    protected static final int NEIGHBOR_EVENT_SLACK_MS = 500;

    private static final String DUMPSYS_TETHERING_RAWMAP_ARG = "bpfRawMap";
    private static final String DUMPSYS_RAWMAP_ARG_STATS = "--stats";
    private static final String DUMPSYS_RAWMAP_ARG_UPSTREAM4 = "--upstream4";
    private static final String LINE_DELIMITER = "\\n";

    private static boolean isUdpOffloadSupportedByKernel(final String kernelVersion) {
        final KVersion current = DeviceInfoUtils.getMajorMinorSubminorVersion(kernelVersion);
        return current.isInRange(new KVersion(4, 14, 222), new KVersion(4, 19, 0))
                || current.isInRange(new KVersion(4, 19, 176), new KVersion(5, 4, 0))
                || current.isAtLeast(new KVersion(5, 4, 98));
    }

    @Test
    public void testIsUdpOffloadSupportedByKernel() throws Exception {
        assertFalse(isUdpOffloadSupportedByKernel("4.14.221"));
        assertTrue(isUdpOffloadSupportedByKernel("4.14.222"));
        assertTrue(isUdpOffloadSupportedByKernel("4.16.0"));
        assertTrue(isUdpOffloadSupportedByKernel("4.18.0"));
        assertFalse(isUdpOffloadSupportedByKernel("4.19.0"));

        assertFalse(isUdpOffloadSupportedByKernel("4.19.175"));
        assertTrue(isUdpOffloadSupportedByKernel("4.19.176"));
        assertTrue(isUdpOffloadSupportedByKernel("5.2.0"));
        assertTrue(isUdpOffloadSupportedByKernel("5.3.0"));
        assertFalse(isUdpOffloadSupportedByKernel("5.4.0"));

        assertFalse(isUdpOffloadSupportedByKernel("5.4.97"));
        assertTrue(isUdpOffloadSupportedByKernel("5.4.98"));
        assertTrue(isUdpOffloadSupportedByKernel("5.10.0"));
    }

    private static void assumeKernelSupportBpfOffloadUdpV4() {
        final String kernelVersion = VintfRuntimeInfo.getKernelVersion();
        assumeTrue("Kernel version " + kernelVersion + " doesn't support IPv4 UDP BPF offload",
                isUdpOffloadSupportedByKernel(kernelVersion));
    }

    @Test
    public void testKernelSupportBpfOffloadUdpV4() throws Exception {
        assumeKernelSupportBpfOffloadUdpV4();
    }

    private boolean isTetherConfigBpfOffloadEnabled() throws Exception {
        final String dumpStr = runAsShell(DUMP, () ->
                DumpTestUtils.dumpService(Context.TETHERING_SERVICE, "--short"));

        // BPF offload tether config can be overridden by "config_tether_enable_bpf_offload" in
        // packages/modules/Connectivity/Tethering/res/values/config.xml. OEM may disable config by
        // RRO to override the enabled default value. Get the tethering config via dumpsys.
        // $ dumpsys tethering
        //   mIsBpfEnabled: true
        boolean enabled = dumpStr.contains("mIsBpfEnabled: true");
        if (!enabled) {
            Log.d(TAG, "BPF offload tether config not enabled: " + dumpStr);
        }
        return enabled;
    }

    @Test
    public void testTetherConfigBpfOffloadEnabled() throws Exception {
        assumeTrue(isTetherConfigBpfOffloadEnabled());
    }

    @NonNull
    private <K extends Struct, V extends Struct> HashMap<K, V> dumpAndParseRawMap(
            Class<K> keyClass, Class<V> valueClass, @NonNull String mapArg)
            throws Exception {
        final String[] args = new String[] {DUMPSYS_TETHERING_RAWMAP_ARG, mapArg};
        final String rawMapStr = runAsShell(DUMP, () ->
                DumpTestUtils.dumpService(Context.TETHERING_SERVICE, args));
        final HashMap<K, V> map = new HashMap<>();

        for (final String line : rawMapStr.split(LINE_DELIMITER)) {
            final Pair<K, V> rule =
                    BpfDump.fromBase64EncodedString(keyClass, valueClass, line.trim());
            map.put(rule.first, rule.second);
        }
        return map;
    }

    @Nullable
    private <K extends Struct, V extends Struct> HashMap<K, V> pollRawMapFromDump(
            Class<K> keyClass, Class<V> valueClass, @NonNull String mapArg)
            throws Exception {
        for (int retryCount = 0; retryCount < DUMP_POLLING_MAX_RETRY; retryCount++) {
            final HashMap<K, V> map = dumpAndParseRawMap(keyClass, valueClass, mapArg);
            if (!map.isEmpty()) return map;

            Thread.sleep(DUMP_POLLING_INTERVAL_MS);
        }

        fail("Cannot get rules after " + DUMP_POLLING_MAX_RETRY * DUMP_POLLING_INTERVAL_MS + "ms");
        return null;
    }

    // Get BPF offload stats value. Expect only one stats entry because test environment has only
    // one upstream.
    // TODO: verify the upstream index in TetherStatsKey.
    private TetherStatsValue getBpfStatsValue() throws Exception {
        final HashMap<TetherStatsKey, TetherStatsValue> statsMap = pollRawMapFromDump(
                TetherStatsKey.class, TetherStatsValue.class, DUMPSYS_RAWMAP_ARG_STATS);
        assertNotNull(statsMap);
        assertEquals(1, statsMap.size());
        final Map.Entry<TetherStatsKey, TetherStatsValue> stats =
                statsMap.entrySet().iterator().next();
        return stats.getValue();
    }

    // Test network topology:
    //
    //         public network (rawip)                 private network
    //                   |                 UE                |
    // +------------+    V    +------------+------------+    V    +------------+
    // |   Sever    +---------+  Upstream  | Downstream +---------+   Client   |
    // +------------+         +------------+------------+         +------------+
    // remote ip              public ip                           private ip
    // 8.8.8.8:443            <Upstream ip>:9876                  <TetheredDevice ip>:9876
    //
    private void runUdp4Test() throws Exception {
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
        final TetherStatsValue statsValue = getBpfStatsValue();
        assertEquals(RX_UDP_PACKET_COUNT, statsValue.rxPackets);
        assertEquals(RX_UDP_PACKET_COUNT * RX_UDP_PACKET_SIZE, statsValue.rxBytes);
        assertEquals(0, statsValue.rxErrors);
        assertEquals(TX_UDP_PACKET_COUNT, statsValue.txPackets);
        assertEquals(TX_UDP_PACKET_COUNT * TX_UDP_PACKET_SIZE, statsValue.txBytes);
        assertEquals(0, statsValue.txErrors);
    }

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
    public void testTetherBpfOffloadUdpV4() throws Exception {
        assumeTrue("Tethering config disabled BPF offload", isTetherConfigBpfOffloadEnabled());
        assumeKernelSupportBpfOffloadUdpV4();

        runUdp4Test();
    }

    private void triggerNeighborUnreachabilityDetection(final InetAddress downloadSrcIp,
            final InetAddress downloadDstIp, final TetheringTester tester) throws Exception {
        // Send a probe IPv6 UDP packet to trigger neighbor event for building offload rules.
        // This probe won't be counted in BPF stats map because the offload rules have not been
        // built by neighbor event.
        // TODO: consider doing neighbor unreachability detection manually in TetheringTester.
        sendDownloadPacketUdp(downloadSrcIp, downloadDstIp, tester, false /* is6To4 */);

        // Wait for neighbor event and the offload rules are built by BpfCoordinator. Avoid the
        // test to be flaky.
        Thread.sleep(NEIGHBOR_EVENT_SLACK_MS);
    }

    private void runTcp6Test() throws Exception {
        final TetheringTester tester = initTetheringTester(toList(TEST_IP6_ADDR),
                toList(TEST_IP6_DNS));
        final TetheredDevice tethered = tester.createTetheredDevice(TEST_MAC, true /* hasIpv6 */);
        triggerNeighborUnreachabilityDetection(REMOTE_IP6_ADDR /* downloadSrcIp */,
                tethered.ipv6Addr /* downloadDstIp */, tester);

        // Expect stats is zero before the test. Because IPv6 offload rules are created by
        // interface, expect there is no other offload traffic on test network upstream before
        // testing.
        TetherStatsValue statsValue = getBpfStatsValue();
        assertEquals(0, statsValue.rxPackets);
        assertEquals(0, statsValue.rxBytes);
        assertEquals(0, statsValue.rxErrors);
        assertEquals(0, statsValue.txPackets);
        assertEquals(0, statsValue.txBytes);
        assertEquals(0, statsValue.txErrors);

        // EthernetTetheringTestBase#runTcpTest sends TCP packets in the following flow.
        //
        // Server (base seq = 2000)                                  Client (base seq = 1000)
        //   |                                                          |
        //   |    [1] [SYN] SEQ = 1000                                  |
        //   |<---------------------------------------------------------|  -
        //   |                                                          |  ^
        //   |    [2] [SYN + ACK] SEQ = 2000, ACK = 1000+1              |  |
        //   |--------------------------------------------------------->|  three way handshake
        //   |                                                          |  |
        //   |    [3] [ACK] SEQ = 1001, ACK = 2000+1                    |  v
        //   |<---------------------------------------------------------|  -
        //   |                                                          |  ^
        //   |    [4] [ACK] SEQ = 1001, ACK = 2001, 2 byte payload      |  |
        //   |<---------------------------------------------------------|  data transfer
        //   |                                                          |  |
        //   |    [5] [ACK] SEQ = 2001, ACK = 1001+2, 2 byte payload    |  v
        //   |--------------------------------------------------------->|  -
        //   |                                                          |
        //
        // Because the syn packets [1][2] weren't offloaded by BPF, they weren't accounted by BPF
        // stats map. The packets [3][4][5] were offloaded and accounted by BPF stats.
        // See do_forward6() in packages/modules/Connectivity/bpf_progs/offload.c
        //
        runTcpTest(tethered.macAddr /* uploadSrcMac */, tethered.routerMacAddr /* uploadDstMac */,
                tethered.ipv6Addr /* uploadSrcIp */, REMOTE_IP6_ADDR /* uploadDstIp */,
                REMOTE_IP6_ADDR /* downloadSrcIp */, tethered.ipv6Addr /* downloadDstIp */,
                tester, false /* isClat */);
        statsValue = getBpfStatsValue();

        // RX stats were accounted from the packet [5].
        // Packet [3]: ip6hdr (40) + tcphdr (20) + payload (2) = 62 bytes.
        assertEquals(1, statsValue.rxPackets);
        assertEquals(62, statsValue.rxBytes);
        assertEquals(0, statsValue.rxErrors);

        // TX stats were accounted from the packet [3] + [4].
        // Packet [4]: ethhdr (14) + ip6hdr (40) + tcphdr (20) = 74 bytes.
        // Packet [5]: ethhdr (14) + ip6hdr (40) + tcphdr (20) + payload (2) = 76 bytes.
        assertEquals(2, statsValue.txPackets);
        assertEquals(150 /* 74 + 76 */, statsValue.txBytes);
        assertEquals(0, statsValue.txErrors);

        // TODO: verify BPF offload rules.
    }

    /**
     * BPF offload IPv6 TCP tethering test. Verify that TCP tethered packets are offloaded by BPF.
     * Minimum test requirement:
     * 1. S+ device.
     * 2. Tethering config enables tethering BPF offload.
     *
     * TODO: consider enabling the test even tethering config disables BPF offload. See b/238288883
     */
    @Test
    @IgnoreUpTo(Build.VERSION_CODES.R)
    public void testTetherBpfOffloadTcpV6() throws Exception {
        assumeTrue("Tethering config disabled BPF offload", isTetherConfigBpfOffloadEnabled());

        runTcp6Test();
    }
}
