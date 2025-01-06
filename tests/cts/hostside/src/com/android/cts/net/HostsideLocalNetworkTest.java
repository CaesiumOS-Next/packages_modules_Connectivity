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

import org.junit.Test;

public class HostsideLocalNetworkTest extends HostsideNetworkTestCase {

    @Test
    public void testLocalNetworkRestrictionsWithoutPermissions() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".LocalNetworkTest",
                "testMissingPermission_dropsEgressUdpPacket");
        runDeviceTests(TEST_PKG, TEST_PKG + ".LocalNetworkTest",
                "testMissingPermission_dropsEgressTcpPacket");
//        runDeviceTests(TEST_PKG, TEST_PKG + ".LocalNetworkTest",
//                "testMissingPermission_dropsIngressTcpPacket");
    }

    @Test
    public void testLocalNetworkRestrictionsWithPermissions() throws Exception {
        uninstallPackage(TEST_PKG, true); // this might crash the test..? not technically the same package
        installPackage(TEST_APK, true);
        runDeviceTests(TEST_PKG, TEST_PKG + ".LocalNetworkTest",
                "testPermissionGranted_sendsUdpPacketToLocalNet");
//        runDeviceTests(TEST_PKG, TEST_PKG + ".LocalNetworkTest",
//                "permissionRevoked_checksLocalNetworkRestrictions");
    }
}
