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

import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(DeviceJUnit4ClassRunner.class)
public class HostsideVpnTests extends BaseHostJUnit4Test {

    private static final String TEST_PKG = "com.android.cts.net.hostside";

    @Test
    public void testChangeUnderlyingNetworks() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest", "testChangeUnderlyingNetworks");
    }

    @Test
    public void testDefault() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest", "testDefault");
    }

    @Test
    public void testAppAllowed() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest", "testAppAllowed");
    }

    @Test
    public void testAppDisallowed() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest", "testAppDisallowed");
    }

    @Test
    public void testGetConnectionOwnerUidSecurity() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest", "testGetConnectionOwnerUidSecurity");
    }

    @Test
    public void testSetProxy() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest", "testSetProxy");
    }

    @Test
    public void testSetProxyDisallowedApps() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest", "testSetProxyDisallowedApps");
    }

    @Test
    public void testNoProxy() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest", "testNoProxy");
    }

    @Test
    public void testBindToNetworkWithProxy() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest", "testBindToNetworkWithProxy");
    }

    @Test
    public void testVpnMeterednessWithNoUnderlyingNetwork() throws Exception {
        runDeviceTests(
                TEST_PKG, TEST_PKG + ".VpnTest", "testVpnMeterednessWithNoUnderlyingNetwork");
    }

    @Test
    public void testVpnMeterednessWithNullUnderlyingNetwork() throws Exception {
        runDeviceTests(
                TEST_PKG, TEST_PKG + ".VpnTest", "testVpnMeterednessWithNullUnderlyingNetwork");
    }

    @Test
    public void testVpnMeterednessWithNonNullUnderlyingNetwork() throws Exception {
        runDeviceTests(
                TEST_PKG, TEST_PKG + ".VpnTest", "testVpnMeterednessWithNonNullUnderlyingNetwork");
    }

    @Test
    public void testAlwaysMeteredVpnWithNullUnderlyingNetwork() throws Exception {
        runDeviceTests(
                TEST_PKG, TEST_PKG + ".VpnTest", "testAlwaysMeteredVpnWithNullUnderlyingNetwork");
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
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest", "testB141603906");
    }

    @Test
    public void testDownloadWithDownloadManagerDisallowed() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest",
                "testDownloadWithDownloadManagerDisallowed");
    }

    @Test
    public void testExcludedRoutes() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest", "testExcludedRoutes");
    }

    @Test
    public void testIncludedRoutes() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest", "testIncludedRoutes");
    }

    @Test
    public void testInterleavedRoutes() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest", "testInterleavedRoutes");
    }

    @Test
    public void testBlockIncomingPackets() throws Exception {
        runDeviceTests(TEST_PKG, TEST_PKG + ".VpnTest", "testBlockIncomingPackets");
    }
}
