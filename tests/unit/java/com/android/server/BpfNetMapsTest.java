/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server;

import static android.net.ConnectivityManager.FIREWALL_CHAIN_DOZABLE;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_LOW_POWER_STANDBY;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_OEM_DENY_1;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_OEM_DENY_2;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_OEM_DENY_3;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_POWERSAVE;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_RESTRICTED;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_STANDBY;
import static android.net.INetd.PERMISSION_INTERNET;

import static com.android.server.BpfNetMaps.DOZABLE_MATCH;
import static com.android.server.BpfNetMaps.HAPPY_BOX_MATCH;
import static com.android.server.BpfNetMaps.IIF_MATCH;
import static com.android.server.BpfNetMaps.LOCKDOWN_VPN_MATCH;
import static com.android.server.BpfNetMaps.NO_MATCH;
import static com.android.server.BpfNetMaps.PENALTY_BOX_MATCH;
import static com.android.server.BpfNetMaps.POWERSAVE_MATCH;
import static com.android.server.BpfNetMaps.RESTRICTED_MATCH;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.mockito.Mockito.verify;

import android.net.INetd;
import android.os.Build;
import android.os.ServiceSpecificException;

import androidx.test.filters.SmallTest;

import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.BpfMap;
import com.android.net.module.util.Struct.U32;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRule.IgnoreAfter;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import com.android.testutils.DevSdkIgnoreRunner;
import com.android.testutils.TestBpfMap;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

