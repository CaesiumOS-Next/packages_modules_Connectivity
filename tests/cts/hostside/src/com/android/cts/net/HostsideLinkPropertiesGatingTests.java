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

import static android.net.LinkProperties.EXCLUDED_ROUTES;

import android.compat.cts.CompatChangeGatingTestCase;

import com.google.common.collect.ImmutableSet;

import java.util.Collections;

/**
 * Tests for the {@link android.net.LinkProperties#EXCLUDED_ROUTES} compatibility change.
 */
public class HostsideLinkPropertiesGatingTests extends CompatChangeGatingTestCase {
    private static final String TEST_APK = "CtsHostsideNetworkTestsApp3.apk";
    private static final String TEST_PKG = "com.android.cts.net.hostside.app3";
    private static final String TEST_CLASS = ".ExcludedRoutesGatingTest";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        installPackage(TEST_APK, true);
    }

    public void testExcludedRoutesEnabled() throws Exception {
        runDeviceCompatTest("testExcludedRoutesEnabled");
    }

    public void testExcludedRoutesDisabledPreT() throws Exception {
        runDeviceCompatTest("testExcludedRoutesDisabledPreT");
    }

    public void testExcludedRoutesDisabledByOverride() throws Exception {
        runDeviceCompatTestWithChangeDisabled("testExcludedRoutesDisabledByOverride");
    }

    public void testExcludedRoutesEnabledByOverridePreT() throws Exception {
        runDeviceCompatTestWithChangeEnabled("testExcludedRoutesEnabledByOverridePreT");
    }

    private void runDeviceCompatTest(String methodName) throws Exception {
        runDeviceCompatTest(TEST_PKG, TEST_CLASS, methodName, Collections.emptySet(),
                Collections.emptySet());
    }

    private void runDeviceCompatTestWithChangeEnabled(String methodName) throws Exception {
        runDeviceCompatTest(TEST_PKG, TEST_CLASS, methodName, ImmutableSet.of(EXCLUDED_ROUTES),
                Collections.emptySet());
    }

    private void runDeviceCompatTestWithChangeDisabled(String methodName) throws Exception {
        runDeviceCompatTest(TEST_PKG, TEST_CLASS, methodName, Collections.emptySet(),
                ImmutableSet.of(EXCLUDED_ROUTES));
    }
}
