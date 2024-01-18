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

package android.net.thread;

import static android.net.thread.IntegrationTestUtils.isExpectedIcmpv6Packet;
import static android.net.thread.IntegrationTestUtils.isFromIpv6Source;
import static android.net.thread.IntegrationTestUtils.newPacketReader;
import static android.net.thread.IntegrationTestUtils.readPacketFrom;

import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ECHO_REPLY_TYPE;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ECHO_REQUEST_TYPE;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.net.InetAddresses;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.testutils.TapPacketReader;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.Inet6Address;

/** Integration test cases for Thread Border Routing feature. */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class MulticastBorderRoutingTest extends BorderRoutingBaseTest {
    private static final String GROUP_ADDR_SCOPE_5_STR = "ff05::1234";
    private static final String GROUP_ADDR_SCOPE_4_STR = "ff04::1234";
    private static final String GROUP_ADDR_SCOPE_3_STR = "ff03::1234";
    private static final Inet6Address GROUP_ADDR_SCOPE_5 =
            (Inet6Address) InetAddresses.parseNumericAddress(GROUP_ADDR_SCOPE_5_STR);
    private static final Inet6Address GROUP_ADDR_SCOPE_4 =
            (Inet6Address) InetAddresses.parseNumericAddress(GROUP_ADDR_SCOPE_4_STR);
    private static final Inet6Address GROUP_ADDR_SCOPE_3 =
            (Inet6Address) InetAddresses.parseNumericAddress(GROUP_ADDR_SCOPE_3_STR);

    @Before
    public void setUp() throws Exception {
        super.setUp();
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    public void inboundForwarding_infraDeviceCanPingFtdSubscribedAddress() throws Exception {
        /*
         * <pre>
         * Topology:
         *                 infra network                       Thread
         * infra device -------------------- Border Router -------------- Full Thread device
         *                                   (Cuttlefish)
         * </pre>
         */

        // BR forms a network.
        startBRLeader();

        // Creates a Full Thread Device (FTD) and lets it join the network.
        FullThreadDevice ftd = startFtdChild(6);
        // Subscribe to a scope 5 multicast address
        ftd.subscribeMulticastAddress(GROUP_ADDR_SCOPE_5_STR);
        Inet6Address ftdOmr = ftd.getOmrAddress();
        assertNotNull(ftdOmr);

        // Creates a infra network device.
        TapPacketReader infraNetworkReader =
                newPacketReader(mInfraNetworkTracker.getTestIface(), mHandler);
        InfraNetworkDevice infraDevice = startInfraDevice("1:2:3:4:5:6", infraNetworkReader);

        // Infra device sends an echo request to FTD's subscribed group address.
        infraDevice.sendEchoRequest(GROUP_ADDR_SCOPE_5);

        // Infra device receives an echo reply sent by FTD.
        assertNotNull(
                readPacketFrom(
                        infraNetworkReader,
                        p ->
                                isExpectedIcmpv6Packet(p, ICMPV6_ECHO_REPLY_TYPE)
                                        && isFromIpv6Source(p, ftdOmr)));

        // Infra device sends an echo request to FTD's not subscribed group address.
        infraDevice.sendEchoRequest(GROUP_ADDR_SCOPE_3);

        // Infra device doesn't receive an echo reply sent by FTD.
        assertNull(
                readPacketFrom(
                        infraNetworkReader,
                        p ->
                                isExpectedIcmpv6Packet(p, ICMPV6_ECHO_REPLY_TYPE)
                                        && isFromIpv6Source(p, ftdOmr)));

        // Infra device sends an echo request to FTD's not subscribed group address.
        infraDevice.sendEchoRequest(GROUP_ADDR_SCOPE_4);

        // Infra device doesn't receive an echo reply sent by FTD.
        assertNull(
                readPacketFrom(
                        infraNetworkReader,
                        p ->
                                isExpectedIcmpv6Packet(p, ICMPV6_ECHO_REPLY_TYPE)
                                        && isFromIpv6Source(p, ftdOmr)));
    }

    @Test
    public void outboundForwarding_forwardedToInfraLinkIfScopeLargerThan3() throws Exception {
        /*
         * <pre>
         * Topology:
         *                 infra network                       Thread
         * infra device -------------------- Border Router -------------- Full Thread device
         *                                   (Cuttlefish)
         * </pre>
         */

        // BR forms a network.
        startBRLeader();

        // Creates a Full Thread Device (FTD) and lets it join the network.
        FullThreadDevice ftd = startFtdChild(7);
        Inet6Address ftdOmr = ftd.getOmrAddress();
        assertNotNull(ftdOmr);

        // Creates a infra network device.
        TapPacketReader infraNetworkReader =
                newPacketReader(mInfraNetworkTracker.getTestIface(), mHandler);

        ftd.ping(GROUP_ADDR_SCOPE_5_STR);

        // Infra device receives an echo request sent by FTD.
        assertNotNull(
                readPacketFrom(
                        infraNetworkReader,
                        p ->
                                isExpectedIcmpv6Packet(p, ICMPV6_ECHO_REQUEST_TYPE)
                                        && isFromIpv6Source(p, ftdOmr)));

        ftd.ping(GROUP_ADDR_SCOPE_3_STR);

        // Infra device doesn't receive an echo request sent by FTD.
        assertNull(
                readPacketFrom(
                        infraNetworkReader,
                        p ->
                                isExpectedIcmpv6Packet(p, ICMPV6_ECHO_REQUEST_TYPE)
                                        && isFromIpv6Source(p, ftdOmr)));

        ftd.ping(GROUP_ADDR_SCOPE_4_STR);

        // Infra device doesn't receive an echo reply sent by FTD.
        assertNotNull(
                readPacketFrom(
                        infraNetworkReader,
                        p ->
                                isExpectedIcmpv6Packet(p, ICMPV6_ECHO_REQUEST_TYPE)
                                        && isFromIpv6Source(p, ftdOmr)));
    }

    @Test
    public void inboundForwarding_subscriptionRemainsAfterInfraNetworkSwitch() throws Exception {
        /*
         * <pre>
         * Topology:
         *                 infra network                       Thread
         * infra device -------------------- Border Router -------------- Full Thread device
         *                                   (Cuttlefish)
         * </pre>
         */

        // BR forms a network.
        startBRLeader();

        // Creates a Full Thread Device (FTD) and lets it join the network.
        FullThreadDevice ftd = startFtdChild(6);
        ftd.subscribeMulticastAddress(GROUP_ADDR_SCOPE_5_STR);
        Inet6Address ftdOmr = ftd.getOmrAddress();
        assertNotNull(ftdOmr);

        // Creates a infra network device.
        TapPacketReader infraNetworkReader =
                newPacketReader(mInfraNetworkTracker.getTestIface(), mHandler);
        InfraNetworkDevice infraDevice = startInfraDevice("1:2:3:4:5:6", infraNetworkReader);

        // Infra device sends an echo request to FTD's subscribed group address.
        infraDevice.sendEchoRequest(GROUP_ADDR_SCOPE_5);

        // Infra device receives an echo reply sent by FTD.
        assertNotNull(
                readPacketFrom(
                        infraNetworkReader,
                        p ->
                                isExpectedIcmpv6Packet(p, ICMPV6_ECHO_REPLY_TYPE)
                                        && isFromIpv6Source(p, ftdOmr)));

        // Destroy infra link and re-create
        tearDownInfraNetwork();
        setupInfraNetwork();

        infraNetworkReader = newPacketReader(mInfraNetworkTracker.getTestIface(), mHandler);
        infraDevice = startInfraDevice("1:2:3:4:5:6", infraNetworkReader);

        // Infra device sends an echo request to FTD's subscribed group address.
        infraDevice.sendEchoRequest(GROUP_ADDR_SCOPE_5);

        // Infra device receives an echo reply sent by FTD.
        assertNotNull(
                readPacketFrom(
                        infraNetworkReader,
                        p ->
                                isExpectedIcmpv6Packet(p, ICMPV6_ECHO_REPLY_TYPE)
                                        && isFromIpv6Source(p, ftdOmr)));
    }
}
