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
import android.net.LocalNetworkInfo
import android.net.Network
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
import android.net.RouteInfo
import android.os.Build
import android.util.Log
import com.android.server.TestableNetworkCallback.LocalInfoChanged
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.RecorderCallback.CallbackEntry.Available
import com.android.testutils.RecorderCallback.CallbackEntry.BlockedStatus
import com.android.testutils.RecorderCallback.CallbackEntry.CapabilitiesChanged
import com.android.testutils.RecorderCallback.CallbackEntry.LinkPropertiesChanged
import com.android.testutils.RecorderCallback.CallbackEntry.Lost
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import kotlin.test.assertFailsWith
import com.android.testutils.TestableNetworkCallback as BaseTestableNetworkCallback

// Stopgap solution to see LocalNetworkInfo in the testable network callbacks.
// When LocalNetworkInfo is public, remove this class and reinstate the code commented out
// in the original TestableNetworkCallback
private class TestableNetworkCallback() : BaseTestableNetworkCallback() {
    private val TAG = this::class.simpleName

    data class LocalInfoChanged(
            override val network: Network,
            val info: LocalNetworkInfo)
        : CallbackEntry()

    override fun onLocalNetworkInfoChanged(network: Network, info: LocalNetworkInfo) {
        Log.d(TAG, "onLocalNetworkInfoChanged $network ${info.upstreamNetwork}")
        history.add(LocalInfoChanged(network, info))
    }
}

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
            Agent(nc = NetworkCapabilities.Builder()
                    .addCapability(NET_CAPABILITY_LOCAL_NETWORK)
                    .build(),
                    lnc = null)
        }
    }

    @Test
    fun testForwardingRules() {
        deps.setBuildSdk(VERSION_V)
        // Set up a local agent that should forward its traffic to the best DUN upstream.
        val localAgent = Agent(nc = nc(TRANSPORT_WIFI, NET_CAPABILITY_LOCAL_NETWORK),
                lp = lp("local0"),
                lnc = LocalNetworkConfig.Builder()
                        .setUpstreamSelector(NetworkRequest.Builder()
                                .addCapability(NET_CAPABILITY_DUN)
                                .build())
                        .build(),
                score = FromS(NetworkScore.Builder()
                        .setKeepConnectedReason(NetworkScore.KEEP_CONNECTED_DOWNSTREAM_NETWORK)
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
        cb.expect<Available>(localAgent.network)
        cb.expect<CapabilitiesChanged>(localAgent.network)
        cb.expect<LinkPropertiesChanged>(localAgent.network)
        cb.expect<LocalInfoChanged>(localAgent.network) { it.info.upstreamNetwork == null }
        cb.expect<BlockedStatus>(localAgent.network)

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
        inOrder.verify(netd).ipfwdAddInterfaceForward("local0", "wifi1")
        inOrder.verify(netd).ipfwdRemoveInterfaceForward("local0", "cell0")
        cb.expect<LocalInfoChanged>(localAgent.network) {
            it.info.upstreamNetwork == wifiAgentDun.network
        }

        wifiAgentDun.disconnect()
        // This is a disconnection, so the "remove" will precede the "add" because the forward
        // is removed as part of the disconnection and the add as part of the rematch that follows.
        cb.expect<LocalInfoChanged>(localAgent.network) { it.info.upstreamNetwork == null }
        cb.expect<LocalInfoChanged>(localAgent.network) {
            it.info.upstreamNetwork == cellAgentDun.network
        }
        inOrder.verify(netd).ipfwdRemoveInterfaceForward("local0", "wifi1")
        inOrder.verify(netd).ipfwdAddInterfaceForward("local0", "cell0")

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

        localAgent.disconnect()
        inOrder.verify(netd).ipfwdRemoveInterfaceForward("local0", "wifi2")
        inOrder.verify(netd).ipfwdDisableForwarding(any())
        cb.expect<Lost>(localAgent.network)
        cb.assertNoCallback()
    }
}
