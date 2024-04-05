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
import android.net.ConnectivityManager.FIREWALL_CHAIN_BACKGROUND
import android.net.ConnectivityManager.FIREWALL_CHAIN_METERED_DENY_USER
import android.net.ConnectivityManager.FIREWALL_RULE_ALLOW
import android.net.ConnectivityManager.FIREWALL_RULE_DENY
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.os.Build
import android.os.Process
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.RecorderCallback.CallbackEntry.BlockedStatusInt
import com.android.testutils.TestableNetworkCallback
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mockito.doReturn

private fun cellNc() = NetworkCapabilities.Builder()
        .addTransportType(TRANSPORT_CELLULAR)
        .addCapability(NET_CAPABILITY_INTERNET)
        .addCapability(NET_CAPABILITY_NOT_SUSPENDED)
        .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
        .build()

private fun wifiNc() = NetworkCapabilities.Builder()
                .addTransportType(TRANSPORT_WIFI)
                .addCapability(NET_CAPABILITY_INTERNET)
                .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
                .addCapability(NET_CAPABILITY_NOT_METERED)
                .build()

@DevSdkIgnoreRunner.MonitorThreadLeak
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

    @Test
    fun testBlockedReasons_onAvailable() {
        val cellAgent = Agent(nc = cellNc())
        cellAgent.connect()
        val wifiAgent = Agent(nc = wifiNc())
        wifiAgent.connect()

        doReturn(
            BLOCKED_REASON_DOZE or BLOCKED_METERED_REASON_DATA_SAVER
                    or BLOCKED_METERED_REASON_USER_RESTRICTED
        ).`when`(bpfNetMaps).getUidNetworkingBlockedReasons(Process.myUid())

        val cellCb = DetailedBlockedStatusCallback()
        val wifiCb = DetailedBlockedStatusCallback()
        cm.requestNetwork(
            NetworkRequest.Builder().addTransportType(TRANSPORT_CELLULAR).build(),
            cellCb
        )
        cm.requestNetwork(NetworkRequest.Builder().addTransportType(TRANSPORT_WIFI).build(), wifiCb)

        cellCb.expectAvailableCallbacks(
                cellAgent.network,
                validated = false,
                blockedReason = BLOCKED_REASON_DOZE or BLOCKED_METERED_REASON_DATA_SAVER
                        or BLOCKED_METERED_REASON_USER_RESTRICTED
        )
        wifiCb.expectAvailableCallbacks(
                wifiAgent.network,
                validated = false,
                blockedReason = BLOCKED_REASON_DOZE
        )

        cellAgent.disconnect()
        wifiAgent.disconnect()
        cm.unregisterNetworkCallback(cellCb)
        cm.unregisterNetworkCallback(wifiCb)
    }

    @Test
    fun testBlockedReasons_dataSaverChanged() {
        doReturn(true).`when`(netd).bandwidthEnableDataSaver(anyBoolean())
        val cellCb = DetailedBlockedStatusCallback()
        val wifiCb = DetailedBlockedStatusCallback()
        cm.requestNetwork(
            NetworkRequest.Builder().addTransportType(TRANSPORT_CELLULAR).build(),
            cellCb
        )
        cm.requestNetwork(NetworkRequest.Builder().addTransportType(TRANSPORT_WIFI).build(), wifiCb)

        doReturn(
            BLOCKED_REASON_APP_BACKGROUND
        ).`when`(bpfNetMaps).getUidNetworkingBlockedReasons(Process.myUid())
        cm.setFirewallChainEnabled(FIREWALL_CHAIN_BACKGROUND, true)
        doReturn(
            BLOCKED_REASON_APP_BACKGROUND or BLOCKED_METERED_REASON_DATA_SAVER
        ).`when`(bpfNetMaps).getUidNetworkingBlockedReasons(Process.myUid())
        cm.setDataSaverEnabled(true)

        val cellAgent = Agent(nc = cellNc())
        cellAgent.connect()
        val wifiAgent = Agent(nc = wifiNc())
        wifiAgent.connect()
        cellCb.expectAvailableCallbacks(
                cellAgent.network,
                validated = false,
                blockedReason = BLOCKED_REASON_APP_BACKGROUND or BLOCKED_METERED_REASON_DATA_SAVER
        )
        wifiCb.expectAvailableCallbacks(
                wifiAgent.network,
                validated = false,
                blockedReason = BLOCKED_REASON_APP_BACKGROUND
        )

        doReturn(
            BLOCKED_REASON_APP_BACKGROUND
        ).`when`(bpfNetMaps).getUidNetworkingBlockedReasons(Process.myUid())
        cm.setDataSaverEnabled(false)
        cellCb.expectBlockedStatusChanged(
                cellAgent.network,
                BLOCKED_REASON_APP_BACKGROUND
        )

        doReturn(
            BLOCKED_REASON_APP_BACKGROUND or BLOCKED_METERED_REASON_DATA_SAVER
        ).`when`(bpfNetMaps).getUidNetworkingBlockedReasons(Process.myUid())
        cm.setDataSaverEnabled(true)
        cellCb.expectBlockedStatusChanged(
                cellAgent.network,
                BLOCKED_REASON_APP_BACKGROUND or BLOCKED_METERED_REASON_DATA_SAVER
        )
        wifiCb.assertNoCallback()

        cellAgent.disconnect()
        wifiAgent.disconnect()
        cm.unregisterNetworkCallback(cellCb)
        cm.unregisterNetworkCallback(wifiCb)
    }

    @Test
    fun testBlockedReasons_firewallChanged() {
        val cellCb = DetailedBlockedStatusCallback()
        val wifiCb = DetailedBlockedStatusCallback()
        cm.requestNetwork(
            NetworkRequest.Builder().addTransportType(TRANSPORT_CELLULAR).build(),
            cellCb
        )
        cm.requestNetwork(NetworkRequest.Builder().addTransportType(TRANSPORT_WIFI).build(), wifiCb)

        doReturn(
            BLOCKED_METERED_REASON_USER_RESTRICTED
        ).`when`(bpfNetMaps).getUidNetworkingBlockedReasons(Process.myUid())
        cm.setUidFirewallRule(FIREWALL_CHAIN_METERED_DENY_USER, Process.myUid(), FIREWALL_RULE_DENY)
        val cellAgent = Agent(nc = cellNc())
        cellAgent.connect()
        val wifiAgent = Agent(nc = wifiNc())
        wifiAgent.connect()
        cellCb.expectAvailableCallbacks(
                cellAgent.network,
                validated = false,
                blockedReason = BLOCKED_METERED_REASON_USER_RESTRICTED
        )
        wifiCb.expectAvailableCallbacks(
                wifiAgent.network,
                validated = false,
                blockedReason = BLOCKED_REASON_NONE
        )

        doReturn(
            BLOCKED_REASON_NONE
        ).`when`(bpfNetMaps).getUidNetworkingBlockedReasons(Process.myUid())
        cm.setUidFirewallRule(
            FIREWALL_CHAIN_METERED_DENY_USER,
            Process.myUid(),
            FIREWALL_RULE_ALLOW
        )
        cellCb.expectBlockedStatusChanged(
                cellAgent.network,
                BLOCKED_REASON_NONE
        )
        wifiCb.assertNoCallback()

        doReturn(
            BLOCKED_REASON_APP_BACKGROUND
        ).`when`(bpfNetMaps).getUidNetworkingBlockedReasons(Process.myUid())
        cm.setFirewallChainEnabled(FIREWALL_CHAIN_BACKGROUND, true)
        cellCb.expectBlockedStatusChanged(
                cellAgent.network,
                BLOCKED_REASON_APP_BACKGROUND
        )
        wifiCb.expectBlockedStatusChanged(
                wifiAgent.network,
                BLOCKED_REASON_APP_BACKGROUND
        )

        doReturn(
            BLOCKED_REASON_APP_BACKGROUND or BLOCKED_METERED_REASON_USER_RESTRICTED
        ).`when`(bpfNetMaps).getUidNetworkingBlockedReasons(Process.myUid())
        cm.setUidFirewallRule(FIREWALL_CHAIN_METERED_DENY_USER, Process.myUid(), FIREWALL_RULE_DENY)

        cellCb.expectBlockedStatusChanged(
                cellAgent.network,
                BLOCKED_REASON_APP_BACKGROUND or BLOCKED_METERED_REASON_USER_RESTRICTED
        )

        doReturn(
            BLOCKED_REASON_APP_BACKGROUND
        ).`when`(bpfNetMaps).getUidNetworkingBlockedReasons(Process.myUid())
        cm.setUidFirewallRule(
            FIREWALL_CHAIN_METERED_DENY_USER,
            Process.myUid(),
            FIREWALL_RULE_ALLOW
        )
        cellCb.expectBlockedStatusChanged(
                cellAgent.network,
                BLOCKED_REASON_APP_BACKGROUND
        )
        // WiFi network is not blocked since the network is not metered network
        wifiCb.assertNoCallback()

        doReturn(
            BLOCKED_REASON_NONE
        ).`when`(bpfNetMaps).getUidNetworkingBlockedReasons(Process.myUid())
        cm.setUidFirewallRule(FIREWALL_CHAIN_BACKGROUND, Process.myUid(), FIREWALL_RULE_ALLOW)
        cellCb.expectBlockedStatusChanged(
                cellAgent.network,
                BLOCKED_REASON_NONE
        )
        wifiCb.expectBlockedStatusChanged(
                wifiAgent.network,
                BLOCKED_REASON_NONE
        )
    }
}
