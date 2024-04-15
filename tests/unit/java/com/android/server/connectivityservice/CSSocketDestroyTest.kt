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

import android.os.Build
import com.android.net.module.util.BaseNetdUnsolicitedEventListener
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.runner.RunWith
import android.app.ActivityManager.UidFrozenStateChangedCallback;
import android.net.ConnectivityManager.FIREWALL_RULE_ALLOW
import android.net.ConnectivityManager.FIREWALL_RULE_DENY
import android.net.ConnectivityManager.BLOCKED_REASON_NONE
import android.net.ConnectivityManager.BLOCKED_REASON_APP_BACKGROUND
import android.net.ConnectivityManager.FIREWALL_CHAIN_BACKGROUND
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Process
import org.junit.Test

import android.app.ActivityManager.UidFrozenStateChangedCallback.UID_FROZEN_STATE_FROZEN

import com.android.testutils.TestableNetworkCallback

import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.mockito.Mockito.any
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never

import org.mockito.Mockito.verify
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock

private const val TIMESTAMP = 1234L
private const val TEST_UID0 = 1234
private const val TEST_UID1 = 5678

private fun cellNc() = NetworkCapabilities.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
        .build()
private fun cellRequest() = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        .build()

private fun wifiNc() = NetworkCapabilities.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        .build()
private fun wifiRequest() = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .build()

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class CSSocketDestroyTest : CSTest() {
    private fun getRegisteredNetdUnsolicitedEventListener(): BaseNetdUnsolicitedEventListener {
        val captor = ArgumentCaptor.forClass(BaseNetdUnsolicitedEventListener::class.java)
        verify(netd).registerUnsolicitedEventListener(captor.capture())
        return captor.value
    }

    private fun getUidFrozenStateChangedCallback(): UidFrozenStateChangedCallback {
        val captor = ArgumentCaptor.forClass(UidFrozenStateChangedCallback::class.java)
        verify(activityManager).registerUidFrozenStateChangedCallback(any(), captor.capture())
        return captor.value
    }

    @Test
    fun testBackgroundAppFrozen() {
        val netdEventListener = getRegisteredNetdUnsolicitedEventListener()
        val frozenStateCallback = getUidFrozenStateChangedCallback()

        val cellCb = TestableNetworkCallback()
        cm.requestNetwork(cellRequest(), cellCb)
        val cellAgent = Agent(nc = cellNc())
        cellAgent.connect()

        netdEventListener.onInterfaceClassActivityChanged(false /* isActive */,
                cellAgent.network.netId, TIMESTAMP, TEST_UID0);

        // Enable background chain and allow test_uid0
        cm.setUidFirewallRule(FIREWALL_CHAIN_BACKGROUND, TEST_UID0,
                FIREWALL_RULE_ALLOW)
        cm.setFirewallChainEnabled(FIREWALL_CHAIN_BACKGROUND, true)

        // Deny test_uid0 this is delayed
        doReturn(BLOCKED_REASON_APP_BACKGROUND)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(TEST_UID0)
        cm.setUidFirewallRule(FIREWALL_CHAIN_BACKGROUND, TEST_UID0,
                FIREWALL_RULE_DENY)

        verify(destroySocketsWrapper, never()).destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID0));

        // Freeze
        val uids = intArrayOf(TEST_UID0)
        val frozenStates = intArrayOf(UID_FROZEN_STATE_FROZEN)
        frozenStateCallback.onUidFrozenStateChanged(uids, frozenStates);
        verify(destroySocketsWrapper, never()).destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID0));

        // ALLOW background
        doReturn(BLOCKED_REASON_NONE)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(TEST_UID0)
        cm.setUidFirewallRule(FIREWALL_CHAIN_BACKGROUND, TEST_UID0,
                FIREWALL_RULE_ALLOW)

        verify(destroySocketsWrapper, never()).destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID0));

        netdEventListener.onInterfaceClassActivityChanged(true /* isActive */,
                cellAgent.network.netId, TIMESTAMP, TEST_UID0);

        verify(destroySocketsWrapper).destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID0));

    }


}