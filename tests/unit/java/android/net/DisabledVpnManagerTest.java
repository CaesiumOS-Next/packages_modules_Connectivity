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

package android.net;

import static org.junit.Assert.assertNotEquals;

import android.content.Context;
import android.content.pm.PackageManager;

import android.os.Build;

import androidx.test.filters.SmallTest;
import androidx.test.InstrumentationRegistry;

import com.android.compatibility.common.util.RequiredFeatureRule;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;

import java.util.List;

/**
 * Unit tests for {@link DisabledVpnManager}.
 */
@SmallTest
@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
public class DisabledVpnManagerTest {

  @Rule
  public RequiredFeatureRule mRequiredFeatureRule =
      new RequiredFeatureRule(PackageManager.FEATURE_WATCH);

  @Test
  public void testAllPublicMethodsImplementedByVpnManagerOverridden() throws Exception {
    VpnManager vpnManager = (VpnManager) InstrumentationRegistry.getContext()
        .getSystemService(Context.VPN_MANAGEMENT_SERVICE);
    Method[] methods = vpnManager.getClass().getMethods();
    for (Method method : methods) {
      assertNotEquals(method.getDeclaringClass(), VpnManager.class);
    }
  }
}
