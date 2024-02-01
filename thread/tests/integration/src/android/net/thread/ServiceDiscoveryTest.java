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

import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_LEADER;
import static android.net.thread.ThreadNetworkManager.PERMISSION_THREAD_NETWORK_PRIVILEGED;
import static android.net.thread.utils.IntegrationTestUtils.JOIN_TIMEOUT;
import static android.net.thread.utils.IntegrationTestUtils.RESTART_JOIN_TIMEOUT;
import static android.net.thread.utils.IntegrationTestUtils.discoverService;
import static android.net.thread.utils.IntegrationTestUtils.isSimulatedThreadRadioSupported;
import static android.net.thread.utils.IntegrationTestUtils.resolveService;
import static android.net.thread.utils.IntegrationTestUtils.waitFor;
import static android.net.thread.utils.IntegrationTestUtils.waitForStateAnyOf;

import static com.android.testutils.TestPermissionUtil.runAsShell;

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import static org.junit.Assume.assumeNotNull;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.net.InetAddresses;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.thread.utils.FullThreadDevice;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Integration test cases for Service Discovery feature. */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class ServiceDiscoveryTest {
    private static final String TAG = ServiceDiscoveryTest.class.getSimpleName();
    private final Context mContext = ApplicationProvider.getApplicationContext();
    private ThreadNetworkController mController;
    private NsdManager mNsdManager;

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
        final ThreadNetworkManager manager = mContext.getSystemService(ThreadNetworkManager.class);
        if (manager != null) {
            mController = manager.getAllThreadNetworkControllers().get(0);
        }

        // Run the tests on only devices where the Thread feature is available
        assumeNotNull(mController);

        // BR forms a network.
        runAsShell(
                PERMISSION_THREAD_NETWORK_PRIVILEGED,
                () -> mController.join(DEFAULT_DATASET, directExecutor(), result -> {}));
        waitForStateAnyOf(mController, List.of(DEVICE_ROLE_LEADER), RESTART_JOIN_TIMEOUT);

        mNsdManager = mContext.getSystemService(NsdManager.class);
    }

    @Test
    public void advertisingProxy_registerServiceBySrp_serviceResolved() throws Exception {
        assumeTrue(isSimulatedThreadRadioSupported());

        /*
         * <pre>
         * Topology:
         *                    Thread
         *  Border Router -------------- Full Thread device 1
         *  (Cuttlefish)         |
         *                       +------ Full Thread device 2
         *                       |
         *                       +------ Full Thread device 3
         * </pre>
         */

        final int NUM_FTD = 3;

        // Creates Full Thread Devices (FTD) and let them join the network.
        List<FullThreadDevice> ftds = new ArrayList<>();
        for (int i = 0; i < NUM_FTD; ++i) {
            FullThreadDevice ftd = new FullThreadDevice(i + 15 /* node ID */);

            ftd.factoryReset();
            ftd.joinNetwork(DEFAULT_DATASET);
            ftd.waitForStateAnyOf(List.of("router", "child"), JOIN_TIMEOUT);
            waitFor(() -> ftd.getOmrAddress() != null, Duration.ofSeconds(60));
            ftds.add(ftd);
        }

        // For every FTD, let it register an SRP service.
        for (int i = 0; i < ftds.size(); ++i) {
            FullThreadDevice ftd = ftds.get(i);

            ftd.autoStartSrpClient();
            ftd.setSrpHostname(String.format("host-%d", i));
            ftd.setSrpHostAddresses(
                    List.of("2001:db8::" + (2 * i + 1), "2001:db8::" + (2 * i + 2)));
            ftd.addSrpService(
                    "service-" + i,
                    "_test" + i + "._tcp",
                    Collections.emptyList() /* subtypes */,
                    12345,
                    Map.of("key1", bytes(0x01, 0x02), "key2", bytes(i)));
        }

        // Check the advertised services are discoverable and resolvable by NsdManager
        for (int i = 0; i < ftds.size(); ++i) {
            NsdServiceInfo discoveredService = discoverService(mNsdManager, "_test" + i + "._tcp");
            assertThat(discoveredService).isNotNull();
            NsdServiceInfo resolvedService = resolveService(mNsdManager, discoveredService);

            assertThat(resolvedService.getServiceName()).isEqualTo("service-" + i);
            assertThat(resolvedService.getServiceType()).isEqualTo("_test" + i + "._tcp");
            assertThat(resolvedService.getPort()).isEqualTo(12345);
            assertThat(resolvedService.getServiceName()).isEqualTo("service-" + i);

            Map<String, byte[]> resolvedTxtMap = resolvedService.getAttributes();
            assertThat(resolvedTxtMap.get("key1")).isEqualTo(bytes(0x01, 0x02));
            assertThat(resolvedTxtMap.get("key2")).isEqualTo(bytes(i));
            assertThat(resolvedService.getHostname()).isEqualTo("host-" + i);
            assertThat(resolvedService.getHostAddresses())
                    .isEqualTo(
                            List.of(
                                    InetAddresses.parseNumericAddress("2001:db8::" + (2 * i + 1)),
                                    InetAddresses.parseNumericAddress("2001:db8::" + (2 * i + 2))));
        }
    }

    private static byte[] bytes(int... byteInts) {
        byte[] bytes = new byte[byteInts.length];
        for (int i = 0; i < byteInts.length; ++i) {
            bytes[i] = (byte) byteInts[i];
        }
        return bytes;
    }
}
