/*
 * Copyright (C) 2016 The Android Open Source Project
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

import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

@RunWith(DeviceJUnit4ClassRunner.class)
public class HostsideVpnTests extends BaseHostJUnit4Test {

    private static ITestDevice sDevice;
    private static final String TEST_PKG = HostsideNetworkTestCase.TEST_PKG;

    @Before
    public void setUp() throws Exception {
        // Don't uninstall and reinstall the test app before every testcase. This makes the test
        // about 2x faster because the consent dialog is displayed and clicked through only once.
        sDevice = getDevice();
        if (!sDevice.isPackageInstalled(TEST_PKG)) {
            final String testApkName =  HostsideNetworkTestCase.getTestApkName(sDevice);
            sDevice.installPackage(new File(testApkName), false /* reinstall */);
        }
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
        if (sDevice != null) {
            sDevice.uninstallPackage(TEST_PKG);
        }
    }


    private void runDeviceTest(String name) throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest", name);
    }

    @Test
    public void testChangeUnderlyingNetworks() throws Exception {
        runDeviceTest("testChangeUnderlyingNetworks");
    }

    @Test
    public void testDefault() throws Exception {
        runDeviceTest("testDefault");
    }

    @Test
    public void testAppAllowed() throws Exception {
        runDeviceTest("testAppAllowed");
    }

    @Test
    public void testAppDisallowed() throws Exception {
        runDeviceTest("testAppDisallowed");
    }

    @Test
    public void testGetConnectionOwnerUidSecurity() throws Exception {
        runDeviceTest("testGetConnectionOwnerUidSecurity");
    }

    @Test
    public void testSetProxy() throws Exception {
        runDeviceTest("testSetProxy");
    }

    @Test
    public void testSetProxyDisallowedApps() throws Exception {
        runDeviceTest("testSetProxyDisallowedApps");
    }

    @Test
    public void testNoProxy() throws Exception {
        runDeviceTest("testNoProxy");
    }

    @Test
    public void testBindToNetworkWithProxy() throws Exception {
        runDeviceTest("testBindToNetworkWithProxy");
    }

    @Test
    public void testVpnMeterednessWithNoUnderlyingNetwork() throws Exception {
        runDeviceTest("testVpnMeterednessWithNoUnderlyingNetwork");
    }

    @Test
    public void testVpnMeterednessWithNullUnderlyingNetwork() throws Exception {
        runDeviceTest("testVpnMeterednessWithNullUnderlyingNetwork");
    }

    @Test
    public void testVpnMeterednessWithNonNullUnderlyingNetwork() throws Exception {
        runDeviceTest("testVpnMeterednessWithNonNullUnderlyingNetwork");
    }

    @Test
    public void testAlwaysMeteredVpnWithNullUnderlyingNetwork() throws Exception {
        runDeviceTest("testAlwaysMeteredVpnWithNullUnderlyingNetwork");
    }

    @Test
    public void testAlwaysMeteredVpnWithNonNullUnderlyingNetwork() throws Exception {
        runDeviceTests(
                TEST_PKG,
                TEST_PKG + ".VpnTest",
                "testAlwaysMeteredVpnWithNonNullUnderlyingNetwork");
    }

    @Test
    public void testB141603906() throws Exception {
        runDeviceTest("testB141603906");
    }

    @Test
    public void testDownloadWithDownloadManagerDisallowed() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest",
                "testDownloadWithDownloadManagerDisallowed");
    }

    @Test
    public void testExcludedRoutes() throws Exception {
        runDeviceTest("testExcludedRoutes");
    }

    @Test
    public void testIncludedRoutes() throws Exception {
        runDeviceTest("testIncludedRoutes");
    }

    @Test
    public void testInterleavedRoutes() throws Exception {
        runDeviceTest("testInterleavedRoutes");
    }

    @Test
    public void testBlockIncomingPackets() throws Exception {
        runDeviceTest("testBlockIncomingPackets");
    }
}
