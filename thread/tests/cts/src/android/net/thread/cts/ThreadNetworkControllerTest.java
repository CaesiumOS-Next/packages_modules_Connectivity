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

import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_STOPPED;
import static android.net.thread.ThreadNetworkController.THREAD_VERSION_1_3;
import static android.net.thread.ThreadNetworkException.ERROR_ABORTED;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeNotNull;

import static java.util.concurrent.TimeUnit.SECONDS;

import android.Manifest.permission;
import android.content.Context;
import android.net.thread.ActiveOperationalDataset;
import android.net.thread.OperationalDatasetTimestamp;
import android.net.thread.PendingOperationalDataset;
import android.net.thread.ThreadNetworkController;
import android.net.thread.ThreadNetworkController.OperationalDatasetCallback;
import android.net.thread.ThreadNetworkController.ScheduleMigrationReceiver;
import android.net.thread.ThreadNetworkController.StateCallback;
import android.net.thread.ThreadNetworkException;
import android.net.thread.ThreadNetworkManager;
import android.os.Build;
import android.os.OutcomeReceiver;
import android.platform.test.annotations.RequiresFlagsEnabled;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.LargeTest;

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import com.android.testutils.DevSdkIgnoreRunner;

import com.google.common.util.concurrent.SettableFuture;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** CTS tests for {@link ThreadNetworkController}. */
@LargeTest
@RunWith(DevSdkIgnoreRunner.class)
@IgnoreUpTo(Build.VERSION_CODES.TIRAMISU) // Thread is available on only U+
@RequiresFlagsEnabled("com.android.net.thread.flags.thread_enabled")
public class ThreadNetworkControllerTest {
    private static final int CALLBACK_TIMEOUT_SECONDS = 1;
    private static final String PERMISSION_THREAD_NETWORK_PRIVILEGED =
            "android.permission.THREAD_NETWORK_PRIVILEGED";

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
            leaveAndWait();
            dropPermissions();
        }
    }

    private List<ThreadNetworkController> getAllControllers() {
        return mManager.getAllThreadNetworkControllers();
    }

    private void leaveAndWait() throws Exception {
        grantPermissions(PERMISSION_THREAD_NETWORK_PRIVILEGED);

        for (ThreadNetworkController controller : getAllControllers()) {
            CountDownLatch countDownLatch = new CountDownLatch(1);
            controller.leave(mExecutor, v -> countDownLatch.countDown());
            assertThat(countDownLatch.await(2, SECONDS)).isTrue();
        }
    }

    private static void grantPermissions(String... permissions) {
        getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(permissions);
    }

    private static void dropPermissions() {
        getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
    }

    private static ActiveOperationalDataset newRandomizedDataset(
            String networkName, ThreadNetworkController controller) throws Exception {
        SettableFuture<ActiveOperationalDataset> future = SettableFuture.create();
        controller.createRandomizedDataset(networkName, directExecutor(), future::set);
        return future.get(CALLBACK_TIMEOUT_SECONDS, SECONDS);
    }

    private static boolean isAttached(ThreadNetworkController controller) throws Exception {
        SettableFuture<Integer> future = SettableFuture.create();
        StateCallback callback = future::set;
        controller.registerStateCallback(directExecutor(), callback);
        int role = future.get(CALLBACK_TIMEOUT_SECONDS, SECONDS);
        controller.unregisterStateCallback(callback);
        return ThreadNetworkController.isAttached(role);
    }

    private static ActiveOperationalDataset getActiveOperationalDataset(
            ThreadNetworkController controller) throws Exception {
        SettableFuture<ActiveOperationalDataset> future = SettableFuture.create();
        OperationalDatasetCallback callback = future::set;
        controller.registerOperationalDatasetCallback(directExecutor(), callback);
        ActiveOperationalDataset dataset = future.get(CALLBACK_TIMEOUT_SECONDS, SECONDS);
        controller.unregisterOperationalDatasetCallback(callback);
        return dataset;
    }

    private static PendingOperationalDataset getPendingOperationalDataset(
            ThreadNetworkController controller) throws Exception {
        SettableFuture<PendingOperationalDataset> future = SettableFuture.create();
        OperationalDatasetCallback callback =
                new OperationalDatasetCallback() {
                    @Override
                    public void onActiveOperationalDatasetChanged(
                            ActiveOperationalDataset activeOpDataset) {
                        // Empty as we don't care about the active dataset here
                    }

                    @Override
                    public void onPendingOperationalDatasetChanged(
                            PendingOperationalDataset pendingOpDataset) {
                        future.set(pendingOpDataset);
                    }
                };

        controller.registerOperationalDatasetCallback(directExecutor(), callback);
        PendingOperationalDataset dataset = future.get(CALLBACK_TIMEOUT_SECONDS, SECONDS);
        return dataset;
    }

    @Test
    public void getThreadVersion_returnsAtLeastThreadVersion1P3() {
        for (ThreadNetworkController controller : getAllControllers()) {
            assertThat(controller.getThreadVersion()).isAtLeast(THREAD_VERSION_1_3);
        }
    }

    @Test
    public void registerStateCallback_permissionsGranted_returnsCurrentStatesImmediately()
            throws Exception {
        grantPermissions(permission.ACCESS_NETWORK_STATE);

        for (ThreadNetworkController controller : getAllControllers()) {
            SettableFuture<Integer> deviceRole = SettableFuture.create();

            controller.registerStateCallback(mExecutor, role -> deviceRole.set(role));

            assertThat(deviceRole.get()).isEqualTo(DEVICE_ROLE_STOPPED);
        }
    }

    @Test
    public void registerStateCallback_noPermissions_throwsSecurityException() throws Exception {
        dropPermissions();

        for (ThreadNetworkController controller : getAllControllers()) {
            SettableFuture<Integer> deviceRole = SettableFuture.create();

            assertThrows(
                    SecurityException.class,
                    () -> controller.registerStateCallback(mExecutor, role -> {}));
        }
    }

    @Test
    public void registerStateCallback_alreadyRegistered_throwsIllegalArgumentException()
            throws Exception {
        grantPermissions(permission.ACCESS_NETWORK_STATE);

        for (ThreadNetworkController controller : getAllControllers()) {
            SettableFuture<Integer> deviceRole = SettableFuture.create();
            StateCallback callback = role -> deviceRole.set(role);
            controller.registerStateCallback(mExecutor, callback);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> controller.registerStateCallback(mExecutor, callback));
        }
    }

    @Test
    public void unregisterStateCallback_callbackRegistered_success() throws Exception {
        grantPermissions(permission.ACCESS_NETWORK_STATE);
        for (ThreadNetworkController controller : getAllControllers()) {
            SettableFuture<Integer> deviceRole = SettableFuture.create();
            StateCallback callback = role -> deviceRole.set(role);
            controller.registerStateCallback(mExecutor, callback);

            controller.unregisterStateCallback(callback);
        }
    }

    @Test
    public void unregisterStateCallback_callbackNotRegistered_throwsIllegalArgumentException()
            throws Exception {
        for (ThreadNetworkController controller : getAllControllers()) {
            SettableFuture<Integer> deviceRole = SettableFuture.create();
            StateCallback callback = role -> deviceRole.set(role);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> controller.unregisterStateCallback(callback));
        }
    }

    @Test
    public void unregisterStateCallback_alreadyUnregistered_throwsIllegalArgumentException()
            throws Exception {
        grantPermissions(permission.ACCESS_NETWORK_STATE);
        for (ThreadNetworkController controller : getAllControllers()) {
            SettableFuture<Integer> deviceRole = SettableFuture.create();
            StateCallback callback = role -> deviceRole.set(role);
            controller.registerStateCallback(mExecutor, callback);
            controller.unregisterStateCallback(callback);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> controller.unregisterStateCallback(callback));
        }
    }

    // TODO: CTS test cases for registerOperationalDatasetCallback

    private static <V> OutcomeReceiver<V, ThreadNetworkException> newOutcomeReceiver(
            SettableFuture<V> future) {
        return new OutcomeReceiver<V, ThreadNetworkException>() {
            @Override
            public void onResult(V result) {
                future.set(result);
            }

            @Override
            public void onError(ThreadNetworkException e) {
                future.setException(e);
            }
        };
    }

    @Test
    public void join_withPrivilegedPermission_success() throws Exception {
        grantPermissions(permission.ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);

        for (ThreadNetworkController controller : getAllControllers()) {
            ActiveOperationalDataset activeDataset = newRandomizedDataset("TestNet", controller);
            SettableFuture<ActiveOperationalDataset> joinFuture = SettableFuture.create();

            controller.join(activeDataset, mExecutor, newOutcomeReceiver(joinFuture));

            assertThat(joinFuture.get(21, SECONDS)).isEqualTo(activeDataset);
            assertThat(isAttached(controller)).isTrue();
            assertThat(getActiveOperationalDataset(controller)).isEqualTo(activeDataset);
        }
    }

    @Test
    public void join_withoutPrivilegedPermission_throwsSecurityException() throws Exception {
        dropPermissions();

        for (ThreadNetworkController controller : getAllControllers()) {
            ActiveOperationalDataset activeDataset = newRandomizedDataset("TestNet", controller);

            assertThrows(
                    SecurityException.class,
                    () -> controller.join(activeDataset, mExecutor, v -> {}));
        }
    }

    @Test
    public void join_twoImmediateRequestsForTheSameNetworks_bothSuccess() throws Exception {
        grantPermissions(permission.ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);

        for (ThreadNetworkController controller : getAllControllers()) {
            ActiveOperationalDataset activeDataset = newRandomizedDataset("TestNet", controller);
            SettableFuture<ActiveOperationalDataset> joinFuture1 = SettableFuture.create();
            SettableFuture<ActiveOperationalDataset> joinFuture2 = SettableFuture.create();

            controller.join(activeDataset, mExecutor, newOutcomeReceiver(joinFuture1));
            controller.join(activeDataset, mExecutor, newOutcomeReceiver(joinFuture2));

            assertThat(joinFuture1.get(21, SECONDS)).isEqualTo(activeDataset);
            assertThat(joinFuture2.get(21, SECONDS)).isEqualTo(activeDataset);
            assertThat(isAttached(controller)).isTrue();
            assertThat(getActiveOperationalDataset(controller)).isEqualTo(activeDataset);
        }
    }

    @Test
    public void join_twoImmediateRequestsWithDifferentKey_theFirstOneIsAborted() throws Exception {
        grantPermissions(permission.ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);

        for (ThreadNetworkController controller : getAllControllers()) {
            ActiveOperationalDataset activeDataset1 =
                    new ActiveOperationalDataset.Builder(
                                    newRandomizedDataset("TestNet", controller))
                            .setNetworkKey(
                                    new byte[] {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1})
                            .build();
            ActiveOperationalDataset activeDataset2 =
                    new ActiveOperationalDataset.Builder(activeDataset1)
                            .setNetworkKey(
                                    new byte[] {2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2})
                            .build();
            SettableFuture<ActiveOperationalDataset> joinFuture1 = SettableFuture.create();
            SettableFuture<ActiveOperationalDataset> joinFuture2 = SettableFuture.create();

            controller.join(activeDataset1, mExecutor, newOutcomeReceiver(joinFuture1));
            controller.join(activeDataset2, mExecutor, newOutcomeReceiver(joinFuture2));

            ThreadNetworkException join1Thrown =
                    (ThreadNetworkException)
                            assertThrows(ExecutionException.class, joinFuture1::get).getCause();
            assertThat(join1Thrown.getErrorCode()).isEqualTo(ERROR_ABORTED);
            assertThat(joinFuture2.get(21, SECONDS)).isEqualTo(activeDataset2);
            assertThat(isAttached(controller)).isTrue();
            assertThat(getActiveOperationalDataset(controller)).isEqualTo(activeDataset2);
        }
    }

    @Test
    public void leave_withPrivilegedPermission_success() throws Exception {
        grantPermissions(PERMISSION_THREAD_NETWORK_PRIVILEGED);

        for (ThreadNetworkController controller : getAllControllers()) {
            ActiveOperationalDataset activeDataset = newRandomizedDataset("TestNet", controller);
            SettableFuture<ActiveOperationalDataset> joinFuture = SettableFuture.create();
            SettableFuture<Void> leaveFuture = SettableFuture.create();

            controller.join(activeDataset, mExecutor, newOutcomeReceiver(joinFuture));
            joinFuture.get(21, SECONDS);
            controller.leave(mExecutor, newOutcomeReceiver(leaveFuture));

            leaveFuture.get(2, SECONDS);
            grantPermissions(permission.ACCESS_NETWORK_STATE);
            assertThat(isAttached(controller)).isFalse();
        }
    }

    @Test
    public void leave_withoutPrivilegedPermission_throwsSecurityException() {
        dropPermissions();

        for (ThreadNetworkController controller : getAllControllers()) {
            assertThrows(SecurityException.class, () -> controller.leave(mExecutor, v -> {}));
        }
    }

    @Test
    public void leave_twoImmediateRequests_bothSuccess() throws Exception {
        grantPermissions(PERMISSION_THREAD_NETWORK_PRIVILEGED);

        for (ThreadNetworkController controller : getAllControllers()) {
            ActiveOperationalDataset activeDataset = newRandomizedDataset("TestNet", controller);
            SettableFuture<ActiveOperationalDataset> joinFuture = SettableFuture.create();
            SettableFuture<Void> leaveFuture1 = SettableFuture.create();
            SettableFuture<Void> leaveFuture2 = SettableFuture.create();

            controller.join(activeDataset, mExecutor, newOutcomeReceiver(joinFuture));
            joinFuture.get(21, SECONDS);
            controller.leave(mExecutor, newOutcomeReceiver(leaveFuture1));
            controller.leave(mExecutor, newOutcomeReceiver(leaveFuture2));

            leaveFuture1.get(2, SECONDS);
            leaveFuture2.get(2, SECONDS);
            grantPermissions(permission.ACCESS_NETWORK_STATE);
            assertThat(isAttached(controller)).isFalse();
        }
    }

    @Test
    public void scheduleMigration_withPrivilegedPermission_success() throws Exception {
        grantPermissions(permission.ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);

        for (ThreadNetworkController controller : getAllControllers()) {
            ActiveOperationalDataset activeDataset1 =
                    new ActiveOperationalDataset.Builder(
                                    newRandomizedDataset("TestNet", controller))
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
            SettableFuture<ActiveOperationalDataset> joinFuture = SettableFuture.create();
            SettableFuture<Duration> scheduleFuture = SettableFuture.create();
            SettableFuture<Void> migrateFuture = SettableFuture.create();

            controller.join(activeDataset1, mExecutor, newOutcomeReceiver(joinFuture));
            joinFuture.get(21, SECONDS);
            controller.scheduleMigration(
                    pendingDataset2,
                    mExecutor,
                    new ScheduleMigrationReceiver() {
                        @Override
                        public void onScheduled(Duration delayTimer) {
                            scheduleFuture.set(delayTimer);
                        }

                        @Override
                        public void onMigrated() {
                            migrateFuture.set(null);
                        }

                        @Override
                        public void onError(ThreadNetworkException e) {
                            if (!scheduleFuture.isDone()) {
                                scheduleFuture.setException(e);
                            }
                            migrateFuture.setException(e);
                        }
                    });

            scheduleFuture.get(11, SECONDS);
            assertThat(getActiveOperationalDataset(controller)).isEqualTo(activeDataset1);
            assertThat(getPendingOperationalDataset(controller).getActiveOperationalDataset())
                    .isEqualTo(pendingDataset2.getActiveOperationalDataset());
            assertThat(getPendingOperationalDataset(controller).getDelayTimer())
                    .isEqualTo(scheduleFuture.get());
            migrateFuture.get(scheduleFuture.get().toSeconds() + 5, SECONDS);
            assertThat(isAttached(controller)).isTrue();
            assertThat(getActiveOperationalDataset(controller)).isEqualTo(activeDataset2);
            assertThat(getPendingOperationalDataset(controller)).isNull();
        }
    }

    @Test
    public void createRandomizedDataset_wrongNetworkNameLength_throwsIllegalArgumentException() {
        for (ThreadNetworkController controller : getAllControllers()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> controller.createRandomizedDataset("", mExecutor, dataset -> {}));

            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            controller.createRandomizedDataset(
                                    "ANetNameIs17Bytes", mExecutor, dataset -> {}));
        }
    }

    @Test
    public void createRandomizedDataset_validNetworkName_success() throws Exception {
        for (ThreadNetworkController controller : getAllControllers()) {
            ActiveOperationalDataset dataset = newRandomizedDataset("validName", controller);

            assertThat(dataset.getNetworkName()).isEqualTo("validName");
            assertThat(dataset.getPanId()).isLessThan(0xffff);
            assertThat(dataset.getChannelMask().size()).isAtLeast(1);
            assertThat(dataset.getExtendedPanId()).hasLength(8);
            assertThat(dataset.getNetworkKey()).hasLength(16);
            assertThat(dataset.getPskc()).hasLength(16);
            assertThat(dataset.getMeshLocalPrefix().getPrefixLength()).isEqualTo(64);
            assertThat(dataset.getMeshLocalPrefix().getRawAddress()[0]).isEqualTo((byte)0xfd);
        }
    }
}
