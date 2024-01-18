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

import static android.Manifest.permission.MANAGE_TEST_NETWORKS;
import static android.Manifest.permission.NETWORK_SETTINGS;
import static android.net.thread.IntegrationTestUtils.isExpectedIcmpv6Packet;
import static android.net.thread.IntegrationTestUtils.isFromIpv6Source;
import static android.net.thread.IntegrationTestUtils.isSimulatedThreadRadioSupported;
import static android.net.thread.IntegrationTestUtils.newPacketReader;
import static android.net.thread.IntegrationTestUtils.readPacketFrom;
import static android.net.thread.IntegrationTestUtils.waitFor;
import static android.net.thread.IntegrationTestUtils.waitForStateAnyOf;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_LEADER;
import static android.net.thread.ThreadNetworkManager.PERMISSION_THREAD_NETWORK_PRIVILEGED;

import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ECHO_REPLY_TYPE;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ECHO_REQUEST_TYPE;
import static com.android.testutils.TestNetworkTrackerKt.initTestNetwork;
import static com.android.testutils.TestPermissionUtil.runAsShell;

import static com.google.common.io.BaseEncoding.base16;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.net.InetAddresses;
import android.net.LinkProperties;
import android.net.MacAddress;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.testutils.TapPacketReader;
import com.android.testutils.TestNetworkTracker;

