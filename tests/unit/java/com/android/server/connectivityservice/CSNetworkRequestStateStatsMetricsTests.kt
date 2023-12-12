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

import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Process
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.TestableNetworkCallback
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
class CSNetworkRequestStateStatsMetricsTests : CSTest() {

    @Before
    fun setup() {
        waitForIdle()
        clearInvocations(service.mNetworkRequestStateStatsMetrics)
        verify(service.mNetworkRequestStateStatsMetrics, never())
                .onNetworkRequestReceived(any(), any())
    }

    @Test
    fun testRequestTypeNRProduceMetrics() {
        cm.requestNetwork(
                NetworkRequest.Builder().clearCapabilities().build(),
                TestableNetworkCallback())
        waitForIdle()
        verify(service.mNetworkRequestStateStatsMetrics).onNetworkRequestReceived(any(), any())
    }

    @Test
    fun testListenTypeNRProduceNoMetrics() {
        cm.registerNetworkCallback(
                NetworkRequest.Builder().clearCapabilities().build(),
                TestableNetworkCallback())
        waitForIdle()
        verify(service.mNetworkRequestStateStatsMetrics, never())
                .onNetworkRequestReceived(any(), any())
    }

    @Test
    fun testRemoveRequestTypeNRProduceMetrics() {
        val nr = service.requestNetwork(
                Process.INVALID_UID, NetworkCapabilities(), 3,
                null, 0, null, ConnectivityManager.TYPE_WIFI,
                NetworkCallback.FLAG_NONE, context.getOpPackageName(), "TAG")

        waitForIdle()
        clearInvocations(service.mNetworkRequestStateStatsMetrics)
        verify(service.mNetworkRequestStateStatsMetrics, never())
                .onNetworkRequestReceived(any(), any())
        service.releaseNetworkRequest(nr)
        waitForIdle()

        verify(service.mNetworkRequestStateStatsMetrics).onNetworkRequestRemoved(nr)
    }
}
