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

import android.app.ActivityManager.UidFrozenStateChangedCallback
import android.app.ActivityManager.UidFrozenStateChangedCallback.UID_FROZEN_STATE_FROZEN
import android.app.ActivityManager.UidFrozenStateChangedCallback.UID_FROZEN_STATE_UNFROZEN
import android.net.ConnectivityManager.BLOCKED_REASON_APP_BACKGROUND
import android.net.ConnectivityManager.BLOCKED_REASON_NONE
import android.net.ConnectivityManager.FIREWALL_CHAIN_BACKGROUND
import android.net.ConnectivityManager.FIREWALL_RULE_ALLOW
import android.net.ConnectivityManager.FIREWALL_RULE_DENY
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import com.android.net.module.util.BaseNetdUnsolicitedEventListener
import com.android.server.connectivity.ConnectivityFlags.DELAY_DESTROY_SOCKETS
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

private const val TIMESTAMP = 1234L
private const val TEST_UID = 1234
private const val TEST_CELL_IFACE = "test_rmnet"

private fun cellNc() = NetworkCapabilities.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
        .build()

private fun cellLp() = LinkProperties().also{
    it.interfaceName = TEST_CELL_IFACE
}

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

    private fun doTestBackgroundAppDestroySockets(
            backgroundWithIdleNetwork: Boolean,
            expectDelay: Boolean
    ) {
        val netdEventListener = getRegisteredNetdUnsolicitedEventListener()
        val inOrder = inOrder(destroySocketsWrapper)

        val cellAgent = Agent(nc = cellNc(), lp = cellLp())
        cellAgent.connect()
        if (backgroundWithIdleNetwork) {
            netdEventListener.onInterfaceClassActivityChanged(
                    false, // isActive
                    cellAgent.network.netId,
                    TIMESTAMP,
                    TEST_UID
            )
        }

        // Set deny rule on background chain for TEST_UID
        doReturn(BLOCKED_REASON_APP_BACKGROUND)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(TEST_UID)
        cm.setUidFirewallRule(
                FIREWALL_CHAIN_BACKGROUND,
                TEST_UID,
                FIREWALL_RULE_DENY
        )
        waitForIdle()
        if (expectDelay) {
            inOrder.verify(destroySocketsWrapper, never())
                    .destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID))
        } else {
            inOrder.verify(destroySocketsWrapper)
                    .destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID))
        }

        netdEventListener.onInterfaceClassActivityChanged(
                true, // isActive
                cellAgent.network.netId,
                TIMESTAMP,
                TEST_UID
        )
        waitForIdle()
        if (expectDelay) {
            inOrder.verify(destroySocketsWrapper)
                    .destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID))
        } else {
            inOrder.verify(destroySocketsWrapper, never())
                    .destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID))
        }

        cellAgent.disconnect()
    }

    @Test
    @FeatureFlags(flags = [Flag(DELAY_DESTROY_SOCKETS, true)])
    fun testBackgroundAppDestroySockets() {
        doTestBackgroundAppDestroySockets(
                backgroundWithIdleNetwork = true,
                expectDelay = true
        )
    }

    @Test
    @FeatureFlags(flags = [Flag(DELAY_DESTROY_SOCKETS, true)])
    fun testBackgroundAppDestroySockets_activeNetwork() {
        doTestBackgroundAppDestroySockets(
                backgroundWithIdleNetwork = false,
                expectDelay = false
        )
    }

    @Test
    @FeatureFlags(flags = [Flag(DELAY_DESTROY_SOCKETS, false)])
    fun testBackgroundAppDestroySockets_featureIsDisabled() {
        doTestBackgroundAppDestroySockets(
                backgroundWithIdleNetwork = true,
                expectDelay = false
        )
    }

    private fun doTestDestroySockets(isBackground: Boolean, isFrozen: Boolean) {
        val netdEventListener = getRegisteredNetdUnsolicitedEventListener()
        val frozenStateCallback = getUidFrozenStateChangedCallback()

        // Make cell default network idle
        val cellAgent = Agent(nc = cellNc(), lp = cellLp())
        cellAgent.connect()
        netdEventListener.onInterfaceClassActivityChanged(
                false, // isActive
                cellAgent.network.netId,
                TIMESTAMP,
                TEST_UID
        )

        // Set deny rule on background chain for TEST_UID
        doReturn(BLOCKED_REASON_APP_BACKGROUND)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(TEST_UID)
        cm.setUidFirewallRule(
                FIREWALL_CHAIN_BACKGROUND,
                TEST_UID,
                FIREWALL_RULE_DENY
        )
        // Freeze TEST_UID
        frozenStateCallback.onUidFrozenStateChanged(
                intArrayOf(TEST_UID),
                intArrayOf(UID_FROZEN_STATE_FROZEN)
        )

        if (!isBackground) {
            // Set allow rule on background chain for TEST_UID
            doReturn(BLOCKED_REASON_NONE)
                    .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(TEST_UID)
            cm.setUidFirewallRule(
                    FIREWALL_CHAIN_BACKGROUND,
                    TEST_UID,
                    FIREWALL_RULE_ALLOW
            )
        }
        if (!isFrozen) {
            // Unfreeze TEST_UID
            frozenStateCallback.onUidFrozenStateChanged(
                    intArrayOf(TEST_UID),
                    intArrayOf(UID_FROZEN_STATE_UNFROZEN)
            )
        }
        verify(destroySocketsWrapper, never()).destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID))

        // Make cell network active
        netdEventListener.onInterfaceClassActivityChanged(
                true, // isActive
                cellAgent.network.netId,
                TIMESTAMP,
                TEST_UID
        )

        waitForIdle()
        if (isFrozen or isBackground) {
            verify(destroySocketsWrapper).destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID))
        } else {
            // If the app is neither frozen nor background, socket is not destroyed
            verify(destroySocketsWrapper, never()).destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID))
        }
    }

    @Test
    fun testDestroySockets_background_frozen() {
        doTestDestroySockets(isBackground = true, isFrozen = true)
    }

    @Test
    fun testDestroySockets_background_nonFrozen() {
        doTestDestroySockets(isBackground = true, isFrozen = false)
    }

    @Test
    fun testDestroySockets_nonBackground_frozen() {
        doTestDestroySockets(isBackground = false, isFrozen = true)
    }

    @Test
    fun testDestroySockets_nonBackground_nonFrozen() {
        doTestDestroySockets(isBackground = false, isFrozen = false)
    }
}
