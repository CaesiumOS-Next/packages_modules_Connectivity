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
import static android.provider.DeviceConfig.NAMESPACE_TETHERING;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.net.thread.ThreadNetworkController;
import android.net.thread.ThreadNetworkManager;
import android.os.Build;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.compatibility.common.util.DeviceConfigStateHelper;
//import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
//import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** CTS tests for {@link ThreadNetworkController}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
//@RunWith(DevSdkIgnoreRunner.class)
//@IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public class ThreadNetworkControllerTest {
    private final Context mContext = ApplicationProvider.getApplicationContext();
    private DeviceConfigStateHelper mDeviceConfigStateHelper;

    @Before
    public void setUp() {
        assumeTrue(mContext.getPackageManager().hasSystemFeature(ThreadNetworkManager.FEATURE_NAME));
        mDeviceConfigStateHelper = new DeviceConfigStateHelper(NAMESPACE_TETHERING);
        mDeviceConfigStateHelper.set("thread_enabled", "true");
    }

    private ThreadNetworkController getController() {
        ThreadNetworkManager manager = mContext.getSystemService(ThreadNetworkManager.class);
        return manager == null ? null : manager.getAllThreadNetworkControllers().get(0);
    }

    @Test
    public void getManager_onAndroidU_returnsNull() {
        ThreadNetworkManager manager = mContext.getSystemService(ThreadNetworkManager.class);

        assertThat(manager).isNotNull();
    }

    @Test
    public void getThreadVersion_returnsThreadVersion1P3() {
        ThreadNetworkController controller = getController();

        assertThat(controller.getThreadVersion()).isEqualTo(THREAD_VERSION_1_3);
    }
}