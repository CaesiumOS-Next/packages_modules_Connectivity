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

package android.net.thread.cts;

import static android.net.thread.ThreadNetworkController.THREAD_VERSION_1_3;
import static android.net.thread.ThreadNetworkException.ERROR_ABORTED;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeNotNull;

import static java.util.concurrent.TimeUnit.SECONDS;

import android.Manifest.permission;
import android.content.Context;
import android.net.thread.ActiveOperationalDataset;
import android.net.thread.OperationalDatasetTimestamp;
import android.net.thread.PendingOperationalDataset;
import android.net.thread.ThreadNetworkController;
import android.net.thread.ThreadNetworkController.ScheduleMigrationReceiver;
import android.net.thread.ThreadNetworkException;
import android.net.thread.ThreadNetworkManager;
import android.os.Build;
import android.os.OutcomeReceiver;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** CTS tests for {@link ThreadNetworkController}. */
@SmallTest
@RunWith(DevSdkIgnoreRunner.class)
@IgnoreUpTo(Build.VERSION_CODES.TIRAMISU) // Thread is available on only U+
public class ThreadNetworkControllerTest {
    @Rule public DevSdkIgnoreRule mIgnoreRule = new DevSdkIgnoreRule();

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private ExecutorService mExecutor;
    private ThreadNetworkManager mManager;

    @Before
    public void setUp() {
        mExecutor = Executors.newSingleThreadExecutor();
        mManager = mContext.getSystemService(ThreadNetworkManager.class);

        // TODO: we will also need it in tearDown(), it's better to have a Rule to skip
        // tests if a feature is not available.
        assumeNotNull(mManager);
    }

    @After
    public void tearDown() throws Exception {
        if (mManager != null) {
            detachAndWait();
            dropPermissions();
        }
    }

    private List<ThreadNetworkController> getAllControllers() {
        return mManager.getAllThreadNetworkControllers();
    }

    private void detachAndWait() throws Exception {
        grantPermissions(permission.THREAD_NETWORK_PRIVILEGED);

        for (ThreadNetworkController controller : getAllControllers()) {
            CountDownLatch countDownLatch = new CountDownLatch(1);
            controller.detach(mExecutor, v -> countDownLatch.countDown());
            assertThat(countDownLatch.await(2, SECONDS)).isTrue();
        }
    }

    @Test
    public void getThreadVersion_returnsAtLeastThreadVersion1P3() {
        for (ThreadNetworkController controller : getAllControllers()) {
            assertThat(controller.getThreadVersion()).isAtLeast(THREAD_VERSION_1_3);
        }
    }

    private static void grantPermissions(String... permissions) {
        getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(permissions);
    }

    private static void dropPermissions() {
        getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
    }

    @Test
    public void attachOrForm_withPrivilegedPermission_success() throws Exception {
        grantPermissions(permission.ACCESS_NETWORK_STATE, permission.THREAD_NETWORK_PRIVILEGED);

        for (ThreadNetworkController controller : getAllControllers()) {
            ActiveOperationalDataset activeDataset = ActiveOperationalDataset.createRandomDataset();
            CountDownLatch countDownLatch = new CountDownLatch(1);
            controller.attachOrForm(
                    activeDataset,
                    mExecutor,
                    new OutcomeReceiver<Void, ThreadNetworkException>() {
                        @Override
                        public void onResult(Void v) {
                            countDownLatch.countDown();
                        }

                        @Override
                        public void onError(ThreadNetworkException e) {
                            fail("Unexpected attachOrForm failure: " + e.getMessage());
                        }
                    });
            boolean succeed = countDownLatch.await(21, SECONDS);

            assertThat(succeed).isTrue();
            assertThat(controller.isAttached()).isTrue();
            assertThat(controller.getActiveOperationalDataset()).isEqualTo(activeDataset);
        }
    }

    @Test
    public void attachOrForm_withoutPrivilegedPermission_throwsSecurityException() {
        dropPermissions();

        for (ThreadNetworkController controller : getAllControllers()) {
            ActiveOperationalDataset activeDataset = ActiveOperationalDataset.createRandomDataset();

            assertThrows(
                    SecurityException.class,
                    () -> controller.attachOrForm(activeDataset, mExecutor, v -> {}));
        }
    }

