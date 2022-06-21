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
import static android.net.ConnectivityManager.FIREWALL_RULE_ALLOW;
import static android.net.ConnectivityManager.FIREWALL_RULE_DENY;
import static android.net.INetd.PERMISSION_INTERNET;

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
import android.system.ErrnoException;

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
    private static final String IFNAME = "wlan0";
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

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mBpfNetMaps = new BpfNetMaps(mNetd);
        BpfNetMaps.initialize(makeDependencies());
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
        };
    }

    @Test
    public void testBpfNetMapsBeforeT() throws Exception {
        assumeFalse(SdkLevel.isAtLeastT());
        mBpfNetMaps.addUidInterfaceRules(IFNAME, TEST_UIDS);
        verify(mNetd).firewallAddUidInterfaceRules(IFNAME, TEST_UIDS);
        mBpfNetMaps.removeUidInterfaceRules(TEST_UIDS);
        verify(mNetd).firewallRemoveUidInterfaceRules(TEST_UIDS);
        mBpfNetMaps.setNetPermForUids(PERMISSION_INTERNET, TEST_UIDS);
        verify(mNetd).trafficSetNetPermForUids(PERMISSION_INTERNET, TEST_UIDS);
    }

    private void doTestGetChainEnabled(final List<Integer> enableChains) throws Exception {
        long match = 0;
        for (final int chain: enableChains) {
            match |= mBpfNetMaps.getMatchByFirewallChain(chain);
        }
        sConfigurationMap.updateEntry(UID_RULES_CONFIGURATION_KEY, new U32(match));

        for (final int chain: FIREWALL_CHAINS) {
            final String testCase = "EnabledChains: " + enableChains + " CheckedChain: " + chain;
            if (enableChains.contains(chain)) {
                assertTrue("Expected getChainEnabled returns True, " + testCase,
                        mBpfNetMaps.getChainEnabled(chain));
            } else {
                assertFalse("Expected getChainEnabled returns False, " + testCase,
                        mBpfNetMaps.getChainEnabled(chain));
            }
        }
    }

    private void doTestGetChainEnabled(final int enableChain) throws Exception {
        doTestGetChainEnabled(List.of(enableChain));
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testGetChainEnabled() throws Exception {
        doTestGetChainEnabled(FIREWALL_CHAIN_DOZABLE);
        doTestGetChainEnabled(FIREWALL_CHAIN_STANDBY);
        doTestGetChainEnabled(FIREWALL_CHAIN_POWERSAVE);
        doTestGetChainEnabled(FIREWALL_CHAIN_RESTRICTED);
        doTestGetChainEnabled(FIREWALL_CHAIN_LOW_POWER_STANDBY);
        doTestGetChainEnabled(FIREWALL_CHAIN_OEM_DENY_1);
        doTestGetChainEnabled(FIREWALL_CHAIN_OEM_DENY_2);
        doTestGetChainEnabled(FIREWALL_CHAIN_OEM_DENY_3);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testGetChainEnabledMultipleChainEnabled() throws Exception {
        doTestGetChainEnabled(List.of(
                FIREWALL_CHAIN_DOZABLE,
                FIREWALL_CHAIN_STANDBY));
        doTestGetChainEnabled(List.of(
                FIREWALL_CHAIN_DOZABLE,
                FIREWALL_CHAIN_STANDBY,
                FIREWALL_CHAIN_POWERSAVE,
                FIREWALL_CHAIN_RESTRICTED));
        doTestGetChainEnabled(FIREWALL_CHAINS);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testGetChainEnabledInvalidChain() {
        final Class<ServiceSpecificException> expected = ServiceSpecificException.class;
        assertThrows(expected, () -> mBpfNetMaps.getChainEnabled(-1 /* childChain */));
        assertThrows(expected, () -> mBpfNetMaps.getChainEnabled(1000 /* childChain */));
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testGetChainEnabledMissingConfiguration() {
        // sConfigurationMap does not have entry for UID_RULES_CONFIGURATION_KEY
        assertThrows(ServiceSpecificException.class,
                () -> mBpfNetMaps.getChainEnabled(FIREWALL_CHAIN_DOZABLE));
    }

    @Test
    @IgnoreAfter(Build.VERSION_CODES.S_V2)
    public void testGetChainEnabledBeforeT() {
        assertThrows(UnsupportedOperationException.class,
                () -> mBpfNetMaps.getChainEnabled(FIREWALL_CHAIN_DOZABLE));
    }

    private long getMatch(final List<Integer> chains) {
        long match = 0;
        for (final int chain: chains) {
            match |= mBpfNetMaps.getMatchByFirewallChain(chain);
        }
        return match;
    }

    private void doTestSetChildChain(final List<Integer> testChains) throws Exception {
        assertEquals(0, sConfigurationMap.getValue(UID_RULES_CONFIGURATION_KEY).val);

        for (final int chain: testChains) {
            mBpfNetMaps.setChildChain(chain, true /* enable */);
        }
        final long expectedMatch = getMatch(testChains);
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
        assertThrows(ServiceSpecificException.class,
                () -> mBpfNetMaps.setChildChain(FIREWALL_CHAIN_DOZABLE, true /* enable */));
    }

    private void doTestSetUidRule(final List<Integer> testChains) throws ErrnoException {
        final UidOwnerValue match0 = sUidOwnerMap.getValue(new U32(TEST_UID));
        assertNull(match0);

        for (final int chain: testChains) {
            final int ruleToAddMatch =
                    mBpfNetMaps.isAllowList(chain) ? FIREWALL_RULE_ALLOW : FIREWALL_RULE_DENY;
            mBpfNetMaps.setUidRule(chain, TEST_UID, ruleToAddMatch);
        }
        final long expectedMatch = getMatch(testChains);
        final UidOwnerValue match1 = sUidOwnerMap.getValue(new U32(TEST_UID));
        assertEquals(expectedMatch, match1.rule);
        assertEquals(0, match1.iif);

        for (final int chain: testChains) {
            final int ruleToRemoveMatch =
                    mBpfNetMaps.isAllowList(chain) ? FIREWALL_RULE_DENY : FIREWALL_RULE_ALLOW;
            mBpfNetMaps.setUidRule(chain, TEST_UID, ruleToRemoveMatch);
        }
        final UidOwnerValue match2 = sUidOwnerMap.getValue(new U32(TEST_UID));
        assertNull(match2);
    }

    private void doTestSetUidRule(final int testChain) throws ErrnoException {
        doTestSetUidRule(List.of(testChain));
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testSetUidRule() throws Exception {
        doTestSetUidRule(FIREWALL_CHAIN_DOZABLE);
        doTestSetUidRule(FIREWALL_CHAIN_STANDBY);
        doTestSetUidRule(FIREWALL_CHAIN_POWERSAVE);
        doTestSetUidRule(FIREWALL_CHAIN_RESTRICTED);
        doTestSetUidRule(FIREWALL_CHAIN_LOW_POWER_STANDBY);
        doTestSetUidRule(FIREWALL_CHAIN_OEM_DENY_1);
        doTestSetUidRule(FIREWALL_CHAIN_OEM_DENY_2);
        doTestSetUidRule(FIREWALL_CHAIN_OEM_DENY_3);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testSetUidRuleMultipleChain() throws Exception {
        doTestSetUidRule(List.of(
                FIREWALL_CHAIN_DOZABLE,
                FIREWALL_CHAIN_STANDBY));
        doTestSetUidRule(List.of(
                FIREWALL_CHAIN_DOZABLE,
                FIREWALL_CHAIN_STANDBY,
                FIREWALL_CHAIN_POWERSAVE,
                FIREWALL_CHAIN_RESTRICTED));
        doTestSetUidRule(FIREWALL_CHAINS);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testSetUidRuleRemoveRuleFromUidWithNoRule() {
        final Class<ServiceSpecificException> expected = ServiceSpecificException.class;
        assertThrows(expected,
                () -> mBpfNetMaps.setUidRule(FIREWALL_CHAIN_DOZABLE, TEST_UID, FIREWALL_RULE_DENY));
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testSetUidRuleInvalidChain() {
        final Class<ServiceSpecificException> expected = ServiceSpecificException.class;
        assertThrows(expected,
                () -> mBpfNetMaps.setUidRule(-1 /* childChain */, TEST_UID, FIREWALL_RULE_ALLOW));
        assertThrows(expected,
                () -> mBpfNetMaps.setUidRule(1000 /* childChain */, TEST_UID, FIREWALL_RULE_ALLOW));
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testSetUidRuleInvalidRule() {
        final Class<ServiceSpecificException> expected = ServiceSpecificException.class;
        assertThrows(expected, () ->
                mBpfNetMaps.setUidRule(FIREWALL_CHAIN_DOZABLE, TEST_UID, -1 /* firewallRule */));
        assertThrows(expected, () ->
                mBpfNetMaps.setUidRule(FIREWALL_CHAIN_DOZABLE, TEST_UID, 1000 /* firewallRule */));
    }

    @Test
    @IgnoreAfter(Build.VERSION_CODES.S_V2)
    public void testSetUidRuleBeforeT() {
        assertThrows(ServiceSpecificException.class, () ->
                mBpfNetMaps.setUidRule(FIREWALL_CHAIN_DOZABLE, TEST_UID, FIREWALL_RULE_ALLOW));
    }
}
