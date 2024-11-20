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
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.VpnManager
import android.net.VpnTransportInfo
import android.os.Build
import android.util.ArraySet
import android.util.Range
import com.android.net.module.util.BaseNetdUnsolicitedEventListener
import com.android.server.connectivity.ConnectivityFlags.DELAY_DESTROY_SOCKETS
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import java.net.InetAddress
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertNotNull
import junit.framework.Assert.assertTrue
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
private val TEST_UID_RANGE1 = Range(10000, 20000)
private val TEST_UID_RANGE2 = Range(21000, 30000)
private val TEST_UID_RANGE3 = Range(31000, 40000)
private const val TEST_CELL_IFACE = "test_rmnet"
private const val TEST_IPV6_ADDRESS1 = "2100::1234"
private const val TEST_IPV6_ADDRESS2 = "2100:0207::4321:1234"
private const val TEST_IPV6_ADDRESS3 = "FE80::1234"
private const val TEST_IPV6_ADDRESS4 = "2100:0207::1234"
private const val TEST_IPV4_ADDRESS1 = "203.0.113.10"
private const val TEST_IPV4_ADDRESS2 = "203.0.113.11"

private fun cellNc() = NetworkCapabilities.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
        .build()

private fun wlanNc() = NetworkCapabilities.Builder()
    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
    .build()

private fun vpnNc(uidRanges: Set<Range<Int>>) = NetworkCapabilities.Builder()
    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
    .setTransportInfo(
        VpnTransportInfo(VpnManager.TYPE_VPN_PLATFORM, null /* sessionId */,
            false /* bypassable */, false /* longLivedTcpConnectionsExpensive */)
    )
    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
    .setUids(uidRanges)
    .build()

private fun cellLp() = LinkProperties().also{
    it.interfaceName = TEST_CELL_IFACE
}

