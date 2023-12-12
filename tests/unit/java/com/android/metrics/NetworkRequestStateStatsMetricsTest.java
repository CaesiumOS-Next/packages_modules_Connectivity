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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(AndroidJUnit4.class)
@IgnoreUpTo(Build.VERSION_CODES.S_V2)
public class NetworkRequestStateStatsMetricsTest {
    @Rule
    public final MockitoRule mocks = MockitoJUnit.rule();

    @Mock
    private NetworkRequestStateStatsMetrics.Dependencies mDependencies;
    @Captor
    ArgumentCaptor<NetworkRequestStateInfo> mNetworkRequestStateInfoCaptor;
    @Captor
    ArgumentCaptor<Integer> mIntCaptor;

    private NetworkRequestStateStatsMetrics mNetworkRequestStateStatsMetrics;
    private static final int TEST_REQUEST_ID = 10;
    private static final int TEST_PACKAGE_UID = 20;
    private NetworkRequest mNetworkRequest;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mNetworkRequestStateStatsMetrics = new NetworkRequestStateStatsMetrics(mDependencies);
        NetworkCapabilities nc = new NetworkCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED, true)
                .setCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET, false);
        mNetworkRequest =
                new NetworkRequest(nc, 0, TEST_REQUEST_ID, NetworkRequest.Type.REQUEST);
    }

    @Test
    public void testNetworkRequestReceivedRemoved() {
        Mockito.when(mDependencies.getElapsedRealtime()).thenReturn(1L);
        mNetworkRequestStateStatsMetrics.onNetworkRequestReceived(TEST_PACKAGE_UID,
                mNetworkRequest);

        verify(mDependencies, times(1))
                .sendStatsMessage(any(Handler.class), mIntCaptor.capture(),
                        mNetworkRequestStateInfoCaptor.capture());

        assertEquals(NetworkRequestStateStatsMetrics.MSG_NETWORK_REQUEST_RECEIVED,
                (int) mIntCaptor.getValue());

        NetworkRequestStateInfo nrStateInfoSent = mNetworkRequestStateInfoCaptor.getValue();
        assertEquals(nrStateInfoSent.getRequestId(), TEST_REQUEST_ID);
        assertEquals(nrStateInfoSent.mPackageUid, TEST_PACKAGE_UID);
        assertEquals(nrStateInfoSent.mNetworkRequestDurationMillis, 0);
        assertEquals(nrStateInfoSent.getTransportTypes(),
                1 << NetworkCapabilities.TRANSPORT_WIFI);
        assertTrue(nrStateInfoSent.getNetCapabilityNotMetered());
        assertFalse(nrStateInfoSent.getNetCapabilityInternet());

        Mockito.when(mDependencies.getElapsedRealtime()).thenReturn(101L);
        mNetworkRequestStateStatsMetrics.onNetworkRequestRemoved(mNetworkRequest);

        verify(mDependencies, times(2))
                .sendStatsMessage(any(Handler.class), mIntCaptor.capture(),
                        mNetworkRequestStateInfoCaptor.capture());

        assertEquals(NetworkRequestStateStatsMetrics.MSG_NETWORK_REQUEST_REMOVED,
                (int) mIntCaptor.getValue());

        nrStateInfoSent = mNetworkRequestStateInfoCaptor.getValue();
        assertEquals(nrStateInfoSent.getRequestId(), TEST_REQUEST_ID);
        assertEquals(nrStateInfoSent.mPackageUid, TEST_PACKAGE_UID);
        assertEquals(nrStateInfoSent.getTransportTypes(),
                1 << NetworkCapabilities.TRANSPORT_WIFI);
        assertTrue(nrStateInfoSent.getNetCapabilityNotMetered());
        assertFalse(nrStateInfoSent.getNetCapabilityInternet());
        assertEquals(nrStateInfoSent.mNetworkRequestDurationMillis, 100);
    }

    @Test
    public void testNewNetworkRequestRemoved() {
        mNetworkRequestStateStatsMetrics.onNetworkRequestRemoved(mNetworkRequest);

        verify(mDependencies, never())
                .sendStatsMessage(any(Handler.class), anyInt(), any(NetworkRequestStateInfo.class));
    }

    @Test
    public void testExistingNetworkRequestReceived() {
        mNetworkRequestStateStatsMetrics.onNetworkRequestReceived(TEST_PACKAGE_UID,
                mNetworkRequest);
        verify(mDependencies, times(1))
                .sendStatsMessage(any(Handler.class), anyInt(), any(NetworkRequestStateInfo.class));


        mNetworkRequestStateStatsMetrics.onNetworkRequestReceived(TEST_PACKAGE_UID,
                mNetworkRequest);
        verify(mDependencies, times(1))
                .sendStatsMessage(any(Handler.class), anyInt(), any(NetworkRequestStateInfo.class));

    }
}
