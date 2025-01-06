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

import static org.junit.Assume.assumeTrue;

import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.host.HostFlagsValueProvider;

import org.junit.Rule;
import org.junit.Test;

public class HostsideLocalNetworkTest extends HostsideNetworkTestCase {

    @Test
    public void testLocalNetworkRestrictions() throws Exception {
        // Local network restrictions were introduced as an opt-in in Baklava
        assumeTrue(getDevice().getApiLevel() >= 35);
        getDevice().executeShellCommand("am compat enable RESTRICT_LOCAL_NETWORK " + TEST_APP2_PKG);
        getDevice().executeAdbCommand("reboot");  // will probably kill test?
        uninstallPackage(TEST_APP2_PKG, false);
        installPackage(TEST_APP2_APK, false);

        runDeviceTests(TEST_PKG, TEST_PKG + ".LocalNetworkTest",
                "testMissingPermission_dropsPacketToLocalNet");
//        runDeviceTests(TEST_PKG, TEST_PKG + ".LocalNetworkTest",
//                "permissionRevoked_checksLocalNetworkRestrictions");
        uninstallPackage(TEST_APP2_PKG, true);
    }
}
