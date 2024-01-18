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
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_LEADER;
import static android.net.thread.ThreadNetworkManager.PERMISSION_THREAD_NETWORK_PRIVILEGED;
import static android.net.thread.utils.IntegrationTestUtils.JOIN_TIMEOUT;
import static android.net.thread.utils.IntegrationTestUtils.RESTART_JOIN_TIMEOUT;
import static android.net.thread.utils.IntegrationTestUtils.isExpectedIcmpv6Packet;
import static android.net.thread.utils.IntegrationTestUtils.isFromIpv6Source;
import static android.net.thread.utils.IntegrationTestUtils.isInMulticastGroup;
import static android.net.thread.utils.IntegrationTestUtils.isSimulatedThreadRadioSupported;
import static android.net.thread.utils.IntegrationTestUtils.newPacketReader;
import static android.net.thread.utils.IntegrationTestUtils.readPacketFrom;
import static android.net.thread.utils.IntegrationTestUtils.sendUdpMessage;
import static android.net.thread.utils.IntegrationTestUtils.waitFor;
import static android.net.thread.utils.IntegrationTestUtils.waitForStateAnyOf;

import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ECHO_REPLY_TYPE;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ECHO_REQUEST_TYPE;
import static com.android.testutils.DeviceInfoUtils.isKernelVersionAtLeast;
import static com.android.testutils.TestNetworkTrackerKt.initTestNetwork;
import static com.android.testutils.TestPermissionUtil.runAsShell;

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assume.assumeNotNull;
import static org.junit.Assume.assumeTrue;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import android.net.InetAddresses;
import android.net.LinkProperties;
import android.net.MacAddress;
import android.net.thread.utils.FullThreadDevice;
import android.net.thread.utils.InfraNetworkDevice;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.testutils.TapPacketReader;
import com.android.testutils.TestNetworkTracker;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.Inet6Address;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Integration test cases for Thread Border Routing feature. */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class BorderRoutingTest {
    private static final String TAG = BorderRoutingTest.class.getSimpleName();
    private final Context mContext = ApplicationProvider.getApplicationContext();
    private ThreadNetworkController mController;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private TestNetworkTracker mInfraNetworkTracker;
    private List<FullThreadDevice> mFtds;
    private TapPacketReader mInfraNetworkReader;
    private InfraNetworkDevice mInfraDevice;

    private static final int NUM_FTD = 2;
    private static final String KERNEL_VERSION_MULTICAST_ROUTING_SUPPORTED = "5.15.0";
    private static final Inet6Address GROUP_ADDR_SCOPE_5 =
            (Inet6Address) InetAddresses.parseNumericAddress("ff05::1234");
    private static final Inet6Address GROUP_ADDR_SCOPE_4 =
            (Inet6Address) InetAddresses.parseNumericAddress("ff04::1234");
    private static final Inet6Address GROUP_ADDR_SCOPE_3 =
            (Inet6Address) InetAddresses.parseNumericAddress("ff03::1234");

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

    @Before
    public void setUp() throws Exception {
        assumeTrue(isSimulatedThreadRadioSupported());
        final ThreadNetworkManager manager = mContext.getSystemService(ThreadNetworkManager.class);
        if (manager != null) {
            mController = manager.getAllThreadNetworkControllers().get(0);
        }

        // Run the tests on only devices where the Thread feature is available
        assumeNotNull(mController);

        mHandlerThread = new HandlerThread(getClass().getSimpleName());
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mFtds = new ArrayList<>();

        setUpInfraNetwork();

        // BR forms a network.
        startBrLeader();

        // Creates a infra network device.
        mInfraNetworkReader = newPacketReader(mInfraNetworkTracker.getTestIface(), mHandler);
        mInfraDevice = startInfraDevice("1:2:3:4:5:6", mInfraNetworkReader);

        // Create Ftds
        for (int i = 0; i < NUM_FTD; ++i) {
            mFtds.add(new FullThreadDevice(15 + i /* node ID */));
        }
    }

    @After
    public void tearDown() throws Exception {
        if (mController == null) {
            return;
        }

        runAsShell(
                PERMISSION_THREAD_NETWORK_PRIVILEGED,
                NETWORK_SETTINGS,
                () -> {
                    CountDownLatch latch = new CountDownLatch(2);
                    mController.setTestNetworkAsUpstream(
                            null, directExecutor(), v -> latch.countDown());
                    mController.leave(directExecutor(), v -> latch.countDown());
                    latch.await(10, TimeUnit.SECONDS);
                });
        tearDownInfraNetwork();

        mHandlerThread.quitSafely();
        mHandlerThread.join();

        for (var ftd : mFtds) {
            ftd.destroy();
        }
        mFtds.clear();
    }

    @Test
    public void unicastRouting_infraDevicePingTheadDeviceOmr_replyReceived() throws Exception {
        /*
         * <pre>
         * Topology:
         *                 infra network                       Thread
         * infra device -------------------- Border Router -------------- Full Thread device
         *                                   (Cuttlefish)
         * </pre>
         */

        // Let ftd join the network.
        FullThreadDevice ftd = mFtds.get(0);
        startFtdChild(ftd);

        // Infra device sends an echo request to FTD's OMR.
        mInfraDevice.sendEchoRequest(ftd.getOmrAddress());

        // Infra device receives an echo reply sent by FTD.
        assertNotNull(readPacket(ICMPV6_ECHO_REPLY_TYPE, null /* srcAddress */));
    }

    @Test
    public void unicastRouting_borderRouterSendsUdpToThreadDevice_datagramReceived()
            throws Exception {
        assumeTrue(isSimulatedThreadRadioSupported());

        /*
         * <pre>
         * Topology:
         *                   Thread
         * Border Router -------------- Full Thread device
         *  (Cuttlefish)
         * </pre>
         */

        // BR forms a network.
        CompletableFuture<Void> joinFuture = new CompletableFuture<>();
        runAsShell(
                PERMISSION_THREAD_NETWORK_PRIVILEGED,
                () -> mController.join(DEFAULT_DATASET, directExecutor(), joinFuture::complete));
        joinFuture.get(RESTART_JOIN_TIMEOUT.toMillis(), MILLISECONDS);

        // Creates a Full Thread Device (FTD) and lets it join the network.
        FullThreadDevice ftd = mFtds.get(0);
        startFtdChild(ftd);
        Inet6Address ftdOmr = ftd.getOmrAddress();
        Inet6Address ftdMlEid = ftd.getMlEid();
        assertNotNull(ftdMlEid);

        ftd.udpBind(ftdOmr, 12345);
        sendUdpMessage(ftdOmr, 12345, "aaaaaaaa");
        assertEquals("aaaaaaaa", ftd.udpReceive());

        ftd.udpBind(ftdMlEid, 12345);
        sendUdpMessage(ftdMlEid, 12345, "bbbbbbbb");
        assertEquals("bbbbbbbb", ftd.udpReceive());
    }

    @Test
    public void multicastRouting_ftdSubscribedMulticastAddress_infraLinkJoinsMulticastGroup()
            throws Exception {
        assumeTrue(isKernelVersionAtLeast(KERNEL_VERSION_MULTICAST_ROUTING_SUPPORTED));
        /*
         * <pre>
         * Topology:
         *                 infra network                       Thread
         * infra device -------------------- Border Router -------------- Full Thread device
         *                                   (Cuttlefish)
         * </pre>
         */

        // Let ftd join the network.
        FullThreadDevice ftd = mFtds.get(0);
        startFtdChild(ftd);

        // Subscribe to a scope 5 multicast address
        ftd.subscribeMulticastAddress(GROUP_ADDR_SCOPE_5);

        assertInfraLinkMemberOfGroup(GROUP_ADDR_SCOPE_5);
    }

    @Test
    public void
            multicastRouting_ftdSubscribedScope3MulticastAddress_infraLinkNotJoinMulticastGroup()
                    throws Exception {
        assumeTrue(isKernelVersionAtLeast(KERNEL_VERSION_MULTICAST_ROUTING_SUPPORTED));
        /*
         * <pre>
         * Topology:
         *                 infra network                       Thread
         * infra device -------------------- Border Router -------------- Full Thread device
         *                                   (Cuttlefish)
         * </pre>
         */

        // Let ftd join the network.
        FullThreadDevice ftd = mFtds.get(0);
        startFtdChild(ftd);
        Inet6Address ftdOmr = ftd.getOmrAddress();

        // Subscribe to a scope 5 multicast address
        ftd.subscribeMulticastAddress(GROUP_ADDR_SCOPE_3);

        assertInfraLinkNotMemberOfGroup(GROUP_ADDR_SCOPE_3);
    }

    @Test
    public void multicastRouting_ftdSubscribedMulticastAddress_canPingfromInfraLink()
            throws Exception {
        assumeTrue(isKernelVersionAtLeast(KERNEL_VERSION_MULTICAST_ROUTING_SUPPORTED));
        /*
         * <pre>
         * Topology:
         *                 infra network                       Thread
         * infra device -------------------- Border Router -------------- Full Thread device
         *                                   (Cuttlefish)
         * </pre>
         */

        // Let ftd join the network.
        FullThreadDevice ftd = mFtds.get(0);
        startFtdChild(ftd);

        // Subscribe to a scope 5 multicast address
        subscribeMulticastAddressAndWait(ftd, GROUP_ADDR_SCOPE_5);

        // Infra device sends an echo request to FTD's subscribed group address.
        mInfraDevice.sendEchoRequest(GROUP_ADDR_SCOPE_5);
        // Infra device receives an echo reply sent by FTD.
        assertNotNull(readPacket(ICMPV6_ECHO_REPLY_TYPE, ftd.getOmrAddress()));
    }

    @Test
    public void multicastRouting_ftdSubscribedScope3MulticastAddress_cannotPingfromInfraLink()
            throws Exception {
        assumeTrue(isKernelVersionAtLeast(KERNEL_VERSION_MULTICAST_ROUTING_SUPPORTED));
        /*
         * <pre>
         * Topology:
         *                 infra network                       Thread
         * infra device -------------------- Border Router -------------- Full Thread device
         *                                   (Cuttlefish)
         * </pre>
         */

        // Let ftd join the network.
        FullThreadDevice ftd = mFtds.get(0);
        startFtdChild(ftd);

        // Subscribe to a scope 3 multicast address
        ftd.subscribeMulticastAddress(GROUP_ADDR_SCOPE_3);

        // Infra device sends an echo request to FTD's subscribed group address.
        mInfraDevice.sendEchoRequest(GROUP_ADDR_SCOPE_3);
        // Infra device does not receive an echo reply sent by FTD.
        assertNull(readPacket(ICMPV6_ECHO_REPLY_TYPE, ftd.getOmrAddress()));
    }

    @Test
    public void multicastRouting_ftdNotSubscribedMulticastAddress_cannotPingFromInfraDevice()
            throws Exception {
        assumeTrue(isKernelVersionAtLeast(KERNEL_VERSION_MULTICAST_ROUTING_SUPPORTED));
        /*
         * <pre>
         * Topology:
         *                 infra network                       Thread
         * infra device -------------------- Border Router -------------- Full Thread device
         *                                   (Cuttlefish)
         * </pre>
         */

        // Let ftd join the network.
        FullThreadDevice ftd = mFtds.get(0);
        startFtdChild(ftd);
        Inet6Address ftdOmr = ftd.getOmrAddress();

        // Infra device sends an echo request to FTD's not subscribed group address.
        mInfraDevice.sendEchoRequest(GROUP_ADDR_SCOPE_4);
        // Infra device doesn't receive an echo reply sent by FTD.
        assertNull(readPacket(ICMPV6_ECHO_REPLY_TYPE, ftdOmr));
    }

    @Test
    public void multicastRouting_multipleFtdsSubscribedMulticastAddresses_canPingFromInfraDevice()
            throws Exception {
        assumeTrue(isKernelVersionAtLeast(KERNEL_VERSION_MULTICAST_ROUTING_SUPPORTED));
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

        // Let ftd1 join the network.
        FullThreadDevice ftd1 = mFtds.get(0);
        startFtdChild(ftd1);
        Inet6Address ftdOmr1 = ftd1.getOmrAddress();
        // Subscribe to a scope 5 multicast address
        subscribeMulticastAddressAndWait(ftd1, GROUP_ADDR_SCOPE_5);
        // Let ftd2 join the network.
        FullThreadDevice ftd2 = mFtds.get(1);
        startFtdChild(ftd2);
        Inet6Address ftdOmr2 = ftd2.getOmrAddress();
        // Subscribe to a scope 4 multicast address
        subscribeMulticastAddressAndWait(ftd2, GROUP_ADDR_SCOPE_4);

        // Infra device sends an echo request to FTD1's subscribed group address.
        mInfraDevice.sendEchoRequest(GROUP_ADDR_SCOPE_5);
        // Infra device receives an echo reply sent by FTD1.
        assertNotNull(readPacket(ICMPV6_ECHO_REPLY_TYPE, ftdOmr1));

        // Infra device sends an echo request to FTD2's subscribed group address.
        mInfraDevice.sendEchoRequest(GROUP_ADDR_SCOPE_4);
        // Infra device receives an echo reply sent by FTD2.
        assertNotNull(readPacket(ICMPV6_ECHO_REPLY_TYPE, ftdOmr2));
    }

    @Test
    public void multicastRouting_outboundForwarding_scopeLargerThan3IsForwarded() throws Exception {
        assumeTrue(isKernelVersionAtLeast(KERNEL_VERSION_MULTICAST_ROUTING_SUPPORTED));
        /*
         * <pre>
         * Topology:
         *                 infra network                       Thread
         * infra device -------------------- Border Router -------------- Full Thread device
         *                                   (Cuttlefish)
         * </pre>
         */

        // Let ftd join the network.
        FullThreadDevice ftd = mFtds.get(0);
        startFtdChild(ftd);
        Inet6Address ftdOmr = ftd.getOmrAddress();

        ftd.ping(GROUP_ADDR_SCOPE_5);
        ftd.ping(GROUP_ADDR_SCOPE_4);

        // Infra device receives an echo request sent by FTD.
        assertNotNull(readPacket(ICMPV6_ECHO_REQUEST_TYPE, ftdOmr));
        // Infra device doesn't receive an echo reply sent by FTD.
        assertNotNull(readPacket(ICMPV6_ECHO_REQUEST_TYPE, ftdOmr));
    }

    @Test
    public void multicastRouting_outboundForwarding_scopeSmallerThan4IsNotForwarded()
            throws Exception {
        assumeTrue(isKernelVersionAtLeast(KERNEL_VERSION_MULTICAST_ROUTING_SUPPORTED));
        /*
         * <pre>
         * Topology:
         *                 infra network                       Thread
         * infra device -------------------- Border Router -------------- Full Thread device
         *                                   (Cuttlefish)
         * </pre>
         */

        // Let ftd join the network.
        FullThreadDevice ftd = mFtds.get(0);
        startFtdChild(ftd);

        ftd.ping(GROUP_ADDR_SCOPE_3);

        // Infra device doesn't receive an echo request sent by FTD.
        assertNull(readPacket(ICMPV6_ECHO_REQUEST_TYPE, ftd.getOmrAddress()));
    }

    @Test
    public void multicastRouting_infraNetworkSwitch_ftdRepliesToSubscribedAddress()
            throws Exception {
        assumeTrue(isKernelVersionAtLeast(KERNEL_VERSION_MULTICAST_ROUTING_SUPPORTED));
        /*
         * <pre>
         * Topology:
         *                 infra network                       Thread
         * infra device -------------------- Border Router -------------- Full Thread device
         *                                   (Cuttlefish)
         * </pre>
         */

        // Let ftd join the network.
        FullThreadDevice ftd = mFtds.get(0);
        startFtdChild(ftd);
        subscribeMulticastAddressAndWait(ftd, GROUP_ADDR_SCOPE_5);
        Inet6Address ftdOmr = ftd.getOmrAddress();

        // Destroy infra link and re-create
        tearDownInfraNetwork();
        setUpInfraNetwork();
        mInfraNetworkReader = newPacketReader(mInfraNetworkTracker.getTestIface(), mHandler);
        mInfraDevice = startInfraDevice("1:2:3:4:5:6", mInfraNetworkReader);

        // Infra device sends an echo request to FTD's subscribed group address.
        mInfraDevice.sendEchoRequest(GROUP_ADDR_SCOPE_5);
        // Infra device receives an echo reply sent by FTD.
        assertNotNull(readPacket(ICMPV6_ECHO_REPLY_TYPE, ftdOmr));
    }

    private void setUpInfraNetwork() {
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
                    CompletableFuture<Void> future = new CompletableFuture<>();
                    mController.setTestNetworkAsUpstream(
                            mInfraNetworkTracker.getTestIface().getInterfaceName(),
                            directExecutor(),
                            future::complete);
                    future.get(5, TimeUnit.SECONDS);
                });
    }

    private void tearDownInfraNetwork() {
        runAsShell(MANAGE_TEST_NETWORKS, () -> mInfraNetworkTracker.teardown());
    }

    private void startBrLeader() throws Exception {
        CompletableFuture<Void> joinFuture = new CompletableFuture<>();
        runAsShell(
                PERMISSION_THREAD_NETWORK_PRIVILEGED,
                () -> mController.join(DEFAULT_DATASET, directExecutor(), joinFuture::complete));
        joinFuture.get(RESTART_JOIN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        waitForStateAnyOf(mController, List.of(DEVICE_ROLE_LEADER), RESTART_JOIN_TIMEOUT);
    }

    private void startFtdChild(FullThreadDevice ftd) throws Exception {
        ftd.factoryReset();
        ftd.joinNetwork(DEFAULT_DATASET);
        ftd.waitForStateAnyOf(List.of("router", "child"), JOIN_TIMEOUT);
        waitFor(() -> ftd.getOmrAddress() != null, Duration.ofSeconds(60));
        Inet6Address ftdOmr = ftd.getOmrAddress();
        assertNotNull(ftdOmr);
    }

    private InfraNetworkDevice startInfraDevice(String macAddress, TapPacketReader tapPacketReader)
            throws Exception {
        InfraNetworkDevice infraDevice =
                new InfraNetworkDevice(MacAddress.fromString(macAddress), tapPacketReader);
        infraDevice.runSlaac(Duration.ofSeconds(60));
        assertNotNull(infraDevice.ipv6Addr);

        return infraDevice;
    }

    private void assertInfraLinkMemberOfGroup(Inet6Address address) throws Exception {
        waitFor(
                () ->
                        isInMulticastGroup(
                                mInfraNetworkTracker.getTestIface().getInterfaceName(), address),
                Duration.ofSeconds(2));
    }

    private void assertInfraLinkNotMemberOfGroup(Inet6Address address) throws Exception {
        waitFor(
                () ->
                        !isInMulticastGroup(
                                mInfraNetworkTracker.getTestIface().getInterfaceName(), address),
                Duration.ofSeconds(3));
    }

    private void subscribeMulticastAddressAndWait(FullThreadDevice ftd, Inet6Address address)
            throws Exception {
        ftd.subscribeMulticastAddress(address);

        assertInfraLinkMemberOfGroup(address);
    }

    private byte[] readPacket(int type, Inet6Address srcAddress) {
        if (srcAddress == null) {
            return readPacketFrom(mInfraNetworkReader, p -> isExpectedIcmpv6Packet(p, type));
        }
        return readPacketFrom(
                mInfraNetworkReader,
                p -> isExpectedIcmpv6Packet(p, type) && isFromIpv6Source(p, srcAddress));
    }
}
