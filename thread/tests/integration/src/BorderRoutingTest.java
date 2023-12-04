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
import static android.Manifest.permission.THREAD_NETWORK_PRIVILEGED;
import static android.net.thread.IntegrationTestUtils.isExpectedIcmpv6Packet;
import static android.net.thread.IntegrationTestUtils.newPacketReader;
import static android.net.thread.IntegrationTestUtils.readPacketFrom;
import static android.net.thread.IntegrationTestUtils.waitFor;
import static android.net.thread.IntegrationTestUtils.waitForStateAnyOf;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_LEADER;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ECHO_REPLY_TYPE;
import static com.android.testutils.TestNetworkTrackerKt.initTestNetwork;
import static com.android.testutils.TestPermissionUtil.runAsShell;
import static com.google.common.io.BaseEncoding.base16;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.net.MacAddress;
import android.net.LinkProperties;
import android.os.Handler;
import android.os.HandlerThread;
import android.net.thread.IntegrationTestUtils.FullThreadDevice;
import android.net.thread.IntegrationTestUtils.InfraNetworkDevice;

import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.testutils.TapPacketReader;
import com.android.testutils.TestNetworkTracker;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.Inet6Address;
import java.util.List;

/** Integration test cases for Thread Border Routing feature. */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class BorderRoutingTest {
    private static final String TAG = BorderRoutingTest.class.getSimpleName();
    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();
    private final ThreadNetworkManager mThreadNetworkManager =
            mContext.getSystemService(ThreadNetworkManager.class);
    private ThreadNetworkController mThreadNetworkController;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private TestNetworkTracker mInfraNetworkTracker;

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
        mHandlerThread = new HandlerThread(getClass().getSimpleName());
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        var threadControllers = mThreadNetworkManager.getAllThreadNetworkControllers();
        assertEquals(threadControllers.size(), 1);
        mThreadNetworkController = threadControllers.get(0);
        mInfraNetworkTracker =
                runAsShell(
                        MANAGE_TEST_NETWORKS,
                        () ->
                                initTestNetwork(
                                        mContext, new LinkProperties(), 5000 /* timeoutMs */));
        runAsShell(
                THREAD_NETWORK_PRIVILEGED,
                () ->
                        mThreadNetworkController.setTestNetworkAsUpstream(
                                mInfraNetworkTracker.getTestIface().getInterfaceName(),
                                mContext.getMainExecutor(),
                                v -> {}));
    }

    @After
    public void tearDown() throws Exception {
        runAsShell(
                THREAD_NETWORK_PRIVILEGED,
                () -> {
                    mThreadNetworkController.setTestNetworkAsUpstream(
                            null, mContext.getMainExecutor(), v -> {});
                    mThreadNetworkController.leave(mContext.getMainExecutor(), result -> {});
                });
        runAsShell(MANAGE_TEST_NETWORKS, () -> mInfraNetworkTracker.teardown());

        mHandlerThread.quitSafely();
        mHandlerThread.join();
    }

    /**
     * A device on infrastructure network pings a Thread device's OMR and receives the ping reply.
     *
     * <pre>
     * Topology:
     *                 infra network                       Thread
     * infra device -------------------- Border Router -------------- Full Thread device
     *                                   (Cuttlefish)
     * </pre>
     *
     * @throws Exception
     */
    @Test
    public void infraDevicePingTheadDeviceOmr_Succeeds() throws Exception {
        TapPacketReader infraNetworkReader =
                newPacketReader(mInfraNetworkTracker.getTestIface(), mHandler);

        // BR forms a network.
        runAsShell(
                THREAD_NETWORK_PRIVILEGED,
                () -> {
                    mThreadNetworkController.join(
                            DEFAULT_DATASET, mContext.getMainExecutor(), result -> {});
                });
        waitForStateAnyOf(
                mThreadNetworkController, List.of(DEVICE_ROLE_LEADER), 30 /* timeoutSeconds */);

        // Creates a Full Thread Device (FTD) and lets it join the network.
        FullThreadDevice ftd = new FullThreadDevice(5 /* node ID */);
        ftd.factoryReset();
        ftd.joinNetwork(DEFAULT_DATASET);
        ftd.waitForStateAnyOf(List.of("router", "child"), 10 /* timeoutSeconds */);
        waitFor(() -> ftd.getOmrAddress() != null, 60 /* timeoutSeconds */);
        Inet6Address ftdOmr = ftd.getOmrAddress();
        assertNotNull(ftdOmr);

        // Creates a infra network device.
        InfraNetworkDevice infraDevice =
                new InfraNetworkDevice(MacAddress.fromString("1:2:3:4:5:6"), infraNetworkReader);
        infraDevice.runSlaac(20 /* timeoutSeconds */);
        assertNotNull(infraDevice.ipv6Addr);

        // Infra device sends an echo request to FTD's OMR.
        infraDevice.sendEchoRequest(ftdOmr);

        // Infra device receives an echo reply sent by FTD.
        assertNotNull(
                readPacketFrom(
                        infraNetworkReader,
                        p -> isExpectedIcmpv6Packet(p, ICMPV6_ECHO_REPLY_TYPE)));
    }
}
