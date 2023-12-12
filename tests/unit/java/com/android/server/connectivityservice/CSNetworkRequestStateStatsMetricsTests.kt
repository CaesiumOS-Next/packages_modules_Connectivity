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

package com.android.server

import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.TestableNetworkCallback
import java.util.Arrays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
class CSNetworkRequestStateStatsMetricsTests : CSTest() {

    val cellInternetNotMeteredNR = NetworkRequest.Builder().clearCapabilities()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            .build()

    @Before
    fun setup() {
        waitForIdle()
        clearInvocations(networkRequestStateStatsMetrics)
    }

    @Test
    fun testRequestTypeNRProduceMetrics() {
        cm.requestNetwork(cellInternetNotMeteredNR, TestableNetworkCallback())
        waitForIdle()

        val captor = org.mockito.ArgumentCaptor.forClass(NetworkRequest::class.java)
        verify(networkRequestStateStatsMetrics).onNetworkRequestReceived(captor.capture())
        val receivedNR = captor.value
        assertEquals(cellInternetNotMeteredNR.networkCapabilities.getUnderlyingNetworks(),
                receivedNR.networkCapabilities.getUnderlyingNetworks())
        assertTrue(Arrays.equals(cellInternetNotMeteredNR.networkCapabilities.getTransportTypes(),
                receivedNR.networkCapabilities.getTransportTypes()))
        assertTrue(receivedNR.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertTrue(receivedNR.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }

    @Test
    fun testListenTypeNRProduceNoMetrics() {
        cm.registerNetworkCallback(cellInternetNotMeteredNR, TestableNetworkCallback())
        waitForIdle()
        verify(networkRequestStateStatsMetrics, never()).onNetworkRequestReceived(any())
    }

    @Test
    fun testRemoveRequestTypeNRProduceMetrics() {
        val cb = TestableNetworkCallback()
        cm.requestNetwork(cellInternetNotMeteredNR, cb)

        waitForIdle()
        clearInvocations(networkRequestStateStatsMetrics)

        cm.unregisterNetworkCallback(cb)
        waitForIdle()
        val captor = org.mockito.ArgumentCaptor.forClass(NetworkRequest::class.java)
        verify(networkRequestStateStatsMetrics).onNetworkRequestRemoved(captor.capture())
        val removedNR = captor.value
        assertEquals(cellInternetNotMeteredNR.networkCapabilities.getUnderlyingNetworks(),
                removedNR.networkCapabilities.getUnderlyingNetworks())
        assertTrue(Arrays.equals(cellInternetNotMeteredNR.networkCapabilities.getTransportTypes(),
                removedNR.networkCapabilities.getTransportTypes()))
        assertTrue(removedNR.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertTrue(removedNR.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }
}