private fun makeLp(interfaceName: String) = LinkProperties().also{
    it.interfaceName = interfaceName
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

    private fun prepareNetworkAgent(interfaceName: String,
        addresses: List<String>, netCapabilities: NetworkCapabilities): CSAgentWrapper {
        val linkProperties = makeLp(interfaceName)
        for (address in addresses) {
            if (address.contains(":")) {
                linkProperties.addLinkAddress(LinkAddress("$address/64"))
            } else {
                linkProperties.addLinkAddress(LinkAddress("$address/24"))
            }
        }
        val agent = Agent(netCapabilities, lp = linkProperties)
        agent.connect()
        waitForIdle()
        return agent
    }

    @Test
    fun testIpToNetworksMap() {
        // Creates 3 NetworkAgent with various IP addresses(somre are used for only one network
        // agent some are used for multiple network agents.)
        val agent1 = prepareNetworkAgent(
            "rmnet1", arrayListOf(TEST_IPV6_ADDRESS1, TEST_IPV4_ADDRESS1), cellNc())
        val agent2 = prepareNetworkAgent(
            "wlan1", arrayListOf(TEST_IPV6_ADDRESS2, TEST_IPV4_ADDRESS1), wlanNc())
        val agent3 = prepareNetworkAgent(
            "rmnet2", arrayListOf(TEST_IPV6_ADDRESS2, TEST_IPV4_ADDRESS2), cellNc())
        val networks1 = service.mIpToNetworksMap.get(InetAddress.getByName(TEST_IPV6_ADDRESS1))
        val networks2 = service.mIpToNetworksMap.get(InetAddress.getByName(TEST_IPV4_ADDRESS1))
        val networks3 = service.mIpToNetworksMap.get(InetAddress.getByName(TEST_IPV6_ADDRESS2))
        val networks4 = service.mIpToNetworksMap.get(InetAddress.getByName(TEST_IPV4_ADDRESS2))
        // Verify mIpToNetworksMap properly stores network agents based on IP addresses.
        assertNotNull(networks1)
        assertNotNull(networks2)
        assertNotNull(networks3)
        assertNotNull(networks4)
        assertEquals(1, networks1?.size)
        assertEquals(2, networks2?.size)
        assertEquals(2, networks3?.size)
        assertEquals(1, networks4?.size)

        // Disconnect network agent#2.
        agent2.disconnect()
        waitForIdle()
        // Verify mIpToNetworksMap status is properly updated.
        assertEquals(1, networks1?.size)
        assertEquals(1, networks2?.size)
        assertEquals(1, networks3?.size)
        assertEquals(1, networks4?.size)

        // Disconnect network agent#3.
        agent3.disconnect()
        waitForIdle()
        // Verify mIpToNetworksMap status is properly updated.
        assertEquals(1, networks1?.size)
        assertEquals(1, networks2?.size)
        assertFalse(service.mIpToNetworksMap.contains(InetAddress.getByName(TEST_IPV6_ADDRESS2)))
        assertFalse(service.mIpToNetworksMap.contains(InetAddress.getByName(TEST_IPV4_ADDRESS2)))

        // Update only interface name without IP address change for network agent #1.
        val linkProperties = makeLp("ipsec1")
        linkProperties.addLinkAddress(LinkAddress("$TEST_IPV4_ADDRESS1/24"))
        linkProperties.addLinkAddress(LinkAddress("$TEST_IPV6_ADDRESS1/64"))
        agent1.sendLinkProperties(linkProperties)
        waitForIdle()
        // Verify mIpToNetworksMap status is not changed.
        assertEquals(1, networks1?.size)
        assertEquals(1, networks2?.size)

        // Change IP addresses for network agent #1.
        val linkProperties2 = makeLp("rmnet1")
        linkProperties2.addLinkAddress(LinkAddress("$TEST_IPV4_ADDRESS2/24"))
        linkProperties2.addLinkAddress(LinkAddress("$TEST_IPV6_ADDRESS1/64"))
        linkProperties2.addLinkAddress(LinkAddress("$TEST_IPV6_ADDRESS4/64"))
        agent1.sendLinkProperties(linkProperties2)
        waitForIdle()
        // Verify mIpToNetworksMap status is properly updated.
        assertEquals(1, networks1?.size)
        assertFalse(service.mIpToNetworksMap.contains(InetAddress.getByName(TEST_IPV4_ADDRESS1)))
        val networks5 = service.mIpToNetworksMap.get(InetAddress.getByName(TEST_IPV4_ADDRESS2))
        val networks6 = service.mIpToNetworksMap.get(InetAddress.getByName(TEST_IPV6_ADDRESS4))
        assertEquals(1, networks5?.size)
        assertEquals(1, networks6?.size)

        // Add network agent#4
        val agent4 = prepareNetworkAgent(
            "wlan2", arrayListOf(TEST_IPV6_ADDRESS3, TEST_IPV4_ADDRESS2), wlanNc())
        waitForIdle()
        // Verify mIpToNetworksMap status is properly updated.
        assertFalse(service.mIpToNetworksMap.contains(InetAddress.getByName(TEST_IPV6_ADDRESS3)))
        assertEquals(2, networks5?.size)

        // Disconnect all remaining network agents.
        agent1.disconnect()
        agent4.disconnect()
        waitForIdle()
        // Verify mIpToNetworksMap is empty.
        assertTrue(service.mIpToNetworksMap.isEmpty())
    }

    @Test
    fun testDestroySocketForRemovedIpAddressFromSingleNetwork() {
        val inOrder = inOrder(destroySocketsWrapper)
        // add a test network.
        val agent1 = prepareNetworkAgent(
            "wlan2", arrayListOf(TEST_IPV6_ADDRESS1), wlanNc())

        // IP address changed
        val linkProperties2 = makeLp("wlan2")
        linkProperties2.addLinkAddress(LinkAddress("$TEST_IPV6_ADDRESS2/64"))
        agent1.sendLinkProperties(linkProperties2)
        waitForIdle()

        // verify destroy sockets on the removed IP address
        inOrder.verify(destroySocketsWrapper).destroyLiveTcpSocketsByLocalAddress(
            InetAddress.getByName(TEST_IPV6_ADDRESS1), null, null, 0, 0xffff)

        // network disconnect
        agent1.disconnect()
        waitForIdle()

        // verify destroy sockets on the removed IP address
        inOrder.verify(destroySocketsWrapper).destroyLiveTcpSocketsByLocalAddress(
            InetAddress.getByName(TEST_IPV6_ADDRESS2), null, null, 0, 0xffff)
        inOrder.verifyNoMoreInteractions()
    }

    @Test
    fun testDestroySocketForRemovedIpAddressFromMultipleNonVpnNetworks() {
        val inOrder = inOrder(destroySocketsWrapper)

        // add 4 test networks(non VPN). 3 have a same IP address, and 1 network has different one.
        val agent1 = prepareNetworkAgent(
            "rmnet1", arrayListOf(TEST_IPV6_ADDRESS1, TEST_IPV4_ADDRESS1), cellNc())
        val agent2 = prepareNetworkAgent(
            "wlan0", arrayListOf(TEST_IPV6_ADDRESS3, TEST_IPV6_ADDRESS1), wlanNc())
        val agent3 = prepareNetworkAgent(
            "rmnet2", arrayListOf(TEST_IPV6_ADDRESS2), cellNc())
        val agent4 = prepareNetworkAgent(
            "ipsec1", arrayListOf(TEST_IPV6_ADDRESS1), wlanNc())
        // disconnect one network which has the same IP address with other networks.
        agent1.disconnect()
        waitForIdle()
        val exemptNetIds = ArraySet<Int>()
        exemptNetIds.add(agent2.network.netId)
        exemptNetIds.add(agent4.network.netId)
        // verify destroy sockets on the removed IP address and exempt netId lists.
        inOrder.verify(destroySocketsWrapper).destroyLiveTcpSocketsByLocalAddress(
            InetAddress.getByName(TEST_IPV6_ADDRESS1),
            setOf(agent2.network.netId, agent4.network.netId), null, 0, 0xffff)
        // verify destroy sockets on the removed IP address
        inOrder.verify(destroySocketsWrapper).destroyLiveTcpSocketsByLocalAddress(
            InetAddress.getByName(TEST_IPV4_ADDRESS1),
            null, null, 0, 0xffff)

        // disconnect network#2
        agent2.disconnect()
        waitForIdle()
        // verify destroy sockets on the removed IP address and exempt netId lists.
        inOrder.verify(destroySocketsWrapper).destroyLiveTcpSocketsByLocalAddress(
            InetAddress.getByName(TEST_IPV6_ADDRESS1),
            setOf(agent4.network.netId), null, 0, 0xffff)
        // verify not to destroy sockets on the link local address at NetworkAgent update.
        inOrder.verify(destroySocketsWrapper, never()).destroyLiveTcpSocketsByLocalAddress(
            InetAddress.getByName(TEST_IPV6_ADDRESS3), null, null, 0, 0xffff)

        // disconnect network#3
        agent3.disconnect()
        waitForIdle()
        // verify destroy sockets on the removed IP address
        inOrder.verify(destroySocketsWrapper).destroyLiveTcpSocketsByLocalAddress(
            InetAddress.getByName(TEST_IPV6_ADDRESS2), null, null, 0, 0xffff)

        // disconnect network#4
        agent4.disconnect()
        waitForIdle()
        // verify destroy sockets on the removed IP address
        inOrder.verify(destroySocketsWrapper).destroyLiveTcpSocketsByLocalAddress(
            InetAddress.getByName(TEST_IPV6_ADDRESS1), null, null, 0, 0xffff)
    }

    @Test
    fun testDestroySocketForRemovedIpAddressFromMultipleVpnNetworks() {
        val inOrder = inOrder(destroySocketsWrapper)

        // add 3 VPN test networks and 1 non-VPN network.
        val agent1 = prepareNetworkAgent(
            "ipsec1", arrayListOf(TEST_IPV6_ADDRESS1, TEST_IPV4_ADDRESS1),
            vpnNc(setOf(TEST_UID_RANGE1)))
        val agent2 = prepareNetworkAgent(
            "ipsec2", arrayListOf(
                TEST_IPV6_ADDRESS3, TEST_IPV6_ADDRESS1), vpnNc(setOf(TEST_UID_RANGE2)))
        val agent3 = prepareNetworkAgent(
            "ipsec3", arrayListOf(TEST_IPV6_ADDRESS1), vpnNc(setOf(TEST_UID_RANGE3)))
        val agent4 = prepareNetworkAgent(
            "rmnet1", arrayListOf(TEST_IPV6_ADDRESS2, TEST_IPV4_ADDRESS1), cellNc())
        waitForIdle()
        // disconnect 1 VPN network(#1).
        agent1.disconnect()
        waitForIdle()
        // verify destroy sockets on the removed IP address(TEST_IPV6_ADDRESS1) and VPN's uid range.
        inOrder.verify(destroySocketsWrapper).destroyLiveTcpSocketsByLocalAddress(
            InetAddress.getByName(TEST_IPV6_ADDRESS1), null, agent1.nc.uids, 0, 0xffff)
        // verify destroy sockets on the removed IP address(TEST_IPV4_ADDRESS1). network#1 and
        // network#4 use TEST_IPV4_ADDRESS1, however network#4 is not VPN network, so we destroy
        // sockets only based on the removed IP address.
        inOrder.verify(destroySocketsWrapper).destroyLiveTcpSocketsByLocalAddress(
            InetAddress.getByName(TEST_IPV4_ADDRESS1), null, null, 0, 0xffff)

        // disconnect 1 VPN network(#2).
        agent2.disconnect()
        waitForIdle()
        // verify destroy sockets on the removed IP address(TEST_IPV6_ADDRESS1) and VPN's uid range.
        inOrder.verify(destroySocketsWrapper).destroyLiveTcpSocketsByLocalAddress(
            InetAddress.getByName(TEST_IPV6_ADDRESS1), null, agent2.nc.uids, 0, 0xffff)
        // verify not to destroy sockets on the link local address at NetworkAgent update.
        inOrder.verify(destroySocketsWrapper, never()).destroyLiveTcpSocketsByLocalAddress(
            InetAddress.getByName(TEST_IPV6_ADDRESS3), null, null, 0, 0xffff)

        agent3.disconnect()
        waitForIdle()
        // verify destroy sockets on the removed IP address(TEST_IPV6_ADDRESS1).
        inOrder.verify(destroySocketsWrapper).destroyLiveTcpSocketsByLocalAddress(
            InetAddress.getByName(TEST_IPV6_ADDRESS1), null, null, 0, 0xffff)

        agent4.disconnect()
        waitForIdle()
        // verify destroy sockets on the removed IP address.
        inOrder.verify(destroySocketsWrapper).destroyLiveTcpSocketsByLocalAddress(
            InetAddress.getByName(TEST_IPV6_ADDRESS2), null, null, 0, 0xffff)
        inOrder.verify(destroySocketsWrapper).destroyLiveTcpSocketsByLocalAddress(
            InetAddress.getByName(TEST_IPV4_ADDRESS1), null, null, 0, 0xffff)
    }
}
