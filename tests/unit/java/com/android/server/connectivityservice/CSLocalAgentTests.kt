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

import android.net.IpPrefix
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.LocalNetworkConfig
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_DUN
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.net.NetworkScore
import android.net.NetworkScore.KEEP_CONNECTED_FOR_TEST
import android.net.NetworkScore.KEEP_CONNECTED_LOCAL_NETWORK
import android.net.RouteInfo
import android.os.Build
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.RecorderCallback.CallbackEntry.LocalInfoChanged
import com.android.testutils.RecorderCallback.CallbackEntry.Lost
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import kotlin.test.assertFailsWith

private const val TIMEOUT_MS = 200L
private const val MEDIUM_TIMEOUT_MS = 1_000L
private const val LONG_TIMEOUT_MS = 5_000

private fun nc(transport: Int, vararg caps: Int) = NetworkCapabilities.Builder().apply {
    addTransportType(transport)
    caps.forEach {
        addCapability(it)
    }
    // Useful capabilities for everybody
    addCapability(NET_CAPABILITY_NOT_RESTRICTED)
    addCapability(NET_CAPABILITY_NOT_SUSPENDED)
    addCapability(NET_CAPABILITY_NOT_ROAMING)
    addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
}.build()

private fun lp(iface: String) = LinkProperties().apply {
    interfaceName = iface
    addLinkAddress(LinkAddress(LOCAL_IPV4_ADDRESS, 32))
    addRoute(RouteInfo(IpPrefix("0.0.0.0/0"), null, null))
}

// This allows keeping all the networks connected without having to file individual requests
// for them.
private fun keepScore() = FromS(
        NetworkScore.Builder().setKeepConnectedReason(KEEP_CONNECTED_FOR_TEST).build()
)

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
class CSLocalAgentTests : CSTest() {
    @Test
    fun testBadAgents() {
        assertFailsWith<IllegalArgumentException> {
            Agent(nc = NetworkCapabilities.Builder()
                    .addCapability(NET_CAPABILITY_LOCAL_NETWORK)
                    .build(),
                    lnc = null)
        }
        assertFailsWith<IllegalArgumentException> {
            Agent(nc = NetworkCapabilities.Builder().build(),
                    lnc = LocalNetworkConfig.Builder().build())
        }
    }

    @Test
    fun testStructuralConstraintViolation() {
        val cb = TestNetworkCallback()
        cm.requestNetwork(NetworkRequest.Builder()
                .clearCapabilities()
                .build(),
                cb)
        val agent = Agent(nc = NetworkCapabilities.Builder()
                .addCapability(NET_CAPABILITY_LOCAL_NETWORK)
                .build(),
                lnc = LocalNetworkConfig.Builder().build())
        agent.connect()
        cb.expectAvailableCallbacks(agent.network)
        agent.sendNetworkCapabilities(NetworkCapabilities.Builder().build())
        cb.expect<Lost>(agent.network)

        val agent2 = Agent(nc = NetworkCapabilities.Builder()
                .build(),
                lnc = null)
        agent2.connect()
        cb.expectAvailableCallbacks(agent.network, validated = false)
        agent2.sendNetworkCapabilities(NetworkCapabilities.Builder()
                .addCapability(NET_CAPABILITY_LOCAL_NETWORK)
                .build())
        cb.expect<Lost>(agent2.network)
    }

    @Test
    fun testUpdateLocalAgentConfig() {
        deps.setBuildSdk(VERSION_V)

        val cb = TestableNetworkCallback()
        cm.requestNetwork(NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_LOCAL_NETWORK)
                .build(),
                cb)