@RunWith(DevSdkIgnoreRunner.class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
public final class BpfNetMapsTest {
    private static final String TAG = "BpfNetMapsTest";

    @Rule
    public final DevSdkIgnoreRule ignoreRule = new DevSdkIgnoreRule();

    private static final int TEST_UID = 10086;
    private static final int[] TEST_UIDS = {10002, 10003};
    private static final String TEST_IF_NAME = "wlan0";
    private static final int TEST_IF_INDEX = 7;
    private static final String CHAINNAME = "fw_dozable";
    private static final U32 UID_RULES_CONFIGURATION_KEY = new U32(0);
    private static final List<Integer> FIREWALL_CHAINS = List.of(
            FIREWALL_CHAIN_DOZABLE,
            FIREWALL_CHAIN_STANDBY,
            FIREWALL_CHAIN_POWERSAVE,
            FIREWALL_CHAIN_RESTRICTED,
            FIREWALL_CHAIN_LOW_POWER_STANDBY,
            FIREWALL_CHAIN_OEM_DENY_1,
            FIREWALL_CHAIN_OEM_DENY_2,
            FIREWALL_CHAIN_OEM_DENY_3
    );

    private BpfNetMaps mBpfNetMaps;

    @Mock INetd mNetd;
    private static final TestBpfMap<U32, U32> sConfigurationMap =
            new TestBpfMap<>(U32.class, U32.class);
    private static final BpfMap<U32, UidOwnerValue> sUidOwnerMap =
            new TestBpfMap<>(U32.class, UidOwnerValue.class);

    static {
        BpfNetMaps.ensureInitialized(makeDependencies());
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mBpfNetMaps = new BpfNetMaps(mNetd);
        sConfigurationMap.clear();
        sUidOwnerMap.clear();
    }

    private static BpfNetMaps.Dependencies makeDependencies() {
        return new BpfNetMaps.Dependencies() {
            @Override
            public BpfMap<U32, U32> getConfigurationMap() {
                return sConfigurationMap;
            }
            @Override
            public BpfMap<U32, UidOwnerValue> getUidOwnerMap() {
                return sUidOwnerMap;
            }
            @Override
            public int getIfIndex(String ifName) {
                return TEST_IF_INDEX;
            }
        };
    }

    @Test
    public void testBpfNetMapsBeforeT() throws Exception {
        assumeFalse(SdkLevel.isAtLeastT());
        mBpfNetMaps.addUidInterfaceRules(TEST_IF_NAME, TEST_UIDS);
        verify(mNetd).firewallAddUidInterfaceRules(TEST_IF_NAME, TEST_UIDS);
        mBpfNetMaps.removeUidInterfaceRules(TEST_UIDS);
        verify(mNetd).firewallRemoveUidInterfaceRules(TEST_UIDS);
        mBpfNetMaps.setNetPermForUids(PERMISSION_INTERNET, TEST_UIDS);
        verify(mNetd).trafficSetNetPermForUids(PERMISSION_INTERNET, TEST_UIDS);
    }

    private void doTestIsChainEnabled(final List<Integer> enableChains) throws Exception {
        long match = 0;
        for (final int chain: enableChains) {
            match |= mBpfNetMaps.getMatchByFirewallChain(chain);
        }
        sConfigurationMap.updateEntry(UID_RULES_CONFIGURATION_KEY, new U32(match));

        for (final int chain: FIREWALL_CHAINS) {
            final String testCase = "EnabledChains: " + enableChains + " CheckedChain: " + chain;
            if (enableChains.contains(chain)) {
                assertTrue("Expected isChainEnabled returns True, " + testCase,
                        mBpfNetMaps.isChainEnabled(chain));
            } else {
                assertFalse("Expected isChainEnabled returns False, " + testCase,
                        mBpfNetMaps.isChainEnabled(chain));
            }
        }
    }

    private void doTestIsChainEnabled(final int enableChain) throws Exception {
        doTestIsChainEnabled(List.of(enableChain));
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testIsChainEnabled() throws Exception {
        doTestIsChainEnabled(FIREWALL_CHAIN_DOZABLE);
        doTestIsChainEnabled(FIREWALL_CHAIN_STANDBY);
        doTestIsChainEnabled(FIREWALL_CHAIN_POWERSAVE);
        doTestIsChainEnabled(FIREWALL_CHAIN_RESTRICTED);
        doTestIsChainEnabled(FIREWALL_CHAIN_LOW_POWER_STANDBY);
        doTestIsChainEnabled(FIREWALL_CHAIN_OEM_DENY_1);
        doTestIsChainEnabled(FIREWALL_CHAIN_OEM_DENY_2);
        doTestIsChainEnabled(FIREWALL_CHAIN_OEM_DENY_3);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testIsChainEnabledMultipleChainEnabled() throws Exception {
        doTestIsChainEnabled(List.of(
                FIREWALL_CHAIN_DOZABLE,
                FIREWALL_CHAIN_STANDBY));
        doTestIsChainEnabled(List.of(
                FIREWALL_CHAIN_DOZABLE,
                FIREWALL_CHAIN_STANDBY,
                FIREWALL_CHAIN_POWERSAVE,
                FIREWALL_CHAIN_RESTRICTED));
        doTestIsChainEnabled(FIREWALL_CHAINS);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testIsChainEnabledInvalidChain() {
        final Class<ServiceSpecificException> expected = ServiceSpecificException.class;
        assertThrows(expected, () -> mBpfNetMaps.isChainEnabled(-1 /* childChain */));
        assertThrows(expected, () -> mBpfNetMaps.isChainEnabled(1000 /* childChain */));
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testIsChainEnabledMissingConfiguration() {
        // sConfigurationMap does not have entry for UID_RULES_CONFIGURATION_KEY
        assertThrows(ServiceSpecificException.class,
                () -> mBpfNetMaps.isChainEnabled(FIREWALL_CHAIN_DOZABLE));
    }

    @Test
    @IgnoreAfter(Build.VERSION_CODES.S_V2)
    public void testIsChainEnabledBeforeT() {
        assertThrows(UnsupportedOperationException.class,
                () -> mBpfNetMaps.isChainEnabled(FIREWALL_CHAIN_DOZABLE));
    }

    private void doTestSetChildChain(final List<Integer> testChains) throws Exception {
        long expectedMatch = 0;
        for (final int chain: testChains) {
            expectedMatch |= mBpfNetMaps.getMatchByFirewallChain(chain);
        }

        assertEquals(0, sConfigurationMap.getValue(UID_RULES_CONFIGURATION_KEY).val);

        for (final int chain: testChains) {
            mBpfNetMaps.setChildChain(chain, true /* enable */);
        }
        assertEquals(expectedMatch, sConfigurationMap.getValue(UID_RULES_CONFIGURATION_KEY).val);

        for (final int chain: testChains) {
            mBpfNetMaps.setChildChain(chain, false /* enable */);
        }
        assertEquals(0, sConfigurationMap.getValue(UID_RULES_CONFIGURATION_KEY).val);
    }

    private void doTestSetChildChain(final int testChain) throws Exception {
        doTestSetChildChain(List.of(testChain));
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testSetChildChain() throws Exception {
        sConfigurationMap.updateEntry(UID_RULES_CONFIGURATION_KEY, new U32(0));
        doTestSetChildChain(FIREWALL_CHAIN_DOZABLE);
        doTestSetChildChain(FIREWALL_CHAIN_STANDBY);
        doTestSetChildChain(FIREWALL_CHAIN_POWERSAVE);
        doTestSetChildChain(FIREWALL_CHAIN_RESTRICTED);
        doTestSetChildChain(FIREWALL_CHAIN_LOW_POWER_STANDBY);
        doTestSetChildChain(FIREWALL_CHAIN_OEM_DENY_1);
        doTestSetChildChain(FIREWALL_CHAIN_OEM_DENY_2);
        doTestSetChildChain(FIREWALL_CHAIN_OEM_DENY_3);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testSetChildChainMultipleChain() throws Exception {
        sConfigurationMap.updateEntry(UID_RULES_CONFIGURATION_KEY, new U32(0));
        doTestSetChildChain(List.of(
                FIREWALL_CHAIN_DOZABLE,
                FIREWALL_CHAIN_STANDBY));
        doTestSetChildChain(List.of(
                FIREWALL_CHAIN_DOZABLE,
                FIREWALL_CHAIN_STANDBY,
                FIREWALL_CHAIN_POWERSAVE,
                FIREWALL_CHAIN_RESTRICTED));
        doTestSetChildChain(FIREWALL_CHAINS);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testSetChildChainInvalidChain() {
        final Class<ServiceSpecificException> expected = ServiceSpecificException.class;
        assertThrows(expected,
                () -> mBpfNetMaps.setChildChain(-1 /* childChain */, true /* enable */));
        assertThrows(expected,
                () -> mBpfNetMaps.setChildChain(1000 /* childChain */, true /* enable */));
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testSetChildChainMissingConfiguration() {
        // sConfigurationMap does not have entry for UID_RULES_CONFIGURATION_KEY
        assertThrows(ServiceSpecificException.class,
                () -> mBpfNetMaps.setChildChain(FIREWALL_CHAIN_DOZABLE, true /* enable */));
    }

    @Test
    @IgnoreAfter(Build.VERSION_CODES.S_V2)
    public void testSetChildChainBeforeT() {
        assertThrows(UnsupportedOperationException.class,
                () -> mBpfNetMaps.setChildChain(FIREWALL_CHAIN_DOZABLE, true /* enable */));
    }

    private void checkUidOwnerValue(final long uid, final long expectedIif,
            final long expectedMatch) throws Exception {
        final UidOwnerValue config = sUidOwnerMap.getValue(new U32(uid));
        if (expectedMatch == 0) {
            assertNull(config);
        } else {
            assertEquals(expectedIif, config.iif);
            assertEquals(expectedMatch, config.rule);
        }
    }

    private void doTestRemoveNaughtyApp(final long enabledMatch) throws Exception {
        sUidOwnerMap.updateEntry(new U32(TEST_UID), new UidOwnerValue(0 /* iif */, enabledMatch));

        mBpfNetMaps.removeNaughtyApp(TEST_UID);

        checkUidOwnerValue(TEST_UID, 0 /* expectedIif */, enabledMatch & ~PENALTY_BOX_MATCH);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testRemoveNaughtyApp() throws Exception {
        doTestRemoveNaughtyApp(PENALTY_BOX_MATCH);

        // PENALTY_BOX_MATCH with other matches
        doTestRemoveNaughtyApp(PENALTY_BOX_MATCH | DOZABLE_MATCH);
        doTestRemoveNaughtyApp(
                PENALTY_BOX_MATCH | DOZABLE_MATCH | POWERSAVE_MATCH | RESTRICTED_MATCH);

        // PENALTY_BOX_MATCH is not enabled
        doTestRemoveNaughtyApp(DOZABLE_MATCH | POWERSAVE_MATCH | RESTRICTED_MATCH);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testRemoveNaughtyAppMissingUid() {
        // sUidOwnerMap does not have entry for TEST_UID
        assertThrows(ServiceSpecificException.class,
                () -> mBpfNetMaps.removeNaughtyApp(TEST_UID));
    }

    @Test
    @IgnoreAfter(Build.VERSION_CODES.S_V2)
    public void testRemoveNaughtyAppBeforeT() {
        assertThrows(UnsupportedOperationException.class,
                () -> mBpfNetMaps.removeNaughtyApp(TEST_UID));
    }

    private void doTestAddNaughtyApp(final long enabledMatch) throws Exception {
        sUidOwnerMap.updateEntry(new U32(TEST_UID), new UidOwnerValue(0 /* iif */, enabledMatch));

        mBpfNetMaps.addNaughtyApp(TEST_UID);

        checkUidOwnerValue(TEST_UID, 0 /* expectedIif */, enabledMatch | PENALTY_BOX_MATCH);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testAddNaughtyApp() throws Exception {
        doTestAddNaughtyApp(NO_MATCH);

        // Other matches are enabled
        doTestAddNaughtyApp(DOZABLE_MATCH);
        doTestAddNaughtyApp(DOZABLE_MATCH | POWERSAVE_MATCH | RESTRICTED_MATCH);

        // PENALTY_BOX_MATCH is already enabled
        doTestAddNaughtyApp(PENALTY_BOX_MATCH | DOZABLE_MATCH);
    }

    @Test
    @IgnoreAfter(Build.VERSION_CODES.S_V2)
    public void testAddNaughtyAppBeforeT() {
        assertThrows(UnsupportedOperationException.class,
                () -> mBpfNetMaps.addNaughtyApp(TEST_UID));
    }

    private void doTestRemoveNiceApp(final long enabledMatch) throws Exception {
        sUidOwnerMap.updateEntry(new U32(TEST_UID), new UidOwnerValue(0 /* iif */, enabledMatch));

        mBpfNetMaps.removeNiceApp(TEST_UID);

        checkUidOwnerValue(TEST_UID, 0 /* expectedIif */, enabledMatch & ~HAPPY_BOX_MATCH);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testRemoveNiceApp() throws Exception {
        doTestRemoveNiceApp(HAPPY_BOX_MATCH);

        // HAPPY_BOX_MATCH with other matches
        doTestRemoveNiceApp(HAPPY_BOX_MATCH | DOZABLE_MATCH);
        doTestRemoveNiceApp(
                HAPPY_BOX_MATCH | DOZABLE_MATCH | POWERSAVE_MATCH | RESTRICTED_MATCH);

        // HAPPY_BOX_MATCH is not enabled
        doTestRemoveNiceApp(DOZABLE_MATCH | POWERSAVE_MATCH | RESTRICTED_MATCH);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testRemoveNiceAppMissingUid() {
        // sUidOwnerMap does not have entry for TEST_UID
        assertThrows(ServiceSpecificException.class,
                () -> mBpfNetMaps.removeNiceApp(TEST_UID));
    }

    @Test
    @IgnoreAfter(Build.VERSION_CODES.S_V2)
    public void testRemoveNiceAppBeforeT() {
        assertThrows(UnsupportedOperationException.class,
                () -> mBpfNetMaps.removeNiceApp(TEST_UID));
    }

    private void doTestAddNiceApp(final long enabledMatch) throws Exception {
        sUidOwnerMap.updateEntry(new U32(TEST_UID), new UidOwnerValue(0 /* iif */, enabledMatch));

        mBpfNetMaps.addNiceApp(TEST_UID);

        checkUidOwnerValue(TEST_UID, 0 /* expectedIif */, enabledMatch | HAPPY_BOX_MATCH);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testAddNiceApp() throws Exception {
        doTestAddNiceApp(NO_MATCH);

        // Other matches are enabled
        doTestAddNiceApp(DOZABLE_MATCH);
        doTestAddNiceApp(DOZABLE_MATCH | POWERSAVE_MATCH | RESTRICTED_MATCH);

        // HAPPY_BOX_MATCH is already enabled
        doTestAddNiceApp(HAPPY_BOX_MATCH | DOZABLE_MATCH);
    }

    @Test
    @IgnoreAfter(Build.VERSION_CODES.S_V2)
    public void testAddNiceAppBeforeT() {
        assertThrows(UnsupportedOperationException.class,
                () -> mBpfNetMaps.addNiceApp(TEST_UID));
    }

    private void doTestUpdateUidLockdownRule(final long enabledMatch, final boolean add)
            throws Exception {
        sUidOwnerMap.updateEntry(new U32(TEST_UID), new UidOwnerValue(0 /* iif */, enabledMatch));

        mBpfNetMaps.updateUidLockdownRule(TEST_UID, add);

        final long expectedMatch = add
                ? enabledMatch | LOCKDOWN_VPN_MATCH : enabledMatch & ~LOCKDOWN_VPN_MATCH;
        checkUidOwnerValue(TEST_UID, 0 /* expectedIif */, expectedMatch);
    }

    private static final boolean ADD = true;
    private static final boolean REMOVE = false;

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testUpdateUidLockdownRule() throws Exception {
        doTestUpdateUidLockdownRule(NO_MATCH, ADD);

        // Other matches are enabled
        doTestUpdateUidLockdownRule(DOZABLE_MATCH, ADD);
        doTestUpdateUidLockdownRule(DOZABLE_MATCH, REMOVE);
        doTestUpdateUidLockdownRule(DOZABLE_MATCH | POWERSAVE_MATCH | RESTRICTED_MATCH, ADD);
        doTestUpdateUidLockdownRule(DOZABLE_MATCH | POWERSAVE_MATCH | RESTRICTED_MATCH, REMOVE);

        // LOCKDOWN_VPN_MATCH is enabled
        doTestUpdateUidLockdownRule(LOCKDOWN_VPN_MATCH, ADD);
        doTestUpdateUidLockdownRule(LOCKDOWN_VPN_MATCH, REMOVE);
        doTestUpdateUidLockdownRule(LOCKDOWN_VPN_MATCH | DOZABLE_MATCH, ADD);
        doTestUpdateUidLockdownRule(LOCKDOWN_VPN_MATCH | DOZABLE_MATCH, REMOVE);
    }

    @Test
    @IgnoreAfter(Build.VERSION_CODES.S_V2)
    public void testUpdateUidLockdownRuleBeforeT() {
        assertThrows(UnsupportedOperationException.class,
                () -> mBpfNetMaps.updateUidLockdownRule(TEST_UID, true /* add */));
    }

    private void doTestAddUidInterfaceRules(final long enabledMatch) throws Exception {
        final int uid0 = TEST_UIDS[0];
        final int uid1 = TEST_UIDS[1];
        final long iif = ((enabledMatch & IIF_MATCH) != 0) ? TEST_IF_INDEX : 0;
        sUidOwnerMap.updateEntry(new U32(uid0), new UidOwnerValue(iif, enabledMatch));
        sUidOwnerMap.updateEntry(new U32(uid1), new UidOwnerValue(iif, enabledMatch));

        mBpfNetMaps.addUidInterfaceRules(TEST_IF_NAME, TEST_UIDS);

        checkUidOwnerValue(uid0, TEST_IF_INDEX, enabledMatch | IIF_MATCH);
        checkUidOwnerValue(uid1, TEST_IF_INDEX, enabledMatch | IIF_MATCH);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testAddUidInterfaceRules() throws Exception {
        final int uid0 = TEST_UIDS[0];
        final int uid1 = TEST_UIDS[1];

        mBpfNetMaps.addUidInterfaceRules(TEST_IF_NAME, TEST_UIDS);

        checkUidOwnerValue(uid0, TEST_IF_INDEX, IIF_MATCH);
        checkUidOwnerValue(uid1, TEST_IF_INDEX, IIF_MATCH);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testAddUidInterfaceRulesWithOtherMatch() throws Exception {
        final int uid0 = TEST_UIDS[0];
        final int uid1 = TEST_UIDS[1];
        final long enabledMatch = DOZABLE_MATCH | POWERSAVE_MATCH | RESTRICTED_MATCH;
        sUidOwnerMap.updateEntry(new U32(uid0), new UidOwnerValue(0 /* iif */, enabledMatch));
        sUidOwnerMap.updateEntry(new U32(uid1), new UidOwnerValue(0 /* iif */, enabledMatch));

        mBpfNetMaps.addUidInterfaceRules(TEST_IF_NAME, TEST_UIDS);

        checkUidOwnerValue(uid0, TEST_IF_INDEX, enabledMatch | IIF_MATCH);
        checkUidOwnerValue(uid1, TEST_IF_INDEX, enabledMatch | IIF_MATCH);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testAddUidInterfaceRulesWithExistingIifMatch() throws Exception {
        final int uid0 = TEST_UIDS[0];
        final int uid1 = TEST_UIDS[1];
        final long enabledMatch = IIF_MATCH | DOZABLE_MATCH | POWERSAVE_MATCH | RESTRICTED_MATCH;
        sUidOwnerMap.updateEntry(new U32(uid0), new UidOwnerValue(TEST_IF_INDEX + 1, enabledMatch));
        sUidOwnerMap.updateEntry(new U32(uid1), new UidOwnerValue(0 /* iif */, enabledMatch));

        mBpfNetMaps.addUidInterfaceRules(TEST_IF_NAME, TEST_UIDS);

        checkUidOwnerValue(uid0, TEST_IF_INDEX, enabledMatch);
        checkUidOwnerValue(uid1, TEST_IF_INDEX, enabledMatch);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testAddUidInterfaceRulesWithNullInterface() throws Exception {
        final int uid0 = TEST_UIDS[0];
        final int uid1 = TEST_UIDS[1];
        final long enabledMatch = IIF_MATCH | DOZABLE_MATCH | POWERSAVE_MATCH | RESTRICTED_MATCH;
        sUidOwnerMap.updateEntry(new U32(uid0), new UidOwnerValue(TEST_IF_INDEX, enabledMatch));
        sUidOwnerMap.updateEntry(new U32(uid1), new UidOwnerValue(0 /* iif */, enabledMatch));

        mBpfNetMaps.addUidInterfaceRules(null /* ifName */, TEST_UIDS);

        checkUidOwnerValue(uid0, 0 /* expectedIif */, enabledMatch);
        checkUidOwnerValue(uid1, 0 /* expectedIif */, enabledMatch);
    }

    @Test
    @IgnoreAfter(Build.VERSION_CODES.S_V2)
    public void testAddUidInterfaceRulesBeforeT() {
        assertThrows(UnsupportedOperationException.class,
                () -> mBpfNetMaps.addUidInterfaceRules(TEST_IF_NAME, TEST_UIDS));
    }

    private void doTestRemoveUidInterfaceRules(final long iif, final long enabledMatch)
            throws Exception {
        final int uid0 = TEST_UIDS[0];
        final int uid1 = TEST_UIDS[1];
        sUidOwnerMap.updateEntry(new U32(uid0), new UidOwnerValue(iif, enabledMatch));
        sUidOwnerMap.updateEntry(new U32(uid1), new UidOwnerValue(iif, enabledMatch));

        mBpfNetMaps.removeUidInterfaceRules(TEST_UIDS);

        checkUidOwnerValue(uid0, 0 /* expectedIif */, enabledMatch & ~IIF_MATCH);
        checkUidOwnerValue(uid1, 0 /* expectedIif */, enabledMatch & ~IIF_MATCH);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testRemoveUidInterfaceRules() throws Exception {
        doTestRemoveUidInterfaceRules(TEST_IF_INDEX, IIF_MATCH);
        doTestRemoveUidInterfaceRules(0 /* iif */, IIF_MATCH);

        // IIF_MATCH and other matches are enabled
        doTestRemoveUidInterfaceRules(TEST_IF_INDEX, IIF_MATCH | DOZABLE_MATCH);
        doTestRemoveUidInterfaceRules(0 /* iif */, IIF_MATCH | DOZABLE_MATCH);

        // IIF_MATCH is not enabled and other matches are enabled
        doTestRemoveUidInterfaceRules(0 /* iif */, DOZABLE_MATCH);
        doTestRemoveUidInterfaceRules(
                0 /* iif */, DOZABLE_MATCH | POWERSAVE_MATCH | RESTRICTED_MATCH);
    }

    @Test
    @IgnoreAfter(Build.VERSION_CODES.S_V2)
    public void testRemoveUidInterfaceRulesBeforeT() {
        assertThrows(UnsupportedOperationException.class,
                () -> mBpfNetMaps.removeUidInterfaceRules(TEST_UIDS));
    }
}
