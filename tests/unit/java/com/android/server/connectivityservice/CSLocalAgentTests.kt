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

import android.net.INetd
import android.net.LinkProperties
import android.net.LocalNetworkInfo
import android.net.NativeNetworkConfig
import android.net.NativeNetworkType
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
import com.android.testutils.RecorderCallback.CallbackEntry.Available
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.waitForIdle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.InOrder
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.timeout
import kotlin.test.assertFalse

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
        doTestLocalAgents(netdInOrder, sdkLevel = VERSION_V, addLocalNetCap = true)
        doTestLocalAgents(netdInOrder, sdkLevel = VERSION_V, addLocalNetCap = false)
        doTestLocalAgents(netdInOrder, sdkLevel = VERSION_U, addLocalNetCap = false)
        // No targetSdk=V apps on devices with SDK < V, so no point testing
    }

    private fun makeNativeNetworkConfigLocal(netId: Int, permission: Int) =
            NativeNetworkConfig(netId, NativeNetworkType.PHYSICAL_LOCAL, permission,
                    false /* secure */, VpnManager.TYPE_VPN_NONE, false /* excludeLocalRoutes */)

    private fun doTestLocalAgents(
            netdInOrder: InOrder,
            sdkLevel: Int,
            addLocalNetCap: Boolean
    ) {
        deps.setBuildSdk(sdkLevel)
        val allNetworksCb = TestableNetworkCallback()
        val request = NetworkRequest.Builder()
        if (addLocalNetCap) {
            request.addCapability(NET_CAPABILITY_LOCAL_NETWORK)
        }
        cm.registerNetworkCallback(request.build(), allNetworksCb)
        val ncTemplate = NetworkCapabilities.Builder().run {
            addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            addCapability(NET_CAPABILITY_LOCAL_NETWORK)
        }.build()
        val localAgent = LocalAgentWrapper(nc = ncTemplate)
        // Before V, local agents are not visible to callbacks and connecting has to wait for idle.
        localAgent.connect(if (sdkLevel >= VERSION_V) WAIT_FOR_CALLBACK else WAIT_FOR_IDLE)
        if (sdkLevel >= VERSION_V) {
            netdInOrder.verify(netd).networkCreate(
                    makeNativeNetworkConfigLocal(localAgent.network.netId, INetd.PERMISSION_NONE))
        } else {
            netdInOrder.verify(netd, never()).networkCreate(any())
        }
        if (addLocalNetCap) {
            assertEquals(localAgent.network, allNetworksCb.expect<Available>().network)
        } else {
            allNetworksCb.assertNoCallback(TIMEOUT_MS)
        }
        cm.unregisterNetworkCallback(allNetworksCb)
        localAgent.disconnect()
        if (sdkLevel >= VERSION_V) {
            netdInOrder.verify(netd, timeout(TIMEOUT_MS)).networkDestroy(localAgent.network.netId)
        } else {
            netdInOrder.verifyNoMoreInteractions()
        }
    }

    @Test
    fun testNoLocalAgentsBeforeV() {
        deps.setBuildSdk(VERSION_T)
        val ncTemplate = NetworkCapabilities.Builder().run {
            addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            addCapability(NET_CAPABILITY_LOCAL_NETWORK)
        }.build()
        val localAgent = LocalAgentWrapper(nc = ncTemplate)
        // Before V, local agents are not visible to callbacks and connecting has to wait for idle.
        localAgent.connect(WAIT_FOR_CALLBACK)
        val net = cm.allNetworks.let {
            assertEquals(1, it.size)
            it[0]
        }
        assertFalse(cm.getNetworkCapabilities(net).hasCapability(NET_CAPABILITY_LOCAL_NETWORK))
    }
}
