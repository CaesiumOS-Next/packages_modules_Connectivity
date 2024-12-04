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

import android.net.InetAddresses
import android.net.IpPrefix
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NetworkAgentConfig
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK
import android.net.NetworkRequest
import android.net.NetworkScore
import android.net.NetworkScore.Builder
import android.net.RouteInfo
import android.os.Build
import android.test.suitebuilder.annotation.SmallTest
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.RecorderCallback
import com.android.testutils.TestableNetworkCallback
import kotlin.test.assertFailsWith
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito

private const val TEST_INTERFACE = "wlan0"
private fun keepConnectedScore() =
        FromS(Builder().setKeepConnectedReason(NetworkScore.KEEP_CONNECTED_FOR_TEST).build())

// The API is only available on Android V+.
@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class CSSkipNativeNetworkCreationTest : CSTest() {
    @Test
    fun testSkipNativeNetworkCreation() {
        val allNetworksCb = TestableNetworkCallback()
        val request = NetworkRequest.Builder()
        request.addCapability(NET_CAPABILITY_LOCAL_NETWORK)
        cm.registerNetworkCallback(request.build(), allNetworksCb)
        val ncTemplate = NetworkCapabilities.Builder().run {
            addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            addCapability(NET_CAPABILITY_LOCAL_NETWORK)
        }.build()
        val skipNetdNac = NetworkAgentConfig.Builder().setSkipNativeNetworkCreation(true).build()
        val localAgent = Agent(nc = ncTemplate, score = keepConnectedScore(), nac = skipNetdNac)

        localAgent.connect()
        allNetworksCb.expectAvailableCallbacks(localAgent.network, validated = false)
        Mockito.verify(netd, Mockito.never()).networkCreate(any())
        Mockito.verify(netd, Mockito.never()).networkAddInterface(ArgumentMatchers.anyInt(), ArgumentMatchers.anyString())
        Mockito.verify(netd, Mockito.never()).networkAddRouteParcel(ArgumentMatchers.anyInt(), any())
        Mockito.verify(multicastRoutingCoordinatorService, Mockito.never())
                .applyMulticastRoutingConfig(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), any())

        val newLp = LinkProperties().apply {
            setInterfaceName(TEST_INTERFACE)
            addLinkAddress(LinkAddress(InetAddresses.parseNumericAddress("fe80::1"), 32))
            addRoute(RouteInfo(IpPrefix(InetAddresses.parseNumericAddress("::"), 0), null))
        }
        localAgent.sendLinkProperties(newLp)
        allNetworksCb.eventuallyExpect<RecorderCallback.CallbackEntry.LinkPropertiesChanged> {
            it.lp.interfaceName.equals(TEST_INTERFACE)
        }
        // Verify no interaction when updating LinkProperties.
        Mockito.verify(netd, Mockito.never()).networkAddInterface(ArgumentMatchers.anyInt(), ArgumentMatchers.anyString())
        Mockito.verify(netd, Mockito.never()).networkAddRouteParcel(ArgumentMatchers.anyInt(), any())
        Mockito.verify(multicastRoutingCoordinatorService, Mockito.never())
                .applyMulticastRoutingConfig(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), any())

        cm.unregisterNetworkCallback(allNetworksCb)
        localAgent.disconnect()
        // Verify no interaction when disconnects.
        Mockito.verify(netd, Mockito.never()).networkDestroy(anyInt())
    }

    @Test
    fun testSetSkipNativeNetworkCreation_badAgents() {
        // non-Local Agents set the flag should throw.
        assertFailsWith<IllegalArgumentException> {
            Agent(nc = NetworkCapabilities.Builder().build(),
                    nac = NetworkAgentConfig.Builder().setSkipNativeNetworkCreation(true).build())
        }
    }
}
