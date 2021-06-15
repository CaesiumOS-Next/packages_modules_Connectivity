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
import org.junit.Rule;
import org.junit.Test;

public class NetworkPolicyManagerTest extends AbstractRestrictBackgroundNetworkTestCase {
    private static final boolean METERED = true;
    private static final boolean NON_METERED = false;

    @Rule
    public final MeterednessConfigurationRule mMeterednessConfiguration =
            new MeterednessConfigurationRule();

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

        // Mark network as metered.
        mMeterednessConfiguration.configureNetworkMeteredness(true);
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();

        setBatterySaverMode(false);
        setRestrictBackground(false);
        setRestrictedNetworkingMode(false);
        unregisterNetworkCallback();
        mMeterednessConfiguration.resetNetworkMeteredness();
    }

    @Test
    public void testIsUidNetworkingBlocked_withNormalStatus() throws Exception {
        // Refer to NetworkPolicyManagerService#isUidNetworkingBlockedInternal(), this test is to
        // test the cases of NTWK_ALLOWED_NON_METERED and NTWK_ALLOWED_DEFAULT.
        // If mUid is not blocked by data saver mode or power saver mode, no matter the network is
        // metered or non-metered, mUid shouldn't be blocked.
        assertFalse(isUidNetworkingBlocked(mUid, METERED)); // Match NTWK_ALLOWED_DEFAULT
        assertFalse(isUidNetworkingBlocked(mUid, NON_METERED)); // Match NTWK_ALLOWED_NON_METERED
    }

    @Test
    public void testIsUidNetworkingBlocked_withSystemUid() throws Exception {
        // Refer to NetworkPolicyManagerService#isUidNetworkingBlockedInternal(), this test is to
        // test the case of NTWK_ALLOWED_SYSTEM.
        // SYSTEM_UID will never be blocked.
        assertFalse(isUidNetworkingBlocked(SYSTEM_UID, METERED)); // Match NTWK_ALLOWED_SYSTEM
        assertFalse(isUidNetworkingBlocked(SYSTEM_UID, NON_METERED)); // Match NTWK_ALLOWED_SYSTEM
        setRestrictBackground(true);
        setBatterySaverMode(true);
        setRestrictedNetworkingMode(true);
        assertBackgroundNetworkAccess(false);
        assertFalse(isUidNetworkingBlocked(SYSTEM_UID, METERED)); // Match NTWK_ALLOWED_SYSTEM
        assertFalse(isUidNetworkingBlocked(SYSTEM_UID, NON_METERED)); // Match NTWK_ALLOWED_SYSTEM
        setRestrictBackground(false);
        setBatterySaverMode(false);
        setRestrictedNetworkingMode(false);
        assertBackgroundNetworkAccess(true);
    }

    @Test
    public void testIsUidNetworkingBlocked_withDataSaverMode() throws Exception {
        // Refer to NetworkPolicyManagerService#isUidNetworkingBlockedInternal(), this test is to
        // test the cases of NTWK_ALLOWED_NON_METERED and NTWK_BLOCKED_DENYLIST and
        // NTWK_ALLOWED_ALLOWLIST and NTWK_ALLOWED_TMP_ALLOWLIST and NTWK_BLOCKED_BG_RESTRICT.
        // Enable restrict background and mUid will be blocked because it's not in the foreground.
        setRestrictBackground(true);
        assertBackgroundNetworkAccess(false);
        assertTrue(isUidNetworkingBlocked(mUid, METERED)); // Match NTWK_BLOCKED_BG_RESTRICT
        // Although restrict background is enabled and mUid is in the background, but mUid will not
        // be blocked if network is non-metered.
        assertFalse(isUidNetworkingBlocked(mUid, NON_METERED)); // Match NTWK_ALLOWED_NON_METERED
        // Add mUid into the restrict background blacklist.
        addRestrictBackgroundBlacklist(mUid);
        assertTrue(isUidNetworkingBlocked(mUid, METERED)); // Match NTWK_BLOCKED_DENYLIST
        // Although mUid is in the restrict background blacklist, but mUid won't be blocked if the
        // network is non-metered.
        assertFalse(isUidNetworkingBlocked(mUid, NON_METERED)); // Match NTWK_ALLOWED_NON_METERED
        removeRestrictBackgroundBlacklist(mUid);
        // Add mUid into the restrict background whitelist.
        addRestrictBackgroundWhitelist(mUid);
        assertFalse(isUidNetworkingBlocked(mUid, METERED)); // Match NTWK_ALLOWED_ALLOWLIST
        assertFalse(isUidNetworkingBlocked(mUid, NON_METERED)); // Match NTWK_ALLOWED_NON_METERED
        removeRestrictBackgroundWhitelist(mUid);
        // Make TEST_APP2_PKG go to foreground and mUid will be allowed temporarily.
        launchComponentAndAssertNetworkAccess(TYPE_COMPONENT_ACTIVTIY);
        assertForegroundState();
        assertFalse(isUidNetworkingBlocked(mUid, METERED)); // Match NTWK_ALLOWED_TMP_ALLOWLIST
        // Back to background.
        finishActivity();
        assertBackgroundNetworkAccess(false);
        assertTrue(isUidNetworkingBlocked(mUid, METERED)); // Match NTWK_BLOCKED_BG_RESTRICT
        setRestrictBackground(false);
        assertBackgroundNetworkAccess(true);
    }

    @Test
    public void testIsUidNetworkingBlocked_withRestrictedNetworkingMode() throws Exception {
        // Refer to NetworkPolicyManagerService#isUidNetworkingBlockedInternal(), this test is to
        // test the cases of NTWK_BLOCKED_RESTRICTED_MODE.
        // All apps should be blocked if restricted networking mode is enabled except for those apps
        // who have CONNECTIVITY_USE_RESTRICTED_NETWORKS permission.
        // This test won't test if an app who has CONNECTIVITY_USE_RESTRICTED_NETWORKS will not be
        // blocked because CONNECTIVITY_USE_RESTRICTED_NETWORKS is a signature/privileged permission
        // that CTS cannot acquire. Also it's not good for this test to use those privileged apps
        // which have CONNECTIVITY_USE_RESTRICTED_NETWORKS to test because there is no guarantee
        // that those apps won't remove this permission someday, and if it happens, then this test
        // will fail.
        setRestrictedNetworkingMode(true);
        assertBackgroundNetworkAccess(false);
        assertTrue(isUidNetworkingBlocked(mUid, METERED)); // Match NTWK_BLOCKED_RESTRICTED_MODE
        assertTrue(isUidNetworkingBlocked(mUid, NON_METERED)); // Match NTWK_BLOCKED_RESTRICTED_MODE
        setRestrictedNetworkingMode(false);
        assertBackgroundNetworkAccess(true);
    }

    @Test
    public void testIsUidNetworkingBlocked_withPowerSaverMode() throws Exception {
        // Refer to NetworkPolicyManagerService#isUidNetworkingBlockedInternal(), this test is to
        // test the cases of NTWK_BLOCKED_POWER and NTWK_ALLOWED_NON_METERED and
        // NTWK_ALLOWED_DEFAULT.
        // mUid should be blocked if power saver mode is enabled.
        setBatterySaverMode(true);
        assertBackgroundNetworkAccess(false);
        assertTrue(isUidNetworkingBlocked(mUid, METERED)); // Match NTWK_BLOCKED_POWER
        assertTrue(isUidNetworkingBlocked(mUid, NON_METERED)); // Match NTWK_BLOCKED_POWER
        // Add TEST_APP2_PKG into power saver mode whitelist, its uid rule is RULE_ALLOW_ALL and it
        // shouldn't be blocked.
        addPowerSaveModeWhitelist(TEST_APP2_PKG);
        assertFalse(isUidNetworkingBlocked(mUid, METERED)); // Match NTWK_ALLOWED_DEFAULT
        assertFalse(isUidNetworkingBlocked(mUid, NON_METERED)); // Match NTWK_ALLOWED_NON_METERED
        removePowerSaveModeWhitelist(TEST_APP2_PKG);
        setBatterySaverMode(false);
        assertBackgroundNetworkAccess(true);
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
        assertBackgroundNetworkAccess(false);
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