import com.google.common.util.concurrent.MoreExecutors;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.Inet6Address;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Integration test cases for Thread Border Routing feature. */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class BorderRoutingTest {
    private static final String TAG = BorderRoutingTest.class.getSimpleName();
    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final ThreadNetworkManager mThreadNetworkManager =
            mContext.getSystemService(ThreadNetworkManager.class);
    private ThreadNetworkController mThreadNetworkController;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private TestNetworkTracker mInfraNetworkTracker;

    private static final String GROUP_ADDR_SCOPE_5_STR = "ff05::1234";
    private static final String GROUP_ADDR_SCOPE_4_STR = "ff04::1234";
    private static final String GROUP_ADDR_SCOPE_3_STR = "ff03::1234";
    private static final Inet6Address GROUP_ADDR_SCOPE_5 =
            (Inet6Address) InetAddresses.parseNumericAddress(GROUP_ADDR_SCOPE_5_STR);
    private static final Inet6Address GROUP_ADDR_SCOPE_4 =
            (Inet6Address) InetAddresses.parseNumericAddress(GROUP_ADDR_SCOPE_4_STR);
    private static final Inet6Address GROUP_ADDR_SCOPE_3 =
            (Inet6Address) InetAddresses.parseNumericAddress(GROUP_ADDR_SCOPE_3_STR);

    // A valid Thread Active Operational Dataset generated from OpenThread CLI "dataset init new".
    private static final byte[] DEFAULT_DATASET_TLVS =
            base16().decode(
                            "0E080000000000010000000300001335060004001FFFE002"
                                    + "08ACC214689BC40BDF0708FD64DB1225F47E0B0510F26B31"
                                    + "53760F519A63BAFDDFFC80D2AF030F4F70656E5468726561"
                                    + "642D643961300102D9A00410A245479C836D551B9CA557F7"
                                    + "B9D351B40C0402A0FFF8");
    private static final ActiveOperationalDataset DEFAULT_DATASET =
            ActiveOperationalDataset.fromThreadTlvs(DEFAULT_DATASET_TLVS);

    private void setupInfraNetwork() {
        mInfraNetworkTracker =
                runAsShell(
                        MANAGE_TEST_NETWORKS,
                        () ->
                                initTestNetwork(
                                        mContext, new LinkProperties(), 5000 /* timeoutMs */));
        runAsShell(
                PERMISSION_THREAD_NETWORK_PRIVILEGED,
                NETWORK_SETTINGS,
                () -> {
                    CountDownLatch latch = new CountDownLatch(1);
                    mThreadNetworkController.setTestNetworkAsUpstream(
                            mInfraNetworkTracker.getTestIface().getInterfaceName(),
                            MoreExecutors.directExecutor(),
                            v -> {
                                latch.countDown();
                            });
                    latch.await();
                });
    }

    private void tearDownInfraNetwork() {
        runAsShell(MANAGE_TEST_NETWORKS, () -> mInfraNetworkTracker.teardown());
    }

    private void startBRLeader() throws Exception {
        runAsShell(
                PERMISSION_THREAD_NETWORK_PRIVILEGED,
                () -> {
                    mThreadNetworkController.join(
                            DEFAULT_DATASET, MoreExecutors.directExecutor(), result -> {});
                });
        waitForStateAnyOf(
                mThreadNetworkController, List.of(DEVICE_ROLE_LEADER), 30 /* timeoutSeconds */);
    }

    private FullThreadDevice startFtdChild(int nodeId) throws Exception {
        FullThreadDevice ftd = new FullThreadDevice(nodeId /* node ID */);
        ftd.factoryReset();
        ftd.joinNetwork(DEFAULT_DATASET);
        ftd.waitForStateAnyOf(List.of("router", "child"), 10 /* timeoutSeconds */);
        waitFor(() -> ftd.getOmrAddress() != null, 60 /* timeoutSeconds */);

        return ftd;
    }

    private InfraNetworkDevice startInfraDevice(String macAddress, TapPacketReader tapPacketReader)
            throws Exception {
        InfraNetworkDevice infraDevice =
                new InfraNetworkDevice(MacAddress.fromString(macAddress), tapPacketReader);
        infraDevice.runSlaac(60 /* timeoutSeconds */);
        assertNotNull(infraDevice.ipv6Addr);

        return infraDevice;
    }

    @Before
    public void setUp() throws Exception {
        mHandlerThread = new HandlerThread(getClass().getSimpleName());
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        var threadControllers = mThreadNetworkManager.getAllThreadNetworkControllers();
        assertEquals(threadControllers.size(), 1);
        mThreadNetworkController = threadControllers.get(0);
        setupInfraNetwork();
    }

    @After
    public void tearDown() throws Exception {
        runAsShell(
                PERMISSION_THREAD_NETWORK_PRIVILEGED,
                NETWORK_SETTINGS,
                () -> {
                    CountDownLatch latch = new CountDownLatch(2);
                    mThreadNetworkController.setTestNetworkAsUpstream(
                            null, MoreExecutors.directExecutor(), v -> latch.countDown());
                    mThreadNetworkController.leave(
                            MoreExecutors.directExecutor(), v -> latch.countDown());
                    latch.await(10, TimeUnit.SECONDS);
                });
        tearDownInfraNetwork();

        mHandlerThread.quitSafely();
        mHandlerThread.join();
    }

    @Test
    public void unicastRouting_infraDevicePingTheadDeviceOmr_replyReceived() throws Exception {
        assumeTrue(isSimulatedThreadRadioSupported());

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
        FullThreadDevice ftd = startFtdChild(5);
        Inet6Address ftdOmr = ftd.getOmrAddress();
        assertNotNull(ftdOmr);

        // Creates a infra network device.
        TapPacketReader infraNetworkReader =
                newPacketReader(mInfraNetworkTracker.getTestIface(), mHandler);
        InfraNetworkDevice infraDevice = startInfraDevice("1:2:3:4:5:6", infraNetworkReader);

        // Infra device sends an echo request to FTD's OMR.
        infraDevice.sendEchoRequest(ftdOmr);

        // Infra device receives an echo reply sent by FTD.
        assertNotNull(
                readPacketFrom(
                        infraNetworkReader,
                        p -> isExpectedIcmpv6Packet(p, ICMPV6_ECHO_REPLY_TYPE)));
    }

    @Test
    public void multicastRouting_inboundForwarding() throws Exception {
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
        Inet6Address ftdOmr = ftd.getOmrAddress();
        assertNotNull(ftdOmr);
        // Subscribe to a scope 5 multicast address
        ftd.subscribeMulticastAddress(GROUP_ADDR_SCOPE_5_STR);

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

        ftd.unsubscribeMulticastAddress(GROUP_ADDR_SCOPE_5_STR);
    }

    @Test
    public void multicastRouting_inboundForwarding_multipleEd() throws Exception {
        /*
         * <pre>
         * Topology:
         *                 infra network                       Thread
         * infra device -------------------- Border Router -------------- Full Thread device 1
         *                                   (Cuttlefish)
         *                                         |
         *                                         | Thread
         *                                         |
         *                                  Full Thread device 2
         * </pre>
         */

        // BR forms a network.
        startBRLeader();

        // Creates a Full Thread Device (FTD1) and lets it join the network.
        FullThreadDevice ftd1 = startFtdChild(6);
        Inet6Address ftdOmr1 = ftd1.getOmrAddress();
        assertNotNull(ftdOmr1);
        // Subscribe to a scope 5 multicast address
        ftd1.subscribeMulticastAddress(GROUP_ADDR_SCOPE_5_STR);

        // Creates a Full Thread Device (FTD2) and lets it join the network.
        FullThreadDevice ftd2 = startFtdChild(7);
        Inet6Address ftdOmr2 = ftd2.getOmrAddress();
        assertNotNull(ftdOmr2);
        // Subscribe to a scope 4 multicast address
        ftd2.subscribeMulticastAddress(GROUP_ADDR_SCOPE_4_STR);

        // Creates a infra network device.
        TapPacketReader infraNetworkReader =
                newPacketReader(mInfraNetworkTracker.getTestIface(), mHandler);
        InfraNetworkDevice infraDevice = startInfraDevice("1:2:3:4:5:6", infraNetworkReader);

        // Infra device sends an echo request to FTD1's subscribed group address.
        infraDevice.sendEchoRequest(GROUP_ADDR_SCOPE_5);

        // Infra device receives an echo reply sent by FTD1.
        assertNotNull(
                readPacketFrom(
                        infraNetworkReader,
                        p ->
                                isExpectedIcmpv6Packet(p, ICMPV6_ECHO_REPLY_TYPE)
                                        && isFromIpv6Source(p, ftdOmr1)));
        // FTD2 doesn't reply to the echo request
        assertNull(
                readPacketFrom(
                        infraNetworkReader,
                        p ->
                                isExpectedIcmpv6Packet(p, ICMPV6_ECHO_REPLY_TYPE)
                                        && isFromIpv6Source(p, ftdOmr2)));

        // Infra device sends an echo request to FTD2's subscribed group address.
        infraDevice.sendEchoRequest(GROUP_ADDR_SCOPE_4);
        // Infra device receives an echo reply sent by FTD2.
        assertNotNull(
                readPacketFrom(
                        infraNetworkReader,
                        p ->
                                isExpectedIcmpv6Packet(p, ICMPV6_ECHO_REPLY_TYPE)
                                        && isFromIpv6Source(p, ftdOmr2)));
        // FTD1 doesn't reply to the echo request
        assertNull(
                readPacketFrom(
                        infraNetworkReader,
                        p ->
                                isExpectedIcmpv6Packet(p, ICMPV6_ECHO_REPLY_TYPE)
                                        && isFromIpv6Source(p, ftdOmr1)));

        ftd1.unsubscribeMulticastAddress(GROUP_ADDR_SCOPE_5_STR);
        ftd2.unsubscribeMulticastAddress(GROUP_ADDR_SCOPE_4_STR);
    }

    @Test
    public void multicastRouting_outboundForwarding() throws Exception {
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

        ftd.ping(GROUP_ADDR_SCOPE_5_STR, null, 100, 1);

        // Infra device receives an echo request sent by FTD.
        assertNotNull(
                readPacketFrom(
                        infraNetworkReader,
                        p ->
                                isExpectedIcmpv6Packet(p, ICMPV6_ECHO_REQUEST_TYPE)
                                        && isFromIpv6Source(p, ftdOmr)));

        ftd.ping(GROUP_ADDR_SCOPE_3_STR, null, 100, 1);

        // Infra device doesn't receive an echo request sent by FTD.
        assertNull(
                readPacketFrom(
                        infraNetworkReader,
                        p ->
                                isExpectedIcmpv6Packet(p, ICMPV6_ECHO_REQUEST_TYPE)
                                        && isFromIpv6Source(p, ftdOmr)));

        ftd.ping(GROUP_ADDR_SCOPE_4_STR, null, 100, 1);

        // Infra device doesn't receive an echo reply sent by FTD.
        assertNotNull(
                readPacketFrom(
                        infraNetworkReader,
                        p ->
                                isExpectedIcmpv6Packet(p, ICMPV6_ECHO_REQUEST_TYPE)
                                        && isFromIpv6Source(p, ftdOmr)));
    }

    @Test
    public void multicastRouting_infraNetworkSwitch() throws Exception {
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

        ftd.unsubscribeMulticastAddress(GROUP_ADDR_SCOPE_5_STR);
    }
}
