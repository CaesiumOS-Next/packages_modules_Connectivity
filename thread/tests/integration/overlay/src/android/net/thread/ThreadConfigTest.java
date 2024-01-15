/*
 * Copyright (C) 2024 The Android Open Source Project
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
import static android.net.thread.ThreadNetworkController.STATE_DISABLED;

import static com.android.testutils.TestPermissionUtil.runAsShell;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import static org.junit.Assume.assumeNotNull;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import android.net.thread.ThreadNetworkController.StateCallback;
import android.os.Build;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.LargeTest;

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tests for Thread module configuration via Runtime Resource Overlay (RRO). */
@LargeTest
@RunWith(DevSdkIgnoreRunner.class)
@IgnoreUpTo(Build.VERSION_CODES.TIRAMISU) // Thread is available on only U+
public class ThreadConfigTest {
    private static final int ENABLED_TIMEOUT_MILLIS = 2_000;

    @Rule public DevSdkIgnoreRule mIgnoreRule = new DevSdkIgnoreRule();

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private ExecutorService mExecutor;
    private ThreadNetworkController mController;

    @Before
    public void setUp() throws Exception {
        mExecutor = Executors.newSingleThreadExecutor();
        ThreadNetworkManager manager = mContext.getSystemService(ThreadNetworkManager.class);
        if (manager != null) {
            mController = manager.getAllThreadNetworkControllers().get(0);
        }

        // TODO: we will also need it in tearDown(), it's better to have a Rule to skip
        // tests if a feature is not available.
        assumeNotNull(mController);
    }

    @After
    public void tearDown() throws Exception {}

    @Test
    public void threadEnabled_disabledByDefault() throws Exception {
        assertThat(getEnabledState(mController)).isEqualTo(STATE_DISABLED);
    }

    private static int getEnabledState(ThreadNetworkController controller) throws Exception {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        StateCallback callback =
                new ThreadNetworkController.StateCallback() {
                    @Override
                    public void onDeviceRoleChanged(int r) {}

                    @Override
                    public void onThreadEnableStateChanged(int enabled) {
                        future.complete(enabled);
                    }
                };
        runAsShell(
                ACCESS_NETWORK_STATE,
                () -> controller.registerStateCallback(directExecutor(), callback));
        try {
            return future.get(ENABLED_TIMEOUT_MILLIS, MILLISECONDS);
        } finally {
            runAsShell(ACCESS_NETWORK_STATE, () -> controller.unregisterStateCallback(callback));
        }
    }
}
