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

import static android.os.SystemClock.sleep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;

import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(DevSdkIgnoreRunner.class)
@IgnoreUpTo(Build.VERSION_CODES.S_V2)
public class NetworkRequestStateInfoTest {

    private static final int TEST_REQUEST_ID = 10;
    private static final int TEST_PACKAGE_UID = 20;
    private NetworkRequest mNetworkRequest;
    @Before
    public void setup() {
        NetworkCapabilities nc = new NetworkCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED, true)
                .setCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET, false);
        mNetworkRequest = new NetworkRequest(nc, 0, TEST_REQUEST_ID, NetworkRequest.Type.REQUEST);
    }
    @Test
    public void testConstructor() {
        NetworkRequestStateInfo mNetworkRequestStateInfo =
                new NetworkRequestStateInfo(TEST_PACKAGE_UID, mNetworkRequest);
        assertEquals(mNetworkRequestStateInfo.mPackageUid, TEST_PACKAGE_UID);
        assertEquals(mNetworkRequestStateInfo.getRequestId(), TEST_REQUEST_ID);
        assertEquals(mNetworkRequestStateInfo.getTransportTypes(), 2);
        assertEquals(mNetworkRequestStateInfo.getNetCapabilityNotMetered(), true);
        assertEquals(mNetworkRequestStateInfo.getNetCapabilityInternet(), false);
        assertEquals(mNetworkRequestStateInfo.mNetworkRequestDurationMillis, 0);
    }

    @Test
    public void testUpdateNetworkRequestDuration() {
        NetworkRequestStateInfo mNetworkRequestStateInfo =
                new NetworkRequestStateInfo(TEST_PACKAGE_UID, mNetworkRequest);

        sleep(10);

        mNetworkRequestStateInfo.captureNetworkRequestCurrentDuration();
        assertTrue(mNetworkRequestStateInfo.mNetworkRequestDurationMillis > 0);
    }

}
