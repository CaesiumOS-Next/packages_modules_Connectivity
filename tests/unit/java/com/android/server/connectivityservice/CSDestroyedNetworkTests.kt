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
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.os.Build
import androidx.test.filters.SmallTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.RecorderCallback.CallbackEntry.Lost
import com.android.testutils.TestableNetworkCallback
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.argThat
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify

private const val LONG_TIMEOUT_MS = 5_000

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
class CSDestroyedNetworkTests : CSTest() {

    private fun makeRequests(): Pair<NetworkCapabilities, TestableNetworkCallback> {
        val nc = NetworkCapabilities.Builder()
            .addTransportType(TRANSPORT_WIFI)
            .build()

        val nr = NetworkRequest.Builder()
            .clearCapabilities()
            .addTransportType(TRANSPORT_WIFI)
            .build()
        val cbRequest = TestableNetworkCallback()
        val cbCallback = TestableNetworkCallback()
        cm.requestNetwork(nr, cbRequest)
        cm.registerNetworkCallback(nr, cbCallback)

        return nc to cbCallback
    }

    @Test
    fun testDestroyNetworkNotKeptWhenUnvalidated() {
        val (nc, cbCallback) = makeRequests()

        val firstAgent = Agent(nc = nc)
        firstAgent.connect()
        cbCallback.expectAvailableCallbacks(firstAgent.network, validated = false)

        firstAgent.unregisterAfterReplacement(LONG_TIMEOUT_MS)

        val secondAgent = Agent(nc = nc)
        secondAgent.connect()
        cbCallback.expectAvailableCallbacks(secondAgent.network, validated = false)

        cbCallback.expect<Lost>(timeoutMs = 500) { it.network == firstAgent.network }
    }

    @Test
    fun testDestroyNetworkWithDelayedTeardown() {
        val (nc, cbCallback) = makeRequests()

        val firstAgent = Agent(nc = nc)
        firstAgent.connect()
        firstAgent.setTeardownDelayMillis(1)
        cbCallback.expectAvailableCallbacks(firstAgent.network, validated = false)

        reset(netd)
        val inOrder = inOrder(netd)
        firstAgent.unregisterAfterReplacement(LONG_TIMEOUT_MS)

        val secondAgent = Agent(nc = nc)
        secondAgent.connect()
        cbCallback.expectAvailableCallbacks(secondAgent.network, validated = false)
        secondAgent.disconnect()

        cbCallback.expect<Lost>(timeoutMs = 500) { it.network == firstAgent.network }
        cbCallback.expect<Lost>(timeoutMs = 500) { it.network == secondAgent.network }
        // onLost is fired before the network is destroyed.
        waitForIdle()

        inOrder.verify(netd).networkDestroy(eq(firstAgent.network.netId))
        inOrder.verify(netd).networkCreate(argThat{ it.netId == secondAgent.network.netId })
        inOrder.verify(netd).networkDestroy(eq(secondAgent.network.netId))
        verify(netd, never()).networkSetPermissionForNetwork(anyInt(), anyInt())
    }
}
