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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.mockito.Mockito.verify;

import android.net.INetd;
import android.os.Build;

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
    private static final U32 UID_RULES_CONFIGURATION_KEY = new U32(1);
    private BpfNetMaps mBpfNetMaps;

    @Mock INetd mNetd;
    private TestBpfMap<U32, U32> mConfigurationMap = new TestBpfMap<>(U32.class, U32.class);

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        final BpfNetMaps.Dependencies deps = makeDependencies();
        mBpfNetMaps = new BpfNetMaps(mNetd, deps);
    }

    private BpfNetMaps.Dependencies makeDependencies() {
        return new BpfNetMaps.Dependencies() {
            @Override
            public BpfMap<U32, U32> getConfigurationMap() {
                return mConfigurationMap;
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

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testGetChainEnabled() throws Exception {
        final List<Integer> chains = List.of(
                FIREWALL_CHAIN_DOZABLE,
                FIREWALL_CHAIN_STANDBY,
                FIREWALL_CHAIN_POWERSAVE,
                FIREWALL_CHAIN_RESTRICTED,
                FIREWALL_CHAIN_LOW_POWER_STANDBY,
                FIREWALL_CHAIN_OEM_DENY_1,
                FIREWALL_CHAIN_OEM_DENY_2,
                FIREWALL_CHAIN_OEM_DENY_3
        );
        for (final int enableChain: chains) {
            final int match = mBpfNetMaps.getMatchByFirewallChain(enableChain);
            mConfigurationMap.updateEntry(UID_RULES_CONFIGURATION_KEY, new U32(match));

            assertTrue(mBpfNetMaps.getChainEnabled(enableChain));
            for (final int checkChain: chains) {
                if (checkChain != enableChain) {
                    assertFalse(mBpfNetMaps.getChainEnabled(checkChain));
                }
            }
        }
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testGetChainEnabledInvalidChain() {
        final Class<IllegalArgumentException> expected = IllegalArgumentException.class;
        assertThrows(expected, () -> mBpfNetMaps.getChainEnabled(-1 /* childChain */));
        assertThrows(expected, () -> mBpfNetMaps.getChainEnabled(1000 /* childChain */));
    }

    @Test
    @IgnoreAfter(Build.VERSION_CODES.S_V2)
    public void testGetChainEnabledBeforeT() {
        assertThrows(UnsupportedOperationException.class,
                () -> mBpfNetMaps.getChainEnabled(FIREWALL_CHAIN_DOZABLE));
    }
}
