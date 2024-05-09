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

import android.net.ConnectivityManager.BLOCKED_REASON_APP_BACKGROUND
import android.net.ConnectivityManager.BLOCKED_REASON_NONE
import android.net.ConnectivityManager.BLOCKED_REASON_NO_INTERNET_PERMISSION
import android.net.ConnectivityManager.FIREWALL_CHAIN_BACKGROUND
import android.net.INetd.PERMISSION_NONE
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.net.connectivity.ConnectivityCompatChanges.NETWORK_BLOCKED_WITHOUT_INTERNET_PERMISSION
import android.os.Build
import android.os.Process
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.RecorderCallback.CallbackEntry.BlockedStatusInt
import com.android.testutils.TestableNetworkCallback
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn

private fun wifiNc() = NetworkCapabilities.Builder()
        .addTransportType(TRANSPORT_WIFI)
        .addCapability(NET_CAPABILITY_INTERNET)
        .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
        .addCapability(NET_CAPABILITY_NOT_METERED)
        .build()
private fun wifiRequest() = NetworkRequest.Builder()
        .addTransportType(TRANSPORT_WIFI)
        .build()

@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class CSBlockedReasonsTest : CSTest() {

    inner class DetailedBlockedStatusCallback : TestableNetworkCallback() {
        override fun onBlockedStatusChanged(network: Network, blockedReasons: Int) {
            history.add(BlockedStatusInt(network, blockedReasons))
        }

        fun expectBlockedStatusChanged(network: Network, blockedReasons: Int) {
            expect<BlockedStatusInt>(network) { it.reason == blockedReasons }
        }
    }

    private fun doTestBlockedReasons_internetPermission(blockedByNoInternet: Boolean) {
        doReturn(PERMISSION_NONE).`when`(bpfNetMaps).getNetPermForUid(Process.myUid())

        val wifiCb = DetailedBlockedStatusCallback()
        cm.requestNetwork(wifiRequest(), wifiCb)
        val wifiAgent = Agent(nc = wifiNc())
        wifiAgent.connect()
        var expectedBlockedReason = if (blockedByNoInternet) {
            BLOCKED_REASON_NO_INTERNET_PERMISSION
        } else {
            BLOCKED_REASON_NONE
        }
        wifiCb.expectAvailableCallbacks(
                wifiAgent.network,
                validated = false,
                blockedReason = expectedBlockedReason
        )

        // Enable background firewall chain
        doReturn(BLOCKED_REASON_APP_BACKGROUND)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(Process.myUid())
        cm.setFirewallChainEnabled(FIREWALL_CHAIN_BACKGROUND, true)
        expectedBlockedReason = expectedBlockedReason or BLOCKED_REASON_APP_BACKGROUND
        wifiCb.expectBlockedStatusChanged(
                wifiAgent.network,
                expectedBlockedReason
        )

        // Disable background firewall chain
        doReturn(BLOCKED_REASON_NONE)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(Process.myUid())
        cm.setFirewallChainEnabled(FIREWALL_CHAIN_BACKGROUND, false)
        expectedBlockedReason = expectedBlockedReason and BLOCKED_REASON_APP_BACKGROUND.inv()
        wifiCb.expectBlockedStatusChanged(
                wifiAgent.network,
                expectedBlockedReason
        )
    }

    @Test
    fun testBlockedReasonsNoInternetPermission_changeDisabled() {
        deps.setChangeIdEnabled(false, NETWORK_BLOCKED_WITHOUT_INTERNET_PERMISSION)
        doTestBlockedReasons_internetPermission(blockedByNoInternet = false)
    }

    @Test
    fun testBlockedReasonsNoInternetPermission_changeEnabled() {
        deps.setChangeIdEnabled(true, NETWORK_BLOCKED_WITHOUT_INTERNET_PERMISSION)
        doTestBlockedReasons_internetPermission(blockedByNoInternet = true)
    }
}
