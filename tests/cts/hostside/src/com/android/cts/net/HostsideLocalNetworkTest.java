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

package com.android.cts.net;

import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.host.HostFlagsValueProvider;

import org.junit.Rule;
import org.junit.Test;

public class HostsideLocalNetworkTest extends HostsideNetworkTestCase{

    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            HostFlagsValueProvider.createCheckFlagsRule(this::getDevice);

    @Test
    @RequiresFlagsEnabled("com.android.net.flags.restrict_local_network")
    public void testLocalNetworkRestrictions() throws Exception {
        uninstallPackage(TEST_APP2_PKG, false);
        installPackage(TEST_APP2_APK, false);
        runDeviceTests(TEST_PKG, TEST_PKG + ".LocalNetworkTest",
                "permissionGranted_checksLocalNetworkRestrictions");
        runDeviceTests(TEST_PKG, TEST_PKG + ".LocalNetworkTest",
                "permissionRevoked_checksLocalNetworkRestrictions");
        uninstallPackage(TEST_APP2_PKG, true);
    }
}
