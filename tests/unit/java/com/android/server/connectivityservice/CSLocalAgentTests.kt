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

import android.net.ConnectivityManager
import android.net.INetd
import android.net.LinkProperties
import android.net.LocalNetworkInfo
import android.net.NativeNetworkConfig
import android.net.NativeNetworkType
import android.net.Network
import android.net.NetworkAgentConfig
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK
import android.net.NetworkProvider
import android.net.NetworkRequest
import android.net.NetworkScore
import android.net.VpnManager
import android.os.Build
import androidx.test.filters.SmallTest
import com.android.server.WaitStyle.WAIT_FOR_CALLBACK
import com.android.server.WaitStyle.WAIT_FOR_IDLE
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.waitForIdle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.InOrder
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.timeout
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeoutException
import kotlin.test.assertFailsWith

private const val TIMEOUT_MS = 2_000L

// See LocalAgentWrapper#connect below.
private enum class WaitStyle { WAIT_FOR_CALLBACK, WAIT_FOR_IDLE }

private fun defaultLni() = LocalNetworkInfo.Builder().build()

@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@IgnoreUpTo(Build.VERSION_CODES.R)
class CSLocalAgentTests : CSTest() {
    private inner class LocalAgentWrapper(
            nac: NetworkAgentConfig = emptyAgentConfig(),
            nc: NetworkCapabilities = defaultNc(),
            lp: LinkProperties = defaultLp(),
            lni: LocalNetworkInfo = defaultLni(),
            score: FromS<NetworkScore> = defaultScore(),
            provider: NetworkProvider? = null,
    ) : CSAgentWrapper(context, csHandlerThread, networkStack, nac, nc, lp, lni, score, provider) {
        // Before V, local network agents are not visible at all through callbacks so it
        // is not possible to wait for the agent to connect by blocking on a callback. For
        // this case only, wait for idle.
        fun connect(style: WaitStyle) {
            when (style) {
                WAIT_FOR_CALLBACK -> super.connect()
                WAIT_FOR_IDLE -> {
                    agent.markConnected()
                    csHandlerThread.waitForIdle(TIMEOUT_MS)
                }
            }
        }
    }

    @Test
    fun testLocalAgents() {
        val netdInOrder = inOrder(netd)
        doTestLocalAgents(netdInOrder, sdkAtLeastV = true, addCapability = true)
        doTestLocalAgents(netdInOrder, sdkAtLeastV = true, addCapability = false)
        doTestLocalAgents(netdInOrder, sdkAtLeastV = false, addCapability = false)
        // No targetSdk=V apps on devices with SDK < V, so no point testing
    }

    private fun makeNativeNetworkConfigLocal(netId: Int, permission: Int) =
            NativeNetworkConfig(netId, NativeNetworkType.PHYSICAL_LOCAL, permission,
                    false /* secure */, VpnManager.TYPE_VPN_NONE, false /* excludeLocalRoutes */)

    private fun doTestLocalAgents(
            netdInOrder: InOrder,
            sdkAtLeastV: Boolean,
            addCapability: Boolean
    ) {
        deps.setBuildSdk(if (sdkAtLeastV) VERSION_V else VERSION_U)
        val availableNetwork = CompletableFuture<Network>()
        val allNetworksCb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                availableNetwork.complete(network)
            }
        }
        val allNetworksRequest = NetworkRequest.Builder()
        if (addCapability) {
            allNetworksRequest.addCapability(NET_CAPABILITY_LOCAL_NETWORK)
        }
        cm.registerNetworkCallback(allNetworksRequest.build(), allNetworksCb)
        val ncTemplate = NetworkCapabilities.Builder().run {
            addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            addCapability(NET_CAPABILITY_LOCAL_NETWORK)
        }.build()
        val wifiAgent = LocalAgentWrapper(nc = ncTemplate)
        // Before V, local agents are not visible to callbacks and connecting has to wait for idle.
        wifiAgent.connect(if (sdkAtLeastV) WAIT_FOR_CALLBACK else WAIT_FOR_IDLE)
        if (sdkAtLeastV) {
            netdInOrder.verify(netd).networkCreate(
                    makeNativeNetworkConfigLocal(wifiAgent.network.netId, INetd.PERMISSION_NONE))
        } else {
            netdInOrder.verify(netd, never()).networkCreate(any())
        }
        if (addCapability) {
            assertEquals(wifiAgent.network, availableNetwork.get(TIMEOUT_MS, MILLISECONDS))
        } else {
            assertFailsWith<TimeoutException> {
                availableNetwork.get(TIMEOUT_MS, MILLISECONDS)
            }
        }
        cm.unregisterNetworkCallback(allNetworksCb)
        wifiAgent.disconnect()
        if (sdkAtLeastV) {
            netdInOrder.verify(netd, timeout(TIMEOUT_MS)).networkDestroy(wifiAgent.network.netId)
        } else {
            netdInOrder.verifyNoMoreInteractions()
        }
    }
}
