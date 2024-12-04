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

import android.content.pm.PackageManager.FEATURE_LEANBACK
import android.net.INetd
import android.net.InetAddresses
import android.net.IpPrefix
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.LocalNetworkConfig
import android.net.NativeNetworkConfig
import android.net.NativeNetworkType
import android.net.NetworkAgentConfig
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK
import android.net.NetworkRequest
import android.net.NetworkScore
import android.net.NetworkScore.KEEP_CONNECTED_FOR_TEST
import android.net.RouteInfo
import android.net.VpnManager
import android.os.Build
import androidx.test.filters.SmallTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.RecorderCallback.CallbackEntry.Available
import com.android.testutils.RecorderCallback.CallbackEntry.LinkPropertiesChanged
import com.android.testutils.TestableNetworkCallback
import kotlin.jvm.JvmField
import kotlin.test.assertFailsWith
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify

private const val TIMEOUT_MS = 2_000L
private const val NO_CALLBACK_TIMEOUT_MS = 200L
private const val TEST_INTERFACE = "wlan0"

private fun keepConnectedScore() =
        FromS(NetworkScore.Builder().setKeepConnectedReason(KEEP_CONNECTED_FOR_TEST).build())

private fun defaultLnc() = FromS(LocalNetworkConfig.Builder().build())

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@IgnoreUpTo(Build.VERSION_CODES.R)
class CSLocalAgentCreationTests : CSTest() {
    @Parameterized.Parameter(0) lateinit var params: TestParams

    @JvmField
    @Rule
    val ignoreRule = DevSdkIgnoreRule()

    data class TestParams(
            val sdkLevel: Int,
            val isTv: Boolean = false,
            val addLocalNetCapToRequest: Boolean = true)

    companion object {
        @JvmStatic
        @Parameterized.Parameters
        fun arguments() = listOf(
                TestParams(VERSION_V, isTv = false, addLocalNetCapToRequest = true),
                TestParams(VERSION_V, isTv = false, addLocalNetCapToRequest = false),
                TestParams(VERSION_V, isTv = true, addLocalNetCapToRequest = true),
                TestParams(VERSION_V, isTv = true, addLocalNetCapToRequest = false),
                TestParams(VERSION_U, isTv = false, addLocalNetCapToRequest = true),
                TestParams(VERSION_U, isTv = false, addLocalNetCapToRequest = false),
                TestParams(VERSION_U, isTv = true, addLocalNetCapToRequest = true),
                TestParams(VERSION_U, isTv = true, addLocalNetCapToRequest = false),
                TestParams(VERSION_T, isTv = false, addLocalNetCapToRequest = false),
                TestParams(VERSION_T, isTv = true, addLocalNetCapToRequest = false),
        )
    }

    private fun makeNativeNetworkConfigLocal(netId: Int, permission: Int) =
            NativeNetworkConfig(netId, NativeNetworkType.PHYSICAL_LOCAL, permission,
                    false /* secure */, VpnManager.TYPE_VPN_NONE, false /* excludeLocalRoutes */)

    @Test
    fun testLocalAgents() {
        val netdInOrder = inOrder(netd)
        deps.setBuildSdk(params.sdkLevel)
        doReturn(params.isTv).`when`(packageManager).hasSystemFeature(FEATURE_LEANBACK)
        val allNetworksCb = TestableNetworkCallback()
        val request = NetworkRequest.Builder()
        if (params.addLocalNetCapToRequest) {
            request.addCapability(NET_CAPABILITY_LOCAL_NETWORK)
        }
        cm.registerNetworkCallback(request.build(), allNetworksCb)
        val ncTemplate = NetworkCapabilities.Builder().run {
            addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            addCapability(NET_CAPABILITY_LOCAL_NETWORK)
        }.build()
        val localAgent = if (params.sdkLevel >= VERSION_V
                || params.sdkLevel == VERSION_U && params.isTv) {
            Agent(nc = ncTemplate, score = keepConnectedScore(), lnc = defaultLnc())
        } else {
            assertFailsWith<IllegalArgumentException> { Agent(nc = ncTemplate, lnc = defaultLnc()) }
            netdInOrder.verify(netd, never()).networkCreate(any())
            return
        }
        localAgent.connect()
        netdInOrder.verify(netd).networkCreate(
                makeNativeNetworkConfigLocal(localAgent.network.netId, INetd.PERMISSION_NONE))
        if (params.addLocalNetCapToRequest) {
            assertEquals(localAgent.network, allNetworksCb.expect<Available>().network)
        } else {
            allNetworksCb.assertNoCallback(NO_CALLBACK_TIMEOUT_MS)
        }
        cm.unregisterNetworkCallback(allNetworksCb)
        localAgent.disconnect()
        netdInOrder.verify(netd, timeout(TIMEOUT_MS)).networkDestroy(localAgent.network.netId)
    }

