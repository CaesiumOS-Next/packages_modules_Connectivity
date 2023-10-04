/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.net.INetd.PERMISSION_INTERNET;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.verify;

import android.net.INetd;
import android.os.Build;

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

@RunWith(DevSdkIgnoreRunner.class)
public class BpfNetPermMapsTest {
    @Rule
    public final DevSdkIgnoreRule ignoreRule = new DevSdkIgnoreRule();

    private static final int[] TEST_UIDS = {10002, 10003};
    private static final String TEST_IF_NAME = "wlan0";
    @Mock
    INetd mNetd;

    @Test
    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
    public void testBpfNetPermMapsPreT() throws Exception {
        final BpfNetPermMaps bpfNetPermMaps = new BpfNetPermMaps(mNetd);
        bpfNetPermMaps.addUidInterfaceRules(TEST_IF_NAME, TEST_UIDS);
        verify(mNetd).firewallAddUidInterfaceRules(TEST_IF_NAME, TEST_UIDS);
        bpfNetPermMaps.removeUidInterfaceRules(TEST_UIDS);
        verify(mNetd).firewallRemoveUidInterfaceRules(TEST_UIDS);
        bpfNetPermMaps.setNetPermForUids(PERMISSION_INTERNET, TEST_UIDS);
        verify(mNetd).trafficSetNetPermForUids(PERMISSION_INTERNET, TEST_UIDS);
    }
    @Test
    @DevSdkIgnoreRule.IgnoreAfter(Build.VERSION_CODES.S_V2)
    public void testBpfNetPermMapsConstructor() throws Exception {
        assertThrows(IllegalStateException.class, () -> new BpfNetPermMaps(mNetd));
    }
}
