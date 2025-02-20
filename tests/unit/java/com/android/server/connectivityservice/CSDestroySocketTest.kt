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
import android.net.InetAddresses
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import com.android.net.module.util.BaseNetdUnsolicitedEventListener
import com.android.net.module.util.netlink.InetDiagMessage
import com.android.net.module.util.netlink.StructInetDiagSockId
import com.android.net.module.util.netlink.StructNlMsgHdr
import com.android.server.connectivity.ConnectivityFlags.DELAY_DESTROY_SOCKETS
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import java.net.DatagramSocket
import java.net.InetSocketAddress
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
private const val TEST_UID2 = 5678
private const val TEST_CELL_IFACE = "test_rmnet"
private const val TEST_WIFI_IFACE = "test_wlan"
private const val TEST_IFINDEX = 12
private const val TEST_SOCKET_COOKIE = 12321L
private const val TIMEOUT_MS = 500L

// ????where
private val TEST_SRC_SOCKET_ADDRESS = InetSocketAddress(
        InetAddresses.parseNumericAddress("2001:db8:1:2::2"),
        1234
)
private val TEST_DST_SOCKET_ADDRESS = InetSocketAddress(
        InetAddresses.parseNumericAddress("2001:db8:1:2::3"),
        1234
)
private val TEST_PAYLOAD = byteArrayOf(0, 1, 2, 3, 4, 5)

private fun cellNc() = NetworkCapabilities.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
        .build()

private fun cellLp() = LinkProperties().also{
    it.interfaceName = TEST_CELL_IFACE
}
private fun wifiNc() = NetworkCapabilities.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
        .build()