    @Test
    public void attachOrForm_twoImmediateRequestsForTheSameNetworks_bothSuccess() throws Exception {
        grantPermissions(permission.ACCESS_NETWORK_STATE, permission.THREAD_NETWORK_PRIVILEGED);

        for (ThreadNetworkController controller : getAllControllers()) {
            ActiveOperationalDataset activeDataset = ActiveOperationalDataset.createRandomDataset();
            CountDownLatch countDownLatch = new CountDownLatch(2);

            controller.attachOrForm(
                    activeDataset,
                    mExecutor,
                    new OutcomeReceiver<Void, ThreadNetworkException>() {
                        @Override
                        public void onResult(Void v) {
                            countDownLatch.countDown();
                        }

                        @Override
                        public void onError(ThreadNetworkException e) {
                            fail("Unexpected attachOrForm failure: " + e.getMessage());
                        }
                    });
            controller.attachOrForm(
                    activeDataset,
                    mExecutor,
                    new OutcomeReceiver<Void, ThreadNetworkException>() {
                        @Override
                        public void onResult(Void v) {
                            countDownLatch.countDown();
                        }

                        @Override
                        public void onError(ThreadNetworkException e) {
                            fail("Unexpected attachOrForm failure: " + e.getMessage());
                        }
                    });

            assertThat(countDownLatch.await(21, SECONDS)).isTrue();
            assertThat(controller.isAttached()).isTrue();
            assertThat(controller.getActiveOperationalDataset()).isEqualTo(activeDataset);
        }
    }

    @Test
    public void attachOrForm_twoImmediateRequestsForDifferentNetworks_theFirstOneIsAborted()
            throws Exception {
        grantPermissions(permission.ACCESS_NETWORK_STATE, permission.THREAD_NETWORK_PRIVILEGED);

        for (ThreadNetworkController controller : getAllControllers()) {
            ActiveOperationalDataset activeDataset1 =
                    new ActiveOperationalDataset.Builder(
                                    ActiveOperationalDataset.createRandomDataset())
                            .setExtendedPanId(new byte[] {1, 1, 1, 1, 1, 1, 1, 1})
                            .build();
            ActiveOperationalDataset activeDataset2 =
                    new ActiveOperationalDataset.Builder(activeDataset1)
                            .setExtendedPanId(new byte[] {2, 2, 2, 2, 2, 2, 2, 2})
                            .build();

            CountDownLatch countDownLatch = new CountDownLatch(2);
            AtomicReference<ThreadNetworkException> firstRequestFailure = new AtomicReference<>();
            controller.attachOrForm(
                    activeDataset1,
                    mExecutor,
                    new OutcomeReceiver<Void, ThreadNetworkException>() {
                        @Override
                        public void onResult(Void v) {
                            fail("Unexpected attachOrForm success");
                        }

                        @Override
                        public void onError(ThreadNetworkException e) {
                            firstRequestFailure.set(e);
                            countDownLatch.countDown();
                        }
                    });
            controller.attachOrForm(
                    activeDataset2,
                    mExecutor,
                    new OutcomeReceiver<Void, ThreadNetworkException>() {
                        @Override
                        public void onResult(Void v) {
                            countDownLatch.countDown();
                        }

                        @Override
                        public void onError(ThreadNetworkException e) {
                            fail("Unexpected attachOrForm failure: " + e.getMessage());
                        }
                    });

            assertThat(countDownLatch.await(21, SECONDS)).isTrue();
            assertThat(firstRequestFailure.get().getErrorCode()).isEqualTo(ERROR_ABORTED);
            assertThat(controller.isAttached()).isTrue();
            assertThat(controller.getActiveOperationalDataset()).isEqualTo(activeDataset2);
        }
    }

    @Test
    public void detach_withPrivilegedPermission_success() throws Exception {
        grantPermissions(permission.THREAD_NETWORK_PRIVILEGED);

        for (ThreadNetworkController controller : getAllControllers()) {
            ActiveOperationalDataset activeDataset = ActiveOperationalDataset.createRandomDataset();
            CountDownLatch attachCountDownLatch = new CountDownLatch(1);
            controller.attachOrForm(
                    activeDataset,
                    mExecutor,
                    new OutcomeReceiver<Void, ThreadNetworkException>() {
                        @Override
                        public void onResult(Void v) {
                            attachCountDownLatch.countDown();
                        }

                        @Override
                        public void onError(ThreadNetworkException e) {
                            fail("Unexpected attachOrForm failure: " + e.getMessage());
                        }
                    });
            assertThat(attachCountDownLatch.await(21, SECONDS)).isTrue();

            CountDownLatch detachCountDownLatch = new CountDownLatch(1);
            controller.detach(mExecutor, v -> detachCountDownLatch.countDown());

            assertThat(detachCountDownLatch.await(2, SECONDS)).isTrue();
            grantPermissions(permission.ACCESS_NETWORK_STATE);
            assertThat(controller.isAttached()).isFalse();
        }
    }

    @Test
    public void detach_withoutPrivilegedPermission_throwsSecurityException() {
        dropPermissions();

        for (ThreadNetworkController controller : getAllControllers()) {
            assertThrows(SecurityException.class, () -> controller.detach(mExecutor, v -> {}));
        }
    }

