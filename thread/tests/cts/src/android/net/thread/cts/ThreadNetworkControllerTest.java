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
import static android.net.thread.ThreadNetworkException.ERROR_FAILED_PRECONDITION;
import static android.net.thread.ThreadNetworkException.ERROR_REJECTED_BY_PEER;
import static android.net.thread.ThreadNetworkException.ERROR_THREAD_DISABLED;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeNotNull;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.Manifest.permission;
import android.content.Context;
import android.net.thread.ActiveOperationalDataset;
import android.net.thread.OperationalDatasetTimestamp;
import android.net.thread.PendingOperationalDataset;
import android.net.thread.ThreadNetworkController;
import android.net.thread.ThreadNetworkController.OperationalDatasetCallback;
import android.net.thread.ThreadNetworkController.StateCallback;
import android.net.thread.ThreadNetworkException;
import android.net.thread.ThreadNetworkManager;
import android.os.Build;
import android.os.OutcomeReceiver;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;

/** CTS tests for {@link ThreadNetworkController}. */
@LargeTest
@RunWith(DevSdkIgnoreRunner.class)
@IgnoreUpTo(Build.VERSION_CODES.TIRAMISU) // Thread is available on only U+
public class ThreadNetworkControllerTest {
    private static final int CALLBACK_TIMEOUT_MILLIS = 1000;
    private static final String PERMISSION_THREAD_NETWORK_PRIVILEGED =
            "android.permission.THREAD_NETWORK_PRIVILEGED";

    @Rule public DevSdkIgnoreRule mIgnoreRule = new DevSdkIgnoreRule();

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private ExecutorService mExecutor;
    private ThreadNetworkController mController;

    private Set<String> mGrantedPermissions;

    @Before
    public void setUp() throws Exception {
        mExecutor = Executors.newSingleThreadExecutor();
        final ThreadNetworkManager manager = mContext.getSystemService(ThreadNetworkManager.class);
        if (manager != null) {
            mController = manager.getThreadNetworkController();
        }
        mGrantedPermissions = new HashSet<String>();

        // TODO: we will also need it in tearDown(), it's better to have a Rule to skip
        // tests if a feature is not available.
        assumeNotNull(mController);
        setThreadEnabled(true);
        dropAllPermissions();
    }

    @After
    public void tearDown() throws Exception {
        if (mController != null) {
            setThreadEnabled(true);
            leaveAndWait();
            dropAllPermissions();
        }
    }

    private void leaveAndWait() throws Exception {
        grantPermissions(PERMISSION_THREAD_NETWORK_PRIVILEGED);

        SettableFuture<Void> future = SettableFuture.create();
        mController.leave(mExecutor, future::set);
        future.get();
    }

    private void grantPermissions(String... permissions) {
        for (String permission : permissions) {
            mGrantedPermissions.add(permission);
        }
        String[] allPermissions = new String[mGrantedPermissions.size()];
        mGrantedPermissions.toArray(allPermissions);
        getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(allPermissions);
    }

    private static void dropAllPermissions() {
        getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
    }