        // Set up a local agent that should forward its traffic to the best DUN upstream.
        val localAgent = Agent(
                nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_LOCAL_NETWORK),
                lp = lp("local0"),
                lnc = LocalNetworkConfig.Builder().build(),
        )
        localAgent.connect()

        cb.expectAvailableCallbacks(localAgent.network)

        val wifiAgent = Agent(score = keepScore(), lp = lp("wifi0"),
                nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_INTERNET))
        wifiAgent.connect()

        val newLnc = LocalNetworkConfig.Builder()
                .setUpstreamSelector(NetworkRequest.Builder()
                        .addTransportType(TRANSPORT_WIFI)
                        .build())
                .build()
        localAgent.sendLocalNetworkConfig(newLnc)

        cb.expect<LocalInfoChanged>(localAgent.network) {
            it.info.upstreamNetwork == wifiAgent.network
        }

        localAgent.sendLocalNetworkConfig(LocalNetworkConfig.Builder().build())
        cb.expect<LocalInfoChanged>(localAgent.network) { it.info.upstreamNetwork == null }

        localAgent.sendLocalNetworkConfig(newLnc)
        cb.expect<LocalInfoChanged>(localAgent.network) {
            it.info.upstreamNetwork == wifiAgent.network
        }

        wifiAgent.disconnect()
        cb.expect<LocalInfoChanged>(localAgent.network) { it.info.upstreamNetwork == null }

        localAgent.disconnect()
    }

    @Test
    fun testUnregisterUpstreamAfterReplacement_SameIfaceName() {
        doTestUnregisterUpstreamAfterReplacement(true)
    }

    @Test
    fun testUnregisterUpstreamAfterReplacement_DifferentIfaceName() {
        doTestUnregisterUpstreamAfterReplacement(false)
    }

    fun doTestUnregisterUpstreamAfterReplacement(sameIfaceName: Boolean) {
        deps.setBuildSdk(VERSION_V)
        val cb = TestNetworkCallback()
        cm.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities().build(), cb)

        // Set up a local agent that should forward its traffic to the best wifi upstream.
        val localAgent = Agent(nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_LOCAL_NETWORK),
                lp = lp("local0"),
                lnc = LocalNetworkConfig.Builder()
                .setUpstreamSelector(NetworkRequest.Builder()
                        .addTransportType(TRANSPORT_WIFI)
                        .build())
                .build(),
                score = FromS(NetworkScore.Builder()
                        .setKeepConnectedReason(KEEP_CONNECTED_LOCAL_NETWORK)
                        .build())
        )
        localAgent.connect()

        cb.expectAvailableCallbacks(localAgent.network)

        val wifiAgent = Agent(lp = lp("wifi0"),
                nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_INTERNET))
        wifiAgent.connect()

        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)
        cb.expect<LocalInfoChanged>(localAgent.network) {
            it.info.upstreamNetwork == wifiAgent.network
        }

        clearInvocations(netd)
        val inOrder = inOrder(netd)
        wifiAgent.unregisterAfterReplacement(LONG_TIMEOUT_MS)
        waitForIdle()
        inOrder.verify(netd).ipfwdRemoveInterfaceForward("local0", "wifi0")
        inOrder.verify(netd).networkDestroy(wifiAgent.network.netId)

        val wifiAgent2 = Agent(lp = lp(if (sameIfaceName) "wifi0" else "wifi1"),
                nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_INTERNET))
        wifiAgent2.connect()
        cb.expect<LocalInfoChanged>(localAgent.network) {
            it.info.upstreamNetwork == wifiAgent2.network
        }

        if (sameIfaceName) {
            // TODO : wait wat
            cb.expect<Lost> { it.network == wifiAgent.network }
            cb.expectAvailableCallbacks(wifiAgent2.network, validated = false)
        } else {
            cb.expectAvailableCallbacks(wifiAgent2.network, validated = false)
            cb.expect<Lost> { it.network == wifiAgent.network }
        }

        if (sameIfaceName) {
            inOrder.verify(netd, never()).ipfwdAddInterfaceForward(any(), any())
            inOrder.verify(netd, never()).ipfwdRemoveInterfaceForward(any(), any())
        } else {
            inOrder.verify(netd).ipfwdAddInterfaceForward("local0", "wifi1")
        }
    }

    @Test
    fun testUnregisterUpstreamAfterReplacement_neverReplaced() {
        deps.setBuildSdk(VERSION_V)
        val cb = TestNetworkCallback()
        cm.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities().build(), cb)

        // Set up a local agent that should forward its traffic to the best wifi upstream.
        val localAgent = Agent(nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_LOCAL_NETWORK),
                lp = lp("local0"),
                lnc = LocalNetworkConfig.Builder()
                        .setUpstreamSelector(NetworkRequest.Builder()
                                .addTransportType(TRANSPORT_WIFI)
                                .build())
                        .build(),
                score = FromS(NetworkScore.Builder()
                        .setKeepConnectedReason(KEEP_CONNECTED_LOCAL_NETWORK)
                        .build())
        )
        localAgent.connect()

        cb.expectAvailableCallbacks(localAgent.network)

        val wifiAgent = Agent(lp = lp("wifi0"),
                nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_INTERNET))
        wifiAgent.connect()

        cb.expectAvailableCallbacksUnvalidated(wifiAgent)

        clearInvocations(netd)
        wifiAgent.unregisterAfterReplacement(TIMEOUT_MS.toInt())
        waitForIdle()
        verify(netd).networkDestroy(wifiAgent.network.netId)
        verify(netd).ipfwdRemoveInterfaceForward("local0", "wifi0")

        cb.expect<Lost> { it.network == wifiAgent.network }
    }

    @Test
    fun testUnregisterLocalAgentAfterReplacement() {
        deps.setBuildSdk(VERSION_V)

        val localCb = TestNetworkCallback()
        cm.requestNetwork(NetworkRequest.Builder().clearCapabilities()
                .addCapability(NET_CAPABILITY_LOCAL_NETWORK)
                .build(),
                localCb)

        val cb = TestNetworkCallback()
        cm.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities().build(), cb)

        val localNc = nc(TRANSPORT_WIFI, NET_CAPABILITY_LOCAL_NETWORK)
        val lnc = LocalNetworkConfig.Builder()
                .setUpstreamSelector(NetworkRequest.Builder()
                        .addTransportType(TRANSPORT_WIFI)
                        .build())
                .build()
        val localScore = FromS(NetworkScore.Builder().build())

        // Set up a local agent that should forward its traffic to the best wifi upstream.
        val localAgent = Agent(nc = localNc, lp = lp("local0"), lnc = lnc, score = localScore)
        localAgent.connect()

        localCb.expectAvailableCallbacks(localAgent.network)
        cb.expectAvailableCallbacks(localAgent.network)

        val wifiAgent = Agent(lp = lp("wifi0"), nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_INTERNET))
        wifiAgent.connect()

        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)
        listOf(cb, localCb).forEach {
            it.expect<LocalInfoChanged>(localAgent.network) {
                it.info.upstreamNetwork == wifiAgent.network
            }
        }

        verify(netd).ipfwdAddInterfaceForward("local0", "wifi0")

        localAgent.unregisterAfterReplacement(LONG_TIMEOUT_MS)

        val localAgent2 = Agent(nc = localNc, lp = lp("local0"), lnc = lnc, score = localScore)
        localAgent2.connect()

        localCb.expectAvailableCallbacks(localAgent2.network, upstream = wifiAgent.network)
        cb.expectAvailableCallbacks(localAgent2.network, upstream = wifiAgent.network)
        cb.expect<Lost> { it.network == localAgent.network }
    }

    @Test
    fun testDestroyedNetworkAsSelectedUpstream() {
        val cb = TestNetworkCallback()
        cm.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities().build(), cb)

        val wifiAgent = Agent(lp = lp("wifi0"), nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_INTERNET))
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        // Set up a local agent that should forward its traffic to the best wifi upstream.
        val localAgent = Agent(nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_LOCAL_NETWORK),
                lp = lp("local0"),
                lnc = LocalNetworkConfig.Builder()
                        .setUpstreamSelector(NetworkRequest.Builder()
                                .addTransportType(TRANSPORT_WIFI)
                                .build())
                        .build(),
                score = FromS(NetworkScore.Builder()
                        .setKeepConnectedReason(KEEP_CONNECTED_LOCAL_NETWORK)
                        .build())
        )

        // ...but destroy the wifi agent before connecting the local agent
        wifiAgent.unregisterAfterReplacement(LONG_TIMEOUT_MS)

        localAgent.connect()
        cb.expectAvailableCallbacks(localAgent.network, upstream = wifiAgent.network)

        verify(netd, never()).ipfwdAddInterfaceForward("local0", "wifi0")
        verify(netd, never()).ipfwdRemoveInterfaceForward("local0", "wifi0")
    }

    @Test
    fun testForwardingRules() {
        deps.setBuildSdk(VERSION_V)
        // Set up a local agent that should forward its traffic to the best DUN upstream.
        val lnc = LocalNetworkConfig.Builder()
                .setUpstreamSelector(NetworkRequest.Builder()
                        .addCapability(NET_CAPABILITY_DUN)
                        .build())
                .build()
        val localAgent = Agent(nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_LOCAL_NETWORK),
                lp = lp("local0"),
                lnc = lnc,
                score = FromS(NetworkScore.Builder()
                        .setKeepConnectedReason(KEEP_CONNECTED_LOCAL_NETWORK)
                        .build())
        )
        localAgent.connect()

        val wifiAgent = Agent(score = keepScore(), lp = lp("wifi0"),
                nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_INTERNET))
        val cellAgentDun = Agent(score = keepScore(), lp = lp("cell0"),
                nc = nc(TRANSPORT_CELLULAR, NET_CAPABILITY_INTERNET, NET_CAPABILITY_DUN))
        val wifiAgentDun = Agent(score = keepScore(), lp = lp("wifi1"),
                nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_INTERNET, NET_CAPABILITY_DUN))

        val cb = TestableNetworkCallback()
        cm.registerNetworkCallback(NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_LOCAL_NETWORK)
                .build(),
                cb)
        cb.expectAvailableCallbacks(localAgent.network)

        val inOrder = inOrder(netd)
        inOrder.verify(netd, never()).ipfwdAddInterfaceForward(any(), any())
        cb.assertNoCallback()

        wifiAgent.connect()
        inOrder.verify(netd, never()).ipfwdAddInterfaceForward(any(), any())
        cb.assertNoCallback()

        cellAgentDun.connect()
        inOrder.verify(netd).ipfwdEnableForwarding(any())
        inOrder.verify(netd).ipfwdAddInterfaceForward("local0", "cell0")
        cb.expect<LocalInfoChanged>(localAgent.network) {
            it.info.upstreamNetwork == cellAgentDun.network
        }

        wifiAgentDun.connect()
        inOrder.verify(netd).ipfwdRemoveInterfaceForward("local0", "cell0")
        inOrder.verify(netd).ipfwdAddInterfaceForward("local0", "wifi1")
        cb.expect<LocalInfoChanged>(localAgent.network) {
            it.info.upstreamNetwork == wifiAgentDun.network
        }

        // Make sure sending the same config again doesn't do anything
        repeat(5) {
            localAgent.sendLocalNetworkConfig(lnc)
        }
        inOrder.verifyNoMoreInteractions()

        wifiAgentDun.disconnect()
        cb.expect<LocalInfoChanged>(localAgent.network) { it.info.upstreamNetwork == null }
        cb.expect<LocalInfoChanged>(localAgent.network) {
            it.info.upstreamNetwork == cellAgentDun.network
        }
        inOrder.verify(netd).ipfwdRemoveInterfaceForward("local0", "wifi1")
        // This can take a little bit of time because it needs to wait for the rematch
        inOrder.verify(netd, timeout(MEDIUM_TIMEOUT_MS)).ipfwdAddInterfaceForward("local0", "cell0")

        cellAgentDun.disconnect()
        inOrder.verify(netd).ipfwdRemoveInterfaceForward("local0", "cell0")
        inOrder.verify(netd).ipfwdDisableForwarding(any())
        cb.expect<LocalInfoChanged>(localAgent.network) { it.info.upstreamNetwork == null }

        val wifiAgentDun2 = Agent(score = keepScore(), lp = lp("wifi2"),
                nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_INTERNET, NET_CAPABILITY_DUN))
        wifiAgentDun2.connect()
        inOrder.verify(netd).ipfwdEnableForwarding(any())
        inOrder.verify(netd).ipfwdAddInterfaceForward("local0", "wifi2")
        cb.expect<LocalInfoChanged>(localAgent.network) {
            it.info.upstreamNetwork == wifiAgentDun2.network
        }

        wifiAgentDun2.disconnect()
        inOrder.verify(netd).ipfwdRemoveInterfaceForward("local0", "wifi2")
        inOrder.verify(netd).ipfwdDisableForwarding(any())
        cb.expect<LocalInfoChanged>(localAgent.network) { it.info.upstreamNetwork == null }

        val wifiAgentDun3 = Agent(score = keepScore(), lp = lp("wifi3"),
                nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_INTERNET, NET_CAPABILITY_DUN))
        wifiAgentDun3.connect()
        inOrder.verify(netd).ipfwdEnableForwarding(any())
        inOrder.verify(netd).ipfwdAddInterfaceForward("local0", "wifi3")
        cb.expect<LocalInfoChanged>(localAgent.network) {
            it.info.upstreamNetwork == wifiAgentDun3.network
        }

        localAgent.disconnect()
        inOrder.verify(netd).ipfwdRemoveInterfaceForward("local0", "wifi3")
        inOrder.verify(netd).ipfwdDisableForwarding(any())
        cb.expect<Lost>(localAgent.network)
        cb.assertNoCallback()
    }
}
