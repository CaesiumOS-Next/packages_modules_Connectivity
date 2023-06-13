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

import static org.junit.Assert.assertEquals;

import android.os.Build;

import androidx.test.filters.SmallTest;

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.Class;
import java.lang.reflect.Method;

/**
 * Unit tests for {@link DisabledVpnManager}.
 *
 * <p>The tests makes sure that all the public methods defined on the {@link VpnManager} class are
 * overridden in the disable version of it.
 *
 * <p>Adding a new public method in {@link VpnManager} without overriding it in {@link
 * DisabledVpnManager} will cause this test to fail.
 */
@SmallTest
@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public class DisabledVpnManagerTest {
    private static final String DISABLED_VPN_MANAGER_CLASS = "android.net.DisabledVpnManager";

    @Test
    public void testAllPublicMethodsImplementedByVpnManagerOverriden() throws Exception {
        // Gets all the public methods defined on `VpnManager` class.
        final Method[] vpnManagerPublicMethods = VpnManager.class.getMethods();
        final Class disabledVpnManagerClass = Class.forName(DISABLED_VPN_MANAGER_CLASS);
        for (Method method : vpnManagerPublicMethods) {
            // Check only the methods implemented by the `VpnManager` class and ignore public
            // methods implemented by super classes (e.g. equals).
            if (method.getDeclaringClass() == VpnManager.class)
                assertEquals(disabledVpnManagerClass.getMethod(method.getName(),
                    method.getParameterTypes()).getDeclaringClass(), disabledVpnManagerClass);
        }
    }
}