private fun wifiLp() = LinkProperties().also{
    it.interfaceName = TEST_WIFI_IFACE
    it.addLinkAddress(LinkAddress("2001:db8:1:2::2/64"))
}

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class CSDestroySocketTest : CSTest() {
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

    private fun doTestBackgroundRestrictionDestroySockets(
            restrictionWithIdleNetwork: Boolean,
            expectDelay: Boolean
    ) {
        val netdEventListener = getRegisteredNetdUnsolicitedEventListener()
        val inOrder = inOrder(destroySocketsWrapper)

        val cellAgent = Agent(nc = cellNc(), lp = cellLp())
        cellAgent.connect()
        if (restrictionWithIdleNetwork) {
            // Make cell default network idle
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
        doTestBackgroundRestrictionDestroySockets(
                restrictionWithIdleNetwork = true,
                expectDelay = true
        )
    }

    @Test
    @FeatureFlags(flags = [Flag(DELAY_DESTROY_SOCKETS, true)])
    fun testBackgroundAppDestroySockets_activeNetwork() {
        doTestBackgroundRestrictionDestroySockets(
                restrictionWithIdleNetwork = false,
                expectDelay = false
        )
    }

    @Test
    @FeatureFlags(flags = [Flag(DELAY_DESTROY_SOCKETS, false)])
    fun testBackgroundAppDestroySockets_featureIsDisabled() {
        doTestBackgroundRestrictionDestroySockets(
                restrictionWithIdleNetwork = true,
                expectDelay = false
        )
    }

    @Test
    fun testReplaceFirewallChain() {
        val netdEventListener = getRegisteredNetdUnsolicitedEventListener()
        val inOrder = inOrder(destroySocketsWrapper)

        val cellAgent = Agent(nc = cellNc(), lp = cellLp())
        cellAgent.connect()
        // Make cell default network idle
        netdEventListener.onInterfaceClassActivityChanged(
                false, // isActive
                cellAgent.network.netId,
                TIMESTAMP,
                TEST_UID
        )

        // Set allow rule on background chain for TEST_UID
        doReturn(BLOCKED_REASON_NONE)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(TEST_UID)
        cm.setUidFirewallRule(
                FIREWALL_CHAIN_BACKGROUND,
                TEST_UID,
                FIREWALL_RULE_ALLOW
        )
        // Set deny rule on background chain for TEST_UID
        doReturn(BLOCKED_REASON_APP_BACKGROUND)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(TEST_UID2)
        cm.setUidFirewallRule(
                FIREWALL_CHAIN_BACKGROUND,
                TEST_UID2,
                FIREWALL_RULE_DENY
        )

        // Put only TEST_UID2 on background chain (deny TEST_UID and allow TEST_UID2)
        doReturn(setOf(TEST_UID))
                .`when`(bpfNetMaps).getUidsWithAllowRuleOnAllowListChain(FIREWALL_CHAIN_BACKGROUND)
        doReturn(BLOCKED_REASON_APP_BACKGROUND)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(TEST_UID)
        doReturn(BLOCKED_REASON_NONE)
                .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(TEST_UID2)
        cm.replaceFirewallChain(FIREWALL_CHAIN_BACKGROUND, intArrayOf(TEST_UID2))
        waitForIdle()
        inOrder.verify(destroySocketsWrapper, never())
                .destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID))

        netdEventListener.onInterfaceClassActivityChanged(
                true, // isActive
                cellAgent.network.netId,
                TIMESTAMP,
                TEST_UID
        )
        waitForIdle()
        inOrder.verify(destroySocketsWrapper)
                .destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID))

        cellAgent.disconnect()
    }

    private fun doTestDestroySockets(
            isFrozen: Boolean,
            denyOnBackgroundChain: Boolean,
            enableBackgroundChain: Boolean,
            expectDestroySockets: Boolean
    ) {
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

        if (!isFrozen) {
            // Unfreeze TEST_UID
            frozenStateCallback.onUidFrozenStateChanged(
                    intArrayOf(TEST_UID),
                    intArrayOf(UID_FROZEN_STATE_UNFROZEN)
            )
        }
        if (!enableBackgroundChain) {
            // Disable background chain
            cm.setFirewallChainEnabled(FIREWALL_CHAIN_BACKGROUND, false)
        }
        if (!denyOnBackgroundChain) {
            // Set allow rule on background chain for TEST_UID
            doReturn(BLOCKED_REASON_NONE)
                    .`when`(bpfNetMaps).getUidNetworkingBlockedReasons(TEST_UID)
            cm.setUidFirewallRule(
                    FIREWALL_CHAIN_BACKGROUND,
                    TEST_UID,
                    FIREWALL_RULE_ALLOW
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

        if (expectDestroySockets) {
            verify(destroySocketsWrapper).destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID))
        } else {
            verify(destroySocketsWrapper, never()).destroyLiveTcpSocketsByOwnerUids(setOf(TEST_UID))
        }
    }

    @Test
    fun testDestroySockets_backgroundDeny_frozen() {
        doTestDestroySockets(
                isFrozen = true,
                denyOnBackgroundChain = true,
                enableBackgroundChain = true,
                expectDestroySockets = true
        )
    }

    @Test
    fun testDestroySockets_backgroundDeny_nonFrozen() {
        doTestDestroySockets(
                isFrozen = false,
                denyOnBackgroundChain = true,
                enableBackgroundChain = true,
                expectDestroySockets = true
        )
    }

    @Test
    fun testDestroySockets_backgroundAllow_frozen() {
        doTestDestroySockets(
                isFrozen = true,
                denyOnBackgroundChain = false,
                enableBackgroundChain = true,
                expectDestroySockets = true
        )
    }

    @Test
    fun testDestroySockets_backgroundAllow_nonFrozen() {
        // If the app is neither frozen nor under background restriction, sockets are not
        // destroyed
        doTestDestroySockets(
                isFrozen = false,
                denyOnBackgroundChain = false,
                enableBackgroundChain = true,
                expectDestroySockets = false
        )
    }

    @Test
    fun testDestroySockets_backgroundChainDisabled_nonFrozen() {
        // If the app is neither frozen nor under background restriction, sockets are not
        // destroyed
        doTestDestroySockets(
                isFrozen = false,
                denyOnBackgroundChain = true,
                enableBackgroundChain = false,
                expectDestroySockets = false
        )
    }

    private fun setUpMockForRegistereQuicConnectionClosePayload(netId: Int) {
        deps.callingUid = TEST_UID
        deps.setSocketNetId(netId)
        deps.setSocketCookie(TEST_SOCKET_COOKIE)
        deps.setSockName(TEST_SRC_SOCKET_ADDRESS)
        deps.setPeerName(TEST_DST_SOCKET_ADDRESS)
        deps.setifindex(TEST_IFINDEX)
    }

    @Test
    fun testRegistereQuicConnectionClosePayload_unregisterSocket() {
        val wifiAgent = Agent(nc = wifiNc(), lp = wifiLp())
        wifiAgent.connect()
        setUpMockForRegistereQuicConnectionClosePayload(wifiAgent.network.netId)

        val socket = DatagramSocket()
        val pfd = ParcelFileDescriptor.fromDatagramSocket(socket)
        cm.registerQuicConnectionClosePayload(pfd, TEST_PAYLOAD)
        cm.unregisterQuicConnectionClosePayload(pfd)

        val frozenStateCallback = getUidFrozenStateChangedCallback()
        frozenStateCallback.onUidFrozenStateChanged(
                intArrayOf(TEST_UID),
                intArrayOf(UID_FROZEN_STATE_FROZEN)
        )

        // Connection close packet is not sent since socket is unregistered before the uid is
        // frozen.
        deps.assertNoSendQuicConnectionClose(TIMEOUT_MS)
    }

    @Test
    fun testRegistereQuicConnectionClosePayload_frozen() {
        val wifiAgent = Agent(nc = wifiNc(), lp = wifiLp())
        wifiAgent.connect()
        setUpMockForRegistereQuicConnectionClosePayload(wifiAgent.network.netId)

        val socket = DatagramSocket()
        val pfd = ParcelFileDescriptor.fromDatagramSocket(socket)
        cm.registerQuicConnectionClosePayload(pfd, TEST_PAYLOAD)

        val frozenStateCallback = getUidFrozenStateChangedCallback()
        frozenStateCallback.onUidFrozenStateChanged(
                intArrayOf(TEST_UID),
                intArrayOf(UID_FROZEN_STATE_FROZEN)
        )

        // Connection close packet is sent since the uid is frozen while the socket is registered.
        deps.expectSendQuicConnectionClose(
                wifiAgent.network,
                TEST_SRC_SOCKET_ADDRESS,
                TEST_DST_SOCKET_ADDRESS,
                TEST_PAYLOAD,
                TIMEOUT_MS
        )
    }

    @Test
    fun testRegistereQuicConnectionClosePayload_appCrash() {
        val wifiAgent = Agent(nc = wifiNc(), lp = wifiLp())
        wifiAgent.connect()
        setUpMockForRegistereQuicConnectionClosePayload(wifiAgent.network.netId)

        val socket = DatagramSocket()
        val pfd = ParcelFileDescriptor.fromDatagramSocket(socket)
        cm.registerQuicConnectionClosePayload(pfd, TEST_PAYLOAD)

        // When app crash or app is killed, socket is closed without calling
        // unregisterQuicConnectionClosePayload.
        // Send inetDiagMessage to simulate socket close.
        val inetDiagMessage = InetDiagMessage(StructNlMsgHdr())
        inetDiagMessage.inetDiagMsg.id = StructInetDiagSockId(TEST_SRC_SOCKET_ADDRESS,
                TEST_DST_SOCKET_ADDRESS, TEST_IFINDEX, TEST_SOCKET_COOKIE)
        deps.skDestroyListenerCallback?.accept(inetDiagMessage)

        // Connection close packet is sent since the socket is closed while the socket is
        // registered.
        deps.expectSendQuicConnectionClose(
                wifiAgent.network,
                TEST_SRC_SOCKET_ADDRESS,
                TEST_DST_SOCKET_ADDRESS,
                TEST_PAYLOAD,
                TIMEOUT_MS
        )
    }

    @Test
    fun testRegistereQuicConnectionClosePayload_firewallBackgroundChain() {
        val wifiAgent = Agent(nc = wifiNc(), lp = wifiLp())
        wifiAgent.connect()
        setUpMockForRegistereQuicConnectionClosePayload(wifiAgent.network.netId)

        val socket = DatagramSocket()
        val pfd = ParcelFileDescriptor.fromDatagramSocket(socket)
        cm.registerQuicConnectionClosePayload(pfd, TEST_PAYLOAD)

        val frozenStateCallback = getUidFrozenStateChangedCallback()
        frozenStateCallback.onUidFrozenStateChanged(
                intArrayOf(TEST_UID),
                intArrayOf(UID_FROZEN_STATE_FROZEN)
        )

        // Connection close packet is sent since the network of uid is blocked by background chain
        // while the socket is registered.
        deps.expectSendQuicConnectionClose(
                wifiAgent.network,
                TEST_SRC_SOCKET_ADDRESS,
                TEST_DST_SOCKET_ADDRESS,
                TEST_PAYLOAD,
                TIMEOUT_MS
        )
    }

    @Test
    fun testRegistereQuicConnectionClosePayload_unregisterNetwork() {
        val wifiAgent = Agent(nc = wifiNc(), lp = wifiLp())
        wifiAgent.connect()
        setUpMockForRegistereQuicConnectionClosePayload(wifiAgent.network.netId)

        val socket = DatagramSocket()
        val pfd = ParcelFileDescriptor.fromDatagramSocket(socket)
        cm.registerQuicConnectionClosePayload(pfd, TEST_PAYLOAD)

        wifiAgent.disconnect()

        val frozenStateCallback = getUidFrozenStateChangedCallback()
        frozenStateCallback.onUidFrozenStateChanged(
                intArrayOf(TEST_UID),
                intArrayOf(UID_FROZEN_STATE_FROZEN)
        )

        // Connection close packet is not sent since Wi-Fi network is already disconnected when the
        // uid is frozen.
        deps.assertNoSendQuicConnectionClose(TIMEOUT_MS)
    }

    @Test
    fun testRegistereQuicConnectionClosePayload_InterfaceAddressChange() {
        val wifiAgent = Agent(nc = wifiNc(), lp = wifiLp())
        wifiAgent.connect()
        setUpMockForRegistereQuicConnectionClosePayload(wifiAgent.network.netId)

        val socket = DatagramSocket()
        val pfd = ParcelFileDescriptor.fromDatagramSocket(socket)
        cm.registerQuicConnectionClosePayload(pfd, TEST_PAYLOAD)

        wifiAgent.sendLinkProperties(
                LinkProperties().also{
                    it.interfaceName = TEST_WIFI_IFACE
                    it.addLinkAddress(LinkAddress("2001:db8:1:2::7/64"))
                }
        )

        val frozenStateCallback = getUidFrozenStateChangedCallback()
        frozenStateCallback.onUidFrozenStateChanged(
                intArrayOf(TEST_UID),
                intArrayOf(UID_FROZEN_STATE_FROZEN)
        )

        // Connection close packet is not sent since the Wi-Fi interface does not use the source
        // address of the registered socket when the uid is frozen.
        deps.assertNoSendQuicConnectionClose(TIMEOUT_MS)
    }
}
