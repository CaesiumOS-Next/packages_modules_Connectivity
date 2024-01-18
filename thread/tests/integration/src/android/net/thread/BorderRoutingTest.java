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
import static android.net.thread.IntegrationTestUtils.isSimulatedThreadRadioSupported;
import static android.net.thread.IntegrationTestUtils.newPacketReader;
import static android.net.thread.IntegrationTestUtils.readPacketFrom;

import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ECHO_REPLY_TYPE;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

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
public class BorderRoutingTest extends BorderRoutingBaseTest {
    private static final String TAG = BorderRoutingTest.class.getSimpleName();

    @Before
    public void setUp() throws Exception {
        super.setUp();
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
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
}