    @Test
    public void detach_twoImmediateRequests_bothSuccess() throws Exception {
        grantPermissions(permission.THREAD_NETWORK_PRIVILEGED);

        for (ThreadNetworkController controller : getAllControllers()) {
            ActiveOperationalDataset activeDataset = ActiveOperationalDataset.createRandomDataset();
            CountDownLatch attachCountDownLatch = new CountDownLatch(1);
            controller.attachOrForm(
                    activeDataset,
                    mExecutor,
                    new OutcomeReceiver<Void, ThreadNetworkException>() {
                        @Override
                        public void onResult(Void v) {
                            attachCountDownLatch.countDown();
                        }

                        @Override
                        public void onError(ThreadNetworkException e) {
                            fail("Unexpected attachOrForm failure: " + e.getMessage());
                        }
                    });
            assertThat(attachCountDownLatch.await(21, SECONDS)).isTrue();

            CountDownLatch detachCountDownLatch = new CountDownLatch(2);
            controller.detach(mExecutor, v -> detachCountDownLatch.countDown());
            controller.detach(mExecutor, v -> detachCountDownLatch.countDown());

            assertThat(detachCountDownLatch.await(2, SECONDS)).isTrue();
            grantPermissions(permission.ACCESS_NETWORK_STATE);
            assertThat(controller.isAttached()).isFalse();
        }
    }

    @Test
    public void scheduleMigration_withPrivilegedPermission_success() throws Exception {
        grantPermissions(permission.ACCESS_NETWORK_STATE, permission.THREAD_NETWORK_PRIVILEGED);

        for (ThreadNetworkController controller : getAllControllers()) {
            ActiveOperationalDataset activeDataset1 =
                    new ActiveOperationalDataset.Builder(
                                    ActiveOperationalDataset.createRandomDataset())
                            .setActiveTimestamp(new OperationalDatasetTimestamp(1L, 0, false))
                            .setExtendedPanId(new byte[] {1, 1, 1, 1, 1, 1, 1, 1})
                            .build();
            ActiveOperationalDataset activeDataset2 =
                    new ActiveOperationalDataset.Builder(activeDataset1)
                            .setActiveTimestamp(new OperationalDatasetTimestamp(2L, 0, false))
                            .setNetworkName("ThreadNet2")
                            .build();
            PendingOperationalDataset pendingDataset2 =
                    new PendingOperationalDataset(
                            activeDataset2,
                            OperationalDatasetTimestamp.fromInstant(Instant.now()),
                            Duration.ofSeconds(30));
            CountDownLatch attachCountDownLatch = new CountDownLatch(1);
            controller.attachOrForm(
                    activeDataset1,
                    mExecutor,
                    new OutcomeReceiver<Void, ThreadNetworkException>() {
                        @Override
                        public void onResult(Void v) {
                            attachCountDownLatch.countDown();
                        }

                        @Override
                        public void onError(ThreadNetworkException e) {
                            fail("Unexpected attachOrForm failure: " + e.getMessage());
                        }
                    });
            assertThat(attachCountDownLatch.await(21, SECONDS)).isTrue();

            CountDownLatch scheduledCountDown = new CountDownLatch(1);
            CountDownLatch migratedCountDown = new CountDownLatch(1);
            AtomicReference<Duration> atomicDelayTimer = new AtomicReference<>();
            controller.scheduleMigration(
                    pendingDataset2,
                    mExecutor,
                    new ScheduleMigrationReceiver() {
                        @Override
                        public void onScheduled(Duration delayTimer) {
                            atomicDelayTimer.set(delayTimer);
                            scheduledCountDown.countDown();
                        }

                        @Override
                        public void onMigrated() {
                            migratedCountDown.countDown();
                        }

                        @Override
                        public void onError(ThreadNetworkException e) {
                            fail("Unexpected scheduleMigration failure: " + e.getMessage());
                        }
                    });

            assertThat(scheduledCountDown.await(11, SECONDS)).isTrue();
            assertThat(controller.getActiveOperationalDataset()).isEqualTo(activeDataset1);
            assertThat(controller.getPendingOperationalDataset().getActiveOperationalDataset())
                    .isEqualTo(pendingDataset2.getActiveOperationalDataset());
            assertThat(controller.getPendingOperationalDataset().getDelayTimer())
                    .isEqualTo(atomicDelayTimer.get());
            assertThat(migratedCountDown.await(atomicDelayTimer.get().toSeconds() + 5, SECONDS))
                    .isTrue();
            assertThat(controller.isAttached()).isTrue();
            assertThat(controller.getActiveOperationalDataset()).isEqualTo(activeDataset2);
            assertThat(controller.getPendingOperationalDataset()).isNull();
        }
    }
}
