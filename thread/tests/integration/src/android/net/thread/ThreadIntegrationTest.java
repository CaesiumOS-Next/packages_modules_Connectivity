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

import static android.Manifest.permission.ACCESS_NETWORK_STATE;
import static android.Manifest.permission.MANAGE_TEST_NETWORKS;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_LEADER;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_STOPPED;
import static android.net.thread.ThreadNetworkManager.PERMISSION_THREAD_NETWORK_PRIVILEGED;
import static android.net.thread.utils.IntegrationTestUtils.waitForStateAnyOf;

import static com.android.testutils.TestNetworkTrackerKt.initTestNetwork;
import static com.android.testutils.TestPermissionUtil.runAsShell;

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import static org.junit.Assume.assumeNotNull;

import android.app.UiAutomation;
import android.content.Context;
import android.net.LinkProperties;
import android.net.thread.ThreadNetworkController.StateCallback;
import android.net.thread.utils.OtDaemonController;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.test.InstrumentationRegistry;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.testutils.TestNetworkTracker;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Tests for E2E Android Thread integration with ot-daemon, ConnectivityService, etc.. */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class ThreadIntegrationTest {
    private final Context mContext = ApplicationProvider.getApplicationContext();
    private ThreadNetworkController mController;
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
        final ThreadNetworkManager manager = mContext.getSystemService(ThreadNetworkManager.class);
        if (manager != null) {
            mController = manager.getAllThreadNetworkControllers().get(0);
        }

        // Run the tests on only devices where the Thread feature is available
        assumeNotNull(mController);

        mHandlerThread = new HandlerThread(getClass().getSimpleName());
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        mInfraNetworkTracker =
                runAsShell(
                        MANAGE_TEST_NETWORKS,
                        () ->
                                initTestNetwork(
                                        mContext, new LinkProperties(), 5000 /* timeoutMs */));
        /*
        runAsShell(
                PERMISSION_THREAD_NETWORK_PRIVILEGED,
                NETWORK_SETTINGS,
                () -> {
                    CountDownLatch latch = new CountDownLatch(2);
                    mController.setTestNetworkAsUpstream(
                            mInfraNetworkTracker.getTestIface().getInterfaceName(),
                            directExecutor(),
                            v -> latch.countDown());
                    mController.leave(directExecutor(), v -> latch.countDown());
                    latch.await(10, TimeUnit.SECONDS);
                });
        */
    }

    @After
    public void tearDown() throws Exception {
        if (mController == null) {
            return;
        }

        /*
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
        */
        runAsShell(MANAGE_TEST_NETWORKS, () -> mInfraNetworkTracker.teardown());

        mHandlerThread.quitSafely();
        mHandlerThread.join();
    }

    private static UiAutomation getUiAutomation() {
        return InstrumentationRegistry.getInstrumentation().getUiAutomation();
    }

    @Test
    public void otDaemonCrash_otCtlFactoryReset_controllerApiWorks() throws Exception {
        runAsShell(
                PERMISSION_THREAD_NETWORK_PRIVILEGED,
                () -> mController.join(DEFAULT_DATASET, directExecutor(), result -> {}));
        waitForStateAnyOf(mController, List.of(DEVICE_ROLE_LEADER), 30);

        //OtDaemonController otCtl = new OtDaemonController();

        //otCtl.factoryReset();
        getUiAutomation().executeShellCommand("ot-ctl factoryreset");

        Thread.sleep(500);

        assertThat(getDeviceRole(mController)).isEqualTo(DEVICE_ROLE_STOPPED);
    }

    private static int getDeviceRole(ThreadNetworkController controller) throws Exception {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        StateCallback callback = future::complete;
        runAsShell(
                ACCESS_NETWORK_STATE,
                () -> controller.registerStateCallback(directExecutor(), callback));
        try {
            return future.get(1000, TimeUnit.MILLISECONDS);
        } finally {
            runAsShell(ACCESS_NETWORK_STATE, () -> controller.unregisterStateCallback(callback));
        }
    }
}
