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

package com.android.cts.net.hostside;

import static android.os.Process.SYSTEM_UID;

import static com.android.cts.net.hostside.NetworkPolicyTestUtils.canChangeActiveNetworkMeteredness;
import static com.android.cts.net.hostside.NetworkPolicyTestUtils.isUidNetworkingBlocked;
import static com.android.cts.net.hostside.NetworkPolicyTestUtils.isUidRestrictedOnMeteredNetworks;
import static com.android.cts.net.hostside.NetworkPolicyTestUtils.setRestrictBackground;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class NetworkPolicyManagerTest extends AbstractRestrictBackgroundNetworkTestCase {
    private static final int RETRY_DELAY_MILLIS = 200;

    @Before
    public void setUp() throws Exception {
        super.setUp();

        assumeTrue(canChangeActiveNetworkMeteredness());

        registerBroadcastReceiver();

        removeRestrictBackgroundWhitelist(mUid);
        removeRestrictBackgroundBlacklist(mUid);
        assertRestrictBackgroundChangedReceived(0);

        // Initial state
        setBatterySaverMode(false);
        setRestrictBackground(false);
        setRestrictedNetworkingMode(false);
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();

        setBatterySaverMode(false);
        setRestrictBackground(false);
        setRestrictedNetworkingMode(false);
        unregisterNetworkCallback();
    }

    // When power saver mode or restrict background is enabled, NetworkPolicy may need to take some
    // time to update the rules of uids. So having a retry function to reduce the flaky rate.
    private boolean isUidNetworkingBlockedWithRetry(int uid, boolean metered, int retryTimes,
            boolean expectedResult) throws Exception {
        for (int i = 0; i < retryTimes; i++) {
            if (expectedResult == isUidNetworkingBlocked(uid, metered)) return expectedResult;
            Thread.sleep(RETRY_DELAY_MILLIS);
        }
        return !expectedResult;
    }

    @Test
    public void testIsUidNetworkingBlocked() throws Exception {
        final boolean metered = true;
        final boolean nonMetered = false;
        // SYSTEM_UID will never be blocked.
        assertFalse(isUidNetworkingBlocked(SYSTEM_UID, metered));
        assertFalse(isUidNetworkingBlocked(SYSTEM_UID, nonMetered));

        // If mUid is not blocked by restrict background or data saver mode, no matter the network
        // is metered or non-metered, mUid shouldn't be blocked.
        assertFalse(isUidNetworkingBlocked(mUid, metered));
        assertFalse(isUidNetworkingBlocked(mUid, nonMetered));

        // Enable restrict background and mUid will be blocked because it's not in the foreground.
        setRestrictBackground(true);
        assertTrue(isUidNetworkingBlockedWithRetry(mUid, metered, 5 /* retryTimes */,
                true /* expectedResult */));
        // Although restrict background is enabled and mUid is in the background, but mUid will not
        // be blocked if network is non-metered.
        assertFalse(isUidNetworkingBlocked(mUid, nonMetered));
        // Add mUid into the restrict background blacklist.
        addRestrictBackgroundBlacklist(mUid);
        assertTrue(isUidNetworkingBlocked(mUid, metered));
        // Although mUid is in the restrict background blacklist, but mUid won't be blocked if the
        // network is non-metered.
        assertFalse(isUidNetworkingBlocked(mUid, nonMetered));
        removeRestrictBackgroundBlacklist(mUid);
        // Add mUid into the restrict background whitelist.
        addRestrictBackgroundWhitelist(mUid);
        assertFalse(isUidNetworkingBlocked(mUid, metered));
        assertFalse(isUidNetworkingBlocked(mUid, nonMetered));
        removeRestrictBackgroundWhitelist(mUid);
        // Make TEST_APP2_PKG go to foreground and mUid will be allowed temporarily.
        launchComponentAndAssertNetworkAccess(TYPE_COMPONENT_ACTIVTIY);
        assertForegroundState();
        assertFalse(isUidNetworkingBlocked(mUid, metered));
        // Back to background.
        finishActivity();
        // assertBackgroundNetworkAccess(false);
        assertBackgroundState();
        assertTrue(isUidNetworkingBlocked(mUid, metered));
        setRestrictBackground(false);

        // All apps should be blocked if restricted networking mode is enabled except for those apps
        // who have CONNECTIVITY_USE_RESTRICTED_NETWORKS permission.
        // This test won't test if an app who has CONNECTIVITY_USE_RESTRICTED_NETWORKS will not be
        // blocked because CONNECTIVITY_USE_RESTRICTED_NETWORKS is a signature/privileged permission
        // that CTS cannot acquire. Also it's not good for this test to use those privileged apps
        // which have CONNECTIVITY_USE_RESTRICTED_NETWORKS to test because there is no guarantee
        // that those apps won't remove this permission someday, and if it happens, then this test
        // will fail.
        setRestrictedNetworkingMode(true);
        assertTrue(isUidNetworkingBlockedWithRetry(mUid, metered, 5 /* retryTimes */,
                true /* expectedResult */));
        assertTrue(isUidNetworkingBlocked(mUid, nonMetered));
        setRestrictedNetworkingMode(false);

        // mUid should be blocked if power saver mode is enabled.
        setBatterySaverMode(true);
        assertTrue(isUidNetworkingBlockedWithRetry(mUid, metered, 5 /* retryTimes */,
                true /* expectedResult */));
        assertTrue(isUidNetworkingBlocked(mUid, nonMetered));
        // Add TEST_APP2_PKG into power saver mode whitelist, then it shouldn't be blocked.
        addPowerSaveModeWhitelist(TEST_APP2_PKG);
        assertFalse(isUidNetworkingBlocked(mUid, metered));
        assertFalse(isUidNetworkingBlocked(mUid, nonMetered));
        removePowerSaveModeWhitelist(TEST_APP2_PKG);
        setBatterySaverMode(false);
    }

    @Test
    public void testIsUidRestrictedOnMeteredNetworks() throws Exception {
        // isUidRestrictedOnMeteredNetworks() will only return true when restrict background is
        // enabled and mUid is not in the restrict background whitelist and TEST_APP2_PKG is not in
        // the foreground. For other cases, it will return false.
        setRestrictBackground(true);
        assertTrue(isUidRestrictedOnMeteredNetworks(mUid));
        // Make TEST_APP2_PKG go to foreground and isUidRestrictedOnMeteredNetworks() will return
        // false.
        launchComponentAndAssertNetworkAccess(TYPE_COMPONENT_ACTIVTIY);
        assertForegroundState();
        assertFalse(isUidRestrictedOnMeteredNetworks(mUid));
        // Back to background.
        finishActivity();
        assertBackgroundState();
        // Add mUid into restrict background whitelist and isUidRestrictedOnMeteredNetworks() will
        // return false.
        addRestrictBackgroundWhitelist(mUid);
        assertFalse(isUidRestrictedOnMeteredNetworks(mUid));
        removeRestrictBackgroundWhitelist(mUid);
        // Restrict background is disabled and isUidRestrictedOnMeteredNetworks() will return false.
        setRestrictBackground(false);
        assertFalse(isUidRestrictedOnMeteredNetworks(mUid));
    }
}
