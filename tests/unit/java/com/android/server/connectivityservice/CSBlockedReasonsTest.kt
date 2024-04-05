/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.net.ConnectivityManager.BLOCKED_METERED_REASON_DATA_SAVER
import android.net.ConnectivityManager.BLOCKED_METERED_REASON_USER_RESTRICTED
import android.net.ConnectivityManager.BLOCKED_REASON_APP_BACKGROUND
import android.net.ConnectivityManager.BLOCKED_REASON_DOZE
import android.net.ConnectivityManager.BLOCKED_REASON_NONE
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkRequest
import android.os.Build
import android.os.Process
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.RecorderCallback.CallbackEntry.BlockedStatusInt
import com.android.testutils.TestableNetworkCallback
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn

private fun cellNc(metered: Boolean) = NetworkCapabilities.Builder().apply {
    addTransportType(TRANSPORT_CELLULAR)
    addCapability(NET_CAPABILITY_INTERNET)
    addCapability(NET_CAPABILITY_NOT_SUSPENDED)
    addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
    if (!metered) {
        addCapability(NET_CAPABILITY_NOT_METERED)
    }
}.build()
@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class CSBlockedReasonsTest : CSTest() {

    inner class DetailedBlockedStatusCallback : TestableNetworkCallback() {
        override fun onBlockedStatusChanged(network: Network, blockedReasons: Int) {
            history.add(BlockedStatusInt(network, blockedReasons))
        }
    }

    private fun doTestBlockedReasons_onAvailable(
            blockedReasons: Int,
            expectedBlockedReasons: Int,
            metered: Boolean
    ) {
        val cb = DetailedBlockedStatusCallback()
        cm.requestNetwork(NetworkRequest.Builder().build(), cb)

        doReturn(blockedReasons).`when`(bpfNetMaps).getUidNetworkingBlockedReasons(Process.myUid())

        val agent = Agent(nc = cellNc(metered))
        agent.connect()
        cb.expectAvailableCallbacks(
                agent.network,
                validated = false,
                blockedReason = expectedBlockedReasons
        )
        agent.disconnect()
        cm.unregisterNetworkCallback(cb)
    }

    @Test
    fun testBlockedReasons_onAvailable() {
        doTestBlockedReasons_onAvailable(BLOCKED_REASON_NONE, BLOCKED_REASON_NONE, metered = false)
        doTestBlockedReasons_onAvailable(BLOCKED_REASON_DOZE, BLOCKED_REASON_DOZE, metered = false)
        doTestBlockedReasons_onAvailable(
                BLOCKED_REASON_DOZE or BLOCKED_REASON_APP_BACKGROUND,
                BLOCKED_REASON_DOZE or BLOCKED_REASON_APP_BACKGROUND,
                metered = false
        )
        doTestBlockedReasons_onAvailable(
                BLOCKED_REASON_DOZE or BLOCKED_METERED_REASON_USER_RESTRICTED,
                BLOCKED_REASON_DOZE,
                metered = false
        )
        doTestBlockedReasons_onAvailable(
                BLOCKED_METERED_REASON_DATA_SAVER or BLOCKED_METERED_REASON_USER_RESTRICTED,
                BLOCKED_METERED_REASON_DATA_SAVER or BLOCKED_METERED_REASON_USER_RESTRICTED,
                metered = true
        )
    }
}
