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

package android.remoteauth;

import static org.junit.Assert.assertTrue;

import android.app.UiAutomation;
import android.content.Context;
import android.os.Build;
import android.platform.test.annotations.Presubmit;

import androidx.annotation.RequiresApi;
import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Unit tests for {@link RemoteAuth}. */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
// TODO: when available in all active branches: @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
@RequiresApi(Build.VERSION_CODES.CUR_DEVELOPMENT)
public class RemoteAuthManagerTest {
    private Context mContext;
    private RemoteAuthManager mManager;

    private UiAutomation mUiAutomation =
            InstrumentationRegistry.getInstrumentation().getUiAutomation();
    private static final Executor EXECUTOR = Executors.newSingleThreadExecutor();

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getContext();
        //mManager = mContext.getSystemService(RemoteAuthManager.class);
    }

    /*
    @Test
    @SdkSuppress(minSdkVersion = 35, codeName = "V")
    public void test_isRemoteAuthSupported() {
        mManager.isRemoteAuthSupported();
    }
     */

    @Test
    public void testStub() {
        assertTrue(true);
    }
}
