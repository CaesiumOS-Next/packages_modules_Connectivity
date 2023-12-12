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

package com.android.metrics;

import static org.junit.Assert.assertEquals;

import android.os.Build;

import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(DevSdkIgnoreRunner.class)
@IgnoreUpTo(Build.VERSION_CODES.S_V2)
public class NetworkRequestStateInfoTest {
    private NetworkRequestStateInfo mNetworkRequestStateInfo;

    @Before
    public void setup() {
        mNetworkRequestStateInfo = null;
    }

    @Test
    public void testConstructor() {
        mNetworkRequestStateInfo = new NetworkRequestStateInfo.Builder().setPackageUid(1)
                .setNetworkRequestId(2).setTransportTypes(3)
                .setNetCapabilityNotMetered(true).setNetCapabilityInternet(false)
                .setNetworkRequestReceivedTime(4).setNetworkRequestDuration(5).build();
        assertEquals(mNetworkRequestStateInfo.mPackageUid, 1);
        assertEquals(mNetworkRequestStateInfo.mNetworkRequestId, 2);
        assertEquals(mNetworkRequestStateInfo.mTransportTypes, 3);
        assertEquals(mNetworkRequestStateInfo.mNetCapabilityNotMetered, true);
        assertEquals(mNetworkRequestStateInfo.mNetCapabilityInternet, false);
        assertEquals(mNetworkRequestStateInfo.mNetworkRequestReceivedTime, 4);
        assertEquals(mNetworkRequestStateInfo.mNetworkRequestDurationMillis, 5);
    }

    @Test
    public void testUpdateNetworkRequestDuration() {
        mNetworkRequestStateInfo = new NetworkRequestStateInfo.Builder().setNetworkRequestId(1)
                .setNetworkRequestDuration(2).build();

        mNetworkRequestStateInfo.updateNetworkRequestDuration(5);
        assertEquals(mNetworkRequestStateInfo.mNetworkRequestDurationMillis, 5);

        mNetworkRequestStateInfo.updateNetworkRequestDuration(5);
        assertEquals(mNetworkRequestStateInfo.mNetworkRequestDurationMillis, 5);

        mNetworkRequestStateInfo.updateNetworkRequestDuration(3);
        assertEquals(mNetworkRequestStateInfo.mNetworkRequestDurationMillis, 5);
    }

    @After
    public void teardown() {
        mNetworkRequestStateInfo = null;
    }
}