    @Test
    fun testSkipNativeNetworkCreation() {
        deps.setBuildSdk(params.sdkLevel)
        val allNetworksCb = TestableNetworkCallback()
        val request = NetworkRequest.Builder()
        request.addCapability(NET_CAPABILITY_LOCAL_NETWORK)
        cm.registerNetworkCallback(request.build(), allNetworksCb)
        val ncTemplate = NetworkCapabilities.Builder().run {
            addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            addCapability(NET_CAPABILITY_LOCAL_NETWORK)
        }.build()
        val skipNetdNac = NetworkAgentConfig.Builder().setSkipNativeNetworkCreation(true).build()
        val localAgent = if (params.sdkLevel >= VERSION_V) {
            Agent(nc = ncTemplate, score = keepConnectedScore(), nac = skipNetdNac)
        } else {
            assertFailsWith<IllegalArgumentException> { Agent(nc = ncTemplate, nac = skipNetdNac) }
            verify(netd, never()).networkCreate(any())
            return
        }

        localAgent.connect()
        allNetworksCb.expectAvailableCallbacks(localAgent.network, validated = false)
        verify(netd, never()).networkCreate(any())
        verify(netd, never()).networkAddInterface(anyInt(), anyString())
        verify(netd, never()).networkAddRouteParcel(anyInt(), any())
        verify(multicastRoutingCoordinatorService, never())
                .applyMulticastRoutingConfig(anyString(), anyString(), any())

        val newLp = LinkProperties().apply {
            setInterfaceName(TEST_INTERFACE)
            addLinkAddress(LinkAddress(InetAddresses.parseNumericAddress("fe80::1"), 32))
            addRoute(RouteInfo(IpPrefix(InetAddresses.parseNumericAddress("::"), 0), null))
        }
        localAgent.sendLinkProperties(newLp)
        allNetworksCb.eventuallyExpect<LinkPropertiesChanged> {
            it.lp.interfaceName.equals(TEST_INTERFACE)
        }
        // Verify no interaction when updating LinkProperties.
        verify(netd, never()).networkAddInterface(anyInt(), anyString())
        verify(netd, never()).networkAddRouteParcel(anyInt(), any())
        verify(multicastRoutingCoordinatorService, never())
                .applyMulticastRoutingConfig(anyString(), anyString(), any())

        cm.unregisterNetworkCallback(allNetworksCb)
        localAgent.disconnect()
        // Verify no interaction when disconnects.
        verify(netd, never()).networkDestroy(any())
    }

    @Test
    fun testBadAgents() {
        assertFailsWith<IllegalArgumentException> {
            Agent(nc = NetworkCapabilities.Builder()
                    .addCapability(NET_CAPABILITY_LOCAL_NETWORK)
                    .build(),
                    lnc = null)
        }
        assertFailsWith<IllegalArgumentException> {
            Agent(nc = NetworkCapabilities.Builder().build(), lnc = defaultLnc())
        }
    }

    @Test
    // NetworkAgentConfig is not affected by the mocked SDK level,
    // and the API is only available on Android V+.
    @IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun testSetSkipNativeNetworkCreation_badAgents() {
        // non-Local Agents set the flag should throw.
        assertFailsWith<IllegalArgumentException> {
            Agent(nc = NetworkCapabilities.Builder().build(),
                    nac = NetworkAgentConfig.Builder().setSkipNativeNetworkCreation(true).build())
        }
    }
}