    private static ActiveOperationalDataset newRandomizedDataset(
            String networkName, ThreadNetworkController controller) throws Exception {
        SettableFuture<ActiveOperationalDataset> future = SettableFuture.create();
        controller.createRandomizedDataset(networkName, directExecutor(), future::set);
        return future.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS);
    }

    private static boolean isAttached(ThreadNetworkController controller) throws Exception {
        return ThreadNetworkController.isAttached(getDeviceRole(controller));
    }

    private static int getDeviceRole(ThreadNetworkController controller) throws Exception {
        SettableFuture<Integer> future = SettableFuture.create();
        StateCallback callback = future::set;
        controller.registerStateCallback(directExecutor(), callback);
        int role = future.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS);
        controller.unregisterStateCallback(callback);
        return role;
    }

    private static int waitForStateAnyOf(
            ThreadNetworkController controller, List<Integer> deviceRoles) throws Exception {
        SettableFuture<Integer> future = SettableFuture.create();
        StateCallback callback =
                newRole -> {
                    if (deviceRoles.contains(newRole)) {
                        future.set(newRole);
                    }
                };
        controller.registerStateCallback(directExecutor(), callback);
        int role = future.get();
        controller.unregisterStateCallback(callback);
        return role;
    }

    private static ActiveOperationalDataset getActiveOperationalDataset(
            ThreadNetworkController controller) throws Exception {
        SettableFuture<ActiveOperationalDataset> future = SettableFuture.create();
        OperationalDatasetCallback callback = future::set;
        controller.registerOperationalDatasetCallback(directExecutor(), callback);
        ActiveOperationalDataset dataset = future.get(CALLBACK_TIMEOUT_MILLIS, MILLISECONDS);
        controller.unregisterOperationalDatasetCallback(callback);
        return dataset;
    }

    private static PendingOperationalDataset getPendingOperationalDataset(
            ThreadNetworkController controller) throws Exception {
        SettableFuture<ActiveOperationalDataset> activeFuture = SettableFuture.create();
        SettableFuture<PendingOperationalDataset> pendingFuture = SettableFuture.create();
        controller.registerOperationalDatasetCallback(
                directExecutor(), newDatasetCallback(activeFuture, pendingFuture));
        return pendingFuture.get();
    }

    private static OperationalDatasetCallback newDatasetCallback(
            SettableFuture<ActiveOperationalDataset> activeFuture,
            SettableFuture<PendingOperationalDataset> pendingFuture) {
        return new OperationalDatasetCallback() {
            @Override
            public void onActiveOperationalDatasetChanged(
                    ActiveOperationalDataset activeOpDataset) {
                activeFuture.set(activeOpDataset);
            }

            @Override
            public void onPendingOperationalDatasetChanged(
                    PendingOperationalDataset pendingOpDataset) {
                pendingFuture.set(pendingOpDataset);
            }
        };
    }

    @Test
    public void getThreadVersion_returnsAtLeastThreadVersion1P3() {
        assertThat(mController.getThreadVersion()).isAtLeast(THREAD_VERSION_1_3);
    }

    @Test
    public void registerStateCallback_permissionsGranted_returnsCurrentStates() throws Exception {
        grantPermissions(permission.ACCESS_NETWORK_STATE);
        SettableFuture<Integer> deviceRole = SettableFuture.create();
        StateCallback callback = deviceRole::set;

        try {
            mController.registerStateCallback(mExecutor, callback);

            assertThat(deviceRole.get()).isEqualTo(DEVICE_ROLE_STOPPED);
        } finally {
            mController.unregisterStateCallback(callback);
        }
    }

    @Test
    public void registerStateCallback_permissionsGranted_returnsThreadEnabledState()
            throws Exception {
        grantPermissions(permission.ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);

        SettableFuture<Boolean> isThreadEnabled = SettableFuture.create();
        StateCallback callback =
                new ThreadNetworkController.StateCallback() {
                    @Override
                    public void onDeviceRoleChanged(int mDeviceRole) {
                        return;
                    }

                    @Override
                    public void onThreadEnabledChanged(boolean enabled) {
                        isThreadEnabled.set(enabled);
                    }
                };

        try {
            mController.registerStateCallback(mExecutor, callback);

            assertThat(isThreadEnabled.get()).isEqualTo(true);
        } finally {
            mController.unregisterStateCallback(callback);
        }
    }

    @Test
    public void registerStateCallback_noPermissions_throwsSecurityException() throws Exception {
        dropAllPermissions();

        assertThrows(
                SecurityException.class,
                () -> mController.registerStateCallback(mExecutor, role -> {}));
    }

    @Test
    public void registerStateCallback_alreadyRegistered_throwsIllegalArgumentException()
            throws Exception {
        grantPermissions(permission.ACCESS_NETWORK_STATE);

        SettableFuture<Integer> deviceRole = SettableFuture.create();
        StateCallback callback = role -> deviceRole.set(role);
        mController.registerStateCallback(mExecutor, callback);

        assertThrows(
                IllegalArgumentException.class,
                () -> mController.registerStateCallback(mExecutor, callback));
    }

    @Test
    public void unregisterStateCallback_noPermissions_throwsSecurityException() throws Exception {
        SettableFuture<Integer> deviceRole = SettableFuture.create();
        StateCallback callback = role -> deviceRole.set(role);
        grantPermissions(permission.ACCESS_NETWORK_STATE);
        mController.registerStateCallback(mExecutor, callback);

        try {
            dropAllPermissions();
            assertThrows(
                    SecurityException.class, () -> mController.unregisterStateCallback(callback));
        } finally {
            grantPermissions(permission.ACCESS_NETWORK_STATE);
            mController.unregisterStateCallback(callback);
        }
    }

    @Test
    public void unregisterStateCallback_callbackRegistered_success() throws Exception {
        grantPermissions(permission.ACCESS_NETWORK_STATE);
        SettableFuture<Integer> deviceRole = SettableFuture.create();
        StateCallback callback = role -> deviceRole.set(role);
        mController.registerStateCallback(mExecutor, callback);

        mController.unregisterStateCallback(callback);
    }

    @Test
    public void unregisterStateCallback_callbackNotRegistered_throwsIllegalArgumentException()
            throws Exception {
        SettableFuture<Integer> deviceRole = SettableFuture.create();
        StateCallback callback = role -> deviceRole.set(role);

        assertThrows(
                IllegalArgumentException.class,
                () -> mController.unregisterStateCallback(callback));
    }

    @Test
    public void unregisterStateCallback_alreadyUnregistered_throwsIllegalArgumentException()
            throws Exception {
        grantPermissions(permission.ACCESS_NETWORK_STATE);
        SettableFuture<Integer> deviceRole = SettableFuture.create();
        StateCallback callback = deviceRole::set;
        mController.registerStateCallback(mExecutor, callback);
        mController.unregisterStateCallback(callback);

        assertThrows(
                IllegalArgumentException.class,
                () -> mController.unregisterStateCallback(callback));
    }

    @Test
    public void registerOperationalDatasetCallback_permissionsGranted_returnsCurrentStates()
            throws Exception {
        grantPermissions(permission.ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);

        SettableFuture<ActiveOperationalDataset> activeFuture = SettableFuture.create();
        SettableFuture<PendingOperationalDataset> pendingFuture = SettableFuture.create();
        var callback = newDatasetCallback(activeFuture, pendingFuture);

        try {
            mController.registerOperationalDatasetCallback(mExecutor, callback);

            assertThat(activeFuture.get()).isNull();
            assertThat(pendingFuture.get()).isNull();
        } finally {
            mController.unregisterOperationalDatasetCallback(callback);
        }
    }

    @Test
    public void registerOperationalDatasetCallback_noPermissions_throwsSecurityException()
            throws Exception {
        dropAllPermissions();

        SettableFuture<ActiveOperationalDataset> activeFuture = SettableFuture.create();
        SettableFuture<PendingOperationalDataset> pendingFuture = SettableFuture.create();
        var callback = newDatasetCallback(activeFuture, pendingFuture);

        assertThrows(
                SecurityException.class,
                () -> mController.registerOperationalDatasetCallback(mExecutor, callback));
    }

    @Test
    public void unregisterOperationalDatasetCallback_callbackRegistered_success() throws Exception {
        grantPermissions(permission.ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);
        SettableFuture<ActiveOperationalDataset> activeFuture = SettableFuture.create();
        SettableFuture<PendingOperationalDataset> pendingFuture = SettableFuture.create();
        var callback = newDatasetCallback(activeFuture, pendingFuture);
        mController.registerOperationalDatasetCallback(mExecutor, callback);

        mController.unregisterOperationalDatasetCallback(callback);
    }

    @Test
    public void unregisterOperationalDatasetCallback_noPermissions_throwsSecurityException()
            throws Exception {
        dropAllPermissions();
        SettableFuture<ActiveOperationalDataset> activeFuture = SettableFuture.create();
        SettableFuture<PendingOperationalDataset> pendingFuture = SettableFuture.create();
        var callback = newDatasetCallback(activeFuture, pendingFuture);
        grantPermissions(permission.ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);
        mController.registerOperationalDatasetCallback(mExecutor, callback);

        try {
            dropAllPermissions();
            assertThrows(
                    SecurityException.class,
                    () -> mController.unregisterOperationalDatasetCallback(callback));
        } finally {
            grantPermissions(permission.ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);
            mController.unregisterOperationalDatasetCallback(callback);
        }
    }

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
        grantPermissions(PERMISSION_THREAD_NETWORK_PRIVILEGED);
        ActiveOperationalDataset activeDataset = newRandomizedDataset("TestNet", mController);
        SettableFuture<Void> joinFuture = SettableFuture.create();

        mController.join(activeDataset, mExecutor, newOutcomeReceiver(joinFuture));
        joinFuture.get();

        grantPermissions(permission.ACCESS_NETWORK_STATE);
        assertThat(isAttached(mController)).isTrue();
        assertThat(getActiveOperationalDataset(mController)).isEqualTo(activeDataset);
    }

    @Test
    public void join_withoutPrivilegedPermission_throwsSecurityException() throws Exception {
        dropAllPermissions();

        ActiveOperationalDataset activeDataset = newRandomizedDataset("TestNet", mController);

        assertThrows(
                SecurityException.class, () -> mController.join(activeDataset, mExecutor, v -> {}));
    }

    private void setThreadEnabled(boolean enabled) throws Exception {
        String[] savedPermissions =
                mGrantedPermissions.toArray(new String[mGrantedPermissions.size()]);
        grantPermissions(permission.ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);

        BlockingQueue<Boolean> queue = new LinkedBlockingDeque<>();

        StateCallback callback =
                new ThreadNetworkController.StateCallback() {
                    @Override
                    public void onDeviceRoleChanged(int mDeviceRole) {}

                    @Override
                    public void onThreadEnabledChanged(boolean enabled) {
                        queue.add(enabled);
                    }
                };

        mController.registerStateCallback(mExecutor, callback);

        try {
            if (queue.take() != enabled) {
                SettableFuture<Void> setFuture = SettableFuture.create();
                mController.setThreadEnabled(enabled, mExecutor, newOutcomeReceiver(setFuture));

                assertThat(queue.take()).isEqualTo(enabled);
            }
        } finally {
            mController.unregisterStateCallback(callback);
            dropAllPermissions();
            grantPermissions(savedPermissions);
        }
    }

    @Test
    public void join_whenThreadDisabled_throwsThreadNetworkException() throws Exception {
        grantPermissions(PERMISSION_THREAD_NETWORK_PRIVILEGED);
        ActiveOperationalDataset activeDataset = newRandomizedDataset("TestNet", mController);
        setThreadEnabled(false);

        SettableFuture<Void> joinFuture = SettableFuture.create();
        mController.join(activeDataset, mExecutor, newOutcomeReceiver(joinFuture));

        ThreadNetworkException thrown =
                (ThreadNetworkException)
                        assertThrows(ExecutionException.class, joinFuture::get).getCause();
        assertThat(thrown.getErrorCode()).isEqualTo(ERROR_THREAD_DISABLED);
    }

    @Test
    public void join_concurrentRequests_firstOneIsAborted() throws Exception {
        grantPermissions(PERMISSION_THREAD_NETWORK_PRIVILEGED);
        final byte[] KEY_1 = new byte[] {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};
        final byte[] KEY_2 = new byte[] {2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2};
        ActiveOperationalDataset activeDataset1 =
                new ActiveOperationalDataset.Builder(newRandomizedDataset("TestNet", mController))
                        .setNetworkKey(KEY_1)
                        .build();
        ActiveOperationalDataset activeDataset2 =
                new ActiveOperationalDataset.Builder(activeDataset1).setNetworkKey(KEY_2).build();
        SettableFuture<Void> joinFuture1 = SettableFuture.create();
        SettableFuture<Void> joinFuture2 = SettableFuture.create();

        mController.join(activeDataset1, mExecutor, newOutcomeReceiver(joinFuture1));
        mController.join(activeDataset2, mExecutor, newOutcomeReceiver(joinFuture2));

        ThreadNetworkException thrown =
                (ThreadNetworkException)
                        assertThrows(ExecutionException.class, joinFuture1::get).getCause();
        assertThat(thrown.getErrorCode()).isEqualTo(ERROR_ABORTED);
        joinFuture2.get();
        grantPermissions(permission.ACCESS_NETWORK_STATE);
        assertThat(isAttached(mController)).isTrue();
        assertThat(getActiveOperationalDataset(mController)).isEqualTo(activeDataset2);
    }

    @Test
    public void leave_withPrivilegedPermission_success() throws Exception {
        grantPermissions(PERMISSION_THREAD_NETWORK_PRIVILEGED);
        ActiveOperationalDataset activeDataset = newRandomizedDataset("TestNet", mController);
        SettableFuture<Void> joinFuture = SettableFuture.create();
        SettableFuture<Void> leaveFuture = SettableFuture.create();
        mController.join(activeDataset, mExecutor, newOutcomeReceiver(joinFuture));
        joinFuture.get();

        mController.leave(mExecutor, newOutcomeReceiver(leaveFuture));
        leaveFuture.get();

        grantPermissions(permission.ACCESS_NETWORK_STATE);
        assertThat(getDeviceRole(mController)).isEqualTo(DEVICE_ROLE_STOPPED);
    }

    @Test
    public void leave_withoutPrivilegedPermission_throwsSecurityException() {
        dropAllPermissions();

        assertThrows(SecurityException.class, () -> mController.leave(mExecutor, v -> {}));
    }

    @Test
    public void leave_whenThreadDisabled_throwsThreadNetworkException() throws Exception {
        grantPermissions(PERMISSION_THREAD_NETWORK_PRIVILEGED);
        ActiveOperationalDataset activeDataset = newRandomizedDataset("TestNet", mController);
        SettableFuture<Void> joinFuture = SettableFuture.create();
        SettableFuture<Void> leaveFuture = SettableFuture.create();
        mController.join(activeDataset, mExecutor, newOutcomeReceiver(joinFuture));
        joinFuture.get();

        setThreadEnabled(false);
        mController.leave(mExecutor, newOutcomeReceiver(leaveFuture));

        ThreadNetworkException thrown =
                (ThreadNetworkException)
                        assertThrows(ExecutionException.class, leaveFuture::get).getCause();
        assertThat(thrown.getErrorCode()).isEqualTo(ERROR_THREAD_DISABLED);
    }

    @Test
    public void leave_concurrentRequests_bothSuccess() throws Exception {
        grantPermissions(PERMISSION_THREAD_NETWORK_PRIVILEGED);
        ActiveOperationalDataset activeDataset = newRandomizedDataset("TestNet", mController);
        SettableFuture<Void> joinFuture = SettableFuture.create();
        SettableFuture<Void> leaveFuture1 = SettableFuture.create();
        SettableFuture<Void> leaveFuture2 = SettableFuture.create();
        mController.join(activeDataset, mExecutor, newOutcomeReceiver(joinFuture));
        joinFuture.get();

        mController.leave(mExecutor, newOutcomeReceiver(leaveFuture1));
        mController.leave(mExecutor, newOutcomeReceiver(leaveFuture2));

        leaveFuture1.get();
        leaveFuture2.get();
        grantPermissions(permission.ACCESS_NETWORK_STATE);
        assertThat(getDeviceRole(mController)).isEqualTo(DEVICE_ROLE_STOPPED);
    }

    @Test
    public void scheduleMigration_withPrivilegedPermission_newDatasetApplied() throws Exception {
        grantPermissions(permission.ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);
        ActiveOperationalDataset activeDataset1 =
                new ActiveOperationalDataset.Builder(newRandomizedDataset("TestNet", mController))
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
        SettableFuture<Void> joinFuture = SettableFuture.create();
        SettableFuture<Void> migrateFuture = SettableFuture.create();
        mController.join(activeDataset1, mExecutor, newOutcomeReceiver(joinFuture));
        joinFuture.get();

        mController.scheduleMigration(
                pendingDataset2, mExecutor, newOutcomeReceiver(migrateFuture));
        migrateFuture.get();

        SettableFuture<Boolean> dataset2IsApplied = SettableFuture.create();
        SettableFuture<Boolean> pendingDatasetIsRemoved = SettableFuture.create();
        OperationalDatasetCallback datasetCallback =
                new OperationalDatasetCallback() {
                    @Override
                    public void onActiveOperationalDatasetChanged(
                            ActiveOperationalDataset activeDataset) {
                        if (activeDataset.equals(activeDataset2)) {
                            dataset2IsApplied.set(true);
                        }
                    }

                    @Override
                    public void onPendingOperationalDatasetChanged(
                            PendingOperationalDataset pendingDataset) {
                        if (pendingDataset == null) {
                            pendingDatasetIsRemoved.set(true);
                        }
                    }
                };
        mController.registerOperationalDatasetCallback(directExecutor(), datasetCallback);
        assertThat(dataset2IsApplied.get()).isTrue();
        assertThat(pendingDatasetIsRemoved.get()).isTrue();
        mController.unregisterOperationalDatasetCallback(datasetCallback);
    }

    @Test
    public void scheduleMigration_whenNotAttached_failWithPreconditionError() throws Exception {
        grantPermissions(permission.ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);
        PendingOperationalDataset pendingDataset =
                new PendingOperationalDataset(
                        newRandomizedDataset("TestNet", mController),
                        OperationalDatasetTimestamp.fromInstant(Instant.now()),
                        Duration.ofSeconds(30));
        SettableFuture<Void> migrateFuture = SettableFuture.create();

        mController.scheduleMigration(pendingDataset, mExecutor, newOutcomeReceiver(migrateFuture));

        ThreadNetworkException thrown =
                (ThreadNetworkException)
                        assertThrows(ExecutionException.class, migrateFuture::get).getCause();
        assertThat(thrown.getErrorCode()).isEqualTo(ERROR_FAILED_PRECONDITION);
    }

    @Test
    public void scheduleMigration_secondRequestHasSmallerTimestamp_rejectedByLeader()
            throws Exception {
        grantPermissions(permission.ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);
        final ActiveOperationalDataset activeDataset =
                new ActiveOperationalDataset.Builder(newRandomizedDataset("testNet", mController))
                        .setActiveTimestamp(new OperationalDatasetTimestamp(1L, 0, false))
                        .build();
        ActiveOperationalDataset activeDataset1 =
                new ActiveOperationalDataset.Builder(activeDataset)
                        .setActiveTimestamp(new OperationalDatasetTimestamp(2L, 0, false))
                        .setNetworkName("testNet1")
                        .build();
        PendingOperationalDataset pendingDataset1 =
                new PendingOperationalDataset(
                        activeDataset1,
                        new OperationalDatasetTimestamp(100, 0, false),
                        Duration.ofSeconds(30));
        ActiveOperationalDataset activeDataset2 =
                new ActiveOperationalDataset.Builder(activeDataset)
                        .setActiveTimestamp(new OperationalDatasetTimestamp(3L, 0, false))
                        .setNetworkName("testNet2")
                        .build();
        PendingOperationalDataset pendingDataset2 =
                new PendingOperationalDataset(
                        activeDataset2,
                        new OperationalDatasetTimestamp(20, 0, false),
                        Duration.ofSeconds(30));
        SettableFuture<Void> joinFuture = SettableFuture.create();
        SettableFuture<Void> migrateFuture1 = SettableFuture.create();
        SettableFuture<Void> migrateFuture2 = SettableFuture.create();
        mController.join(activeDataset, mExecutor, newOutcomeReceiver(joinFuture));
        joinFuture.get();

        mController.scheduleMigration(
                pendingDataset1, mExecutor, newOutcomeReceiver(migrateFuture1));
        migrateFuture1.get();
        mController.scheduleMigration(
                pendingDataset2, mExecutor, newOutcomeReceiver(migrateFuture2));

        ThreadNetworkException thrown =
                (ThreadNetworkException)
                        assertThrows(ExecutionException.class, migrateFuture2::get).getCause();
        assertThat(thrown.getErrorCode()).isEqualTo(ERROR_REJECTED_BY_PEER);
    }

    @Test
    public void scheduleMigration_secondRequestHasLargerTimestamp_newDatasetApplied()
            throws Exception {
        grantPermissions(permission.ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);
        final ActiveOperationalDataset activeDataset =
                new ActiveOperationalDataset.Builder(newRandomizedDataset("validName", mController))
                        .setActiveTimestamp(new OperationalDatasetTimestamp(1L, 0, false))
                        .build();
        ActiveOperationalDataset activeDataset1 =
                new ActiveOperationalDataset.Builder(activeDataset)
                        .setActiveTimestamp(new OperationalDatasetTimestamp(2L, 0, false))
                        .setNetworkName("testNet1")
                        .build();
        PendingOperationalDataset pendingDataset1 =
                new PendingOperationalDataset(
                        activeDataset1,
                        new OperationalDatasetTimestamp(100, 0, false),
                        Duration.ofSeconds(30));
        ActiveOperationalDataset activeDataset2 =
                new ActiveOperationalDataset.Builder(activeDataset)
                        .setActiveTimestamp(new OperationalDatasetTimestamp(3L, 0, false))
                        .setNetworkName("testNet2")
                        .build();
        PendingOperationalDataset pendingDataset2 =
                new PendingOperationalDataset(
                        activeDataset2,
                        new OperationalDatasetTimestamp(200, 0, false),
                        Duration.ofSeconds(30));
        SettableFuture<Void> joinFuture = SettableFuture.create();
        SettableFuture<Void> migrateFuture1 = SettableFuture.create();
        SettableFuture<Void> migrateFuture2 = SettableFuture.create();
        mController.join(activeDataset, mExecutor, newOutcomeReceiver(joinFuture));
        joinFuture.get();

        mController.scheduleMigration(
                pendingDataset1, mExecutor, newOutcomeReceiver(migrateFuture1));
        migrateFuture1.get();
        mController.scheduleMigration(
                pendingDataset2, mExecutor, newOutcomeReceiver(migrateFuture2));
        migrateFuture2.get();

        SettableFuture<Boolean> dataset2IsApplied = SettableFuture.create();
        SettableFuture<Boolean> pendingDatasetIsRemoved = SettableFuture.create();
        OperationalDatasetCallback datasetCallback =
                new OperationalDatasetCallback() {
                    @Override
                    public void onActiveOperationalDatasetChanged(
                            ActiveOperationalDataset activeDataset) {
                        if (activeDataset.equals(activeDataset2)) {
                            dataset2IsApplied.set(true);
                        }
                    }

                    @Override
                    public void onPendingOperationalDatasetChanged(
                            PendingOperationalDataset pendingDataset) {
                        if (pendingDataset == null) {
                            pendingDatasetIsRemoved.set(true);
                        }
                    }
                };
        mController.registerOperationalDatasetCallback(directExecutor(), datasetCallback);
        assertThat(dataset2IsApplied.get()).isTrue();
        assertThat(pendingDatasetIsRemoved.get()).isTrue();
        mController.unregisterOperationalDatasetCallback(datasetCallback);
    }

    @Test
    public void scheduleMigration_whenThreadDisabled_throwsThreadNetworkException()
            throws Exception {
        grantPermissions(PERMISSION_THREAD_NETWORK_PRIVILEGED);
        ActiveOperationalDataset activeDataset = newRandomizedDataset("TestNet", mController);
        PendingOperationalDataset pendingDataset =
                new PendingOperationalDataset(
                        activeDataset,
                        OperationalDatasetTimestamp.fromInstant(Instant.now()),
                        Duration.ofSeconds(30));
        SettableFuture<Void> joinFuture = SettableFuture.create();
        SettableFuture<Void> migrationFuture = SettableFuture.create();
        mController.join(activeDataset, mExecutor, newOutcomeReceiver(joinFuture));
        joinFuture.get();

        setThreadEnabled(false);
        mController.scheduleMigration(
                pendingDataset, mExecutor, newOutcomeReceiver(migrationFuture));

        ThreadNetworkException thrown =
                (ThreadNetworkException)
                        assertThrows(ExecutionException.class, migrationFuture::get).getCause();
        assertThat(thrown.getErrorCode()).isEqualTo(ERROR_THREAD_DISABLED);
    }

    @Test
    public void createRandomizedDataset_wrongNetworkNameLength_throwsIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> mController.createRandomizedDataset("", mExecutor, dataset -> {}));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        mController.createRandomizedDataset(
                                "ANetNameIs17Bytes", mExecutor, dataset -> {}));
    }

    @Test
    public void createRandomizedDataset_validNetworkName_success() throws Exception {
        ActiveOperationalDataset dataset = newRandomizedDataset("validName", mController);

        assertThat(dataset.getNetworkName()).isEqualTo("validName");
        assertThat(dataset.getPanId()).isLessThan(0xffff);
        assertThat(dataset.getChannelMask().size()).isAtLeast(1);
        assertThat(dataset.getExtendedPanId()).hasLength(8);
        assertThat(dataset.getNetworkKey()).hasLength(16);
        assertThat(dataset.getPskc()).hasLength(16);
        assertThat(dataset.getMeshLocalPrefix().getPrefixLength()).isEqualTo(64);
        assertThat(dataset.getMeshLocalPrefix().getRawAddress()[0]).isEqualTo((byte) 0xfd);
    }

    @Test
    public void setThreadEnabled_permissionsGranted_succeeds() throws Exception {
        grantPermissions(permission.ACCESS_NETWORK_STATE, PERMISSION_THREAD_NETWORK_PRIVILEGED);

        setThreadEnabled(false);

        setThreadEnabled(true);
    }

    @Test
    public void setThreadEnabled_noPermissions_throwsSecurityException() throws Exception {
        dropAllPermissions();
        SettableFuture<Void> setFuture = SettableFuture.create();

        assertThrows(
                SecurityException.class,
                () ->
                        mController.setThreadEnabled(
                                false, mExecutor, newOutcomeReceiver(setFuture)));
    }

    @Test
    public void leave_withPrivilegedPermission_success() throws Exception {
        grantPermissions(PERMISSION_THREAD_NETWORK_PRIVILEGED);
        ActiveOperationalDataset activeDataset = newRandomizedDataset("TestNet", mController);
        SettableFuture<Void> joinFuture = SettableFuture.create();
        SettableFuture<Void> leaveFuture = SettableFuture.create();
        mController.join(activeDataset, mExecutor, newOutcomeReceiver(joinFuture));
        joinFuture.get();

        mController.leave(mExecutor, newOutcomeReceiver(leaveFuture));
        leaveFuture.get();

        grantPermissions(permission.ACCESS_NETWORK_STATE);
        assertThat(getDeviceRole(mController)).isEqualTo(DEVICE_ROLE_STOPPED);
    }
}
