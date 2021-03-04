/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.compat.cts.CompatChangeGatingTestCase;

import com.android.tradefed.device.DeviceNotAvailableException;

import com.google.common.collect.ImmutableSet;

/**
 * Tests for the {@link android.net.LinkProperties#EXCLUDED_ROUTES} compatibility change.
 */
public class HostsideLinkPropertiesGatingTests extends CompatChangeGatingTestCase {
    private static final String TEST_APK = "CtsHostsideNetworkTestsApp3.apk";
    private static final String TEST_PKG = "com.android.cts.net.hostside.app3";
    private static final String TEST_CLASS = ".ExcludedRoutesGatingTest";

    private static final long LINK_PROPERTIES_EXCLUDE_ROUTES_CHANGE_ID = 157330618L;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        installPackage(TEST_APK, true);
    }

    public void testExcludedRoutesEnabled() throws Exception {
        runDeviceCompatTest("testExcludedRoutesEnabled", true /* enableChange */);
    }

    public void testExcludedRoutesDisabled() throws Exception {
        runDeviceCompatTest("testExcludedRoutesDisabled", false /* enableChange */);
    }

    private void runDeviceCompatTest(String methodName, boolean enableChange)
            throws DeviceNotAvailableException {
        runDeviceCompatTest(TEST_PKG, TEST_CLASS, methodName,
                enableChange ? ImmutableSet.of(LINK_PROPERTIES_EXCLUDE_ROUTES_CHANGE_ID)
                        : ImmutableSet.of(),
                enableChange ? ImmutableSet.of()
                        : ImmutableSet.of(LINK_PROPERTIES_EXCLUDE_ROUTES_CHANGE_ID));
    }
}
