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
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_IMS
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.os.Build
import android.os.ConditionVariable
import com.android.net.module.util.BaseNetdUnsolicitedEventListener
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.RecorderCallback.CallbackEntry.Lost
import com.android.testutils.TestableNetworkCallback
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

internal const val DATA_CELL_IFNAME = "rmnet_data"
internal const val IMS_CELL_IFNAME = "rmnet_ims"
internal const val WIFI_IFNAME = "wlan0"
internal const val TIMESTAMP = 1234L
internal const val NETWORK_ACTIVITY_NO_UID = -1
internal const val PACKAGE_UID = 123
internal const val CALLBACK_TIMEOUT_MS = 250L

@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
class CSNetworkActivityTest : CSTest() {

    private fun getRegisteredNetdUnsolicitedEventListener(): BaseNetdUnsolicitedEventListener {
        val captor = ArgumentCaptor.forClass(BaseNetdUnsolicitedEventListener::class.java)
        verify(netd).registerUnsolicitedEventListener(captor.capture())
        return captor.value
    }

    @Test
    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun testInterfaceClassActivityChanged_NonDefaultNetwork() {
        val netdUnsolicitedEventListener = getRegisteredNetdUnsolicitedEventListener()

        val cellNc = NetworkCapabilities.Builder()
                .addTransportType(TRANSPORT_CELLULAR)
                .addCapability(NET_CAPABILITY_INTERNET)
                .addCapability(NET_CAPABILITY_NOT_SUSPENDED)
                .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
                .build()
        val cellLp = LinkProperties().apply {
            interfaceName = DATA_CELL_IFNAME
        }
        val cellNr = NetworkRequest.Builder()
                .clearCapabilities()
                .addTransportType(TRANSPORT_CELLULAR)
                .addCapability(NET_CAPABILITY_INTERNET)
                .build()
        val cellCb = TestableNetworkCallback()
        // Request cell network to keep cell network up
        cm.requestNetwork(cellNr, cellCb)

        val wifiNc = NetworkCapabilities.Builder()
                .addTransportType(TRANSPORT_WIFI)
                .addCapability(NET_CAPABILITY_INTERNET)
                .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
                .build()
        val wifiLp = LinkProperties().apply {
            interfaceName = WIFI_IFNAME
        }

        val defaultCb = TestableNetworkCallback()
        cm.registerDefaultNetworkCallback(defaultCb)

        // Connect Cellular network
        val cellAgent = Agent(nc = cellNc, lp = cellLp)
        cellAgent.connect()
        defaultCb.expectAvailableCallbacks(cellAgent.network, validated = false)

        // Connect Wi-Fi network, Wi-Fi network should be the default network.
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        defaultCb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        val onNetworkActiveCv = ConditionVariable()
        val listener = ConnectivityManager.OnNetworkActiveListener { onNetworkActiveCv::open }
        cm.addDefaultNetworkActiveListener(listener)

        // Cellular network (non default network) goes to inactive state.
        netdUnsolicitedEventListener.onInterfaceClassActivityChanged(false /* isActive */,
                cellAgent.network.netId, TIMESTAMP, NETWORK_ACTIVITY_NO_UID)
        // Non-default network activity change does not change default network activity
        assertFalse(onNetworkActiveCv.block(CALLBACK_TIMEOUT_MS))
        context.expectNoDataActivityBroadcast(0 /* timeoutMs */)
        assertTrue(cm.isDefaultNetworkActive)

        // Cellular network (non default network) goes to active state.
        netdUnsolicitedEventListener.onInterfaceClassActivityChanged(true /* isActive */,
                cellAgent.network.netId, TIMESTAMP, PACKAGE_UID)
        // Non-default network activity change does not change default network activity
        assertFalse(onNetworkActiveCv.block(CALLBACK_TIMEOUT_MS))
        context.expectNoDataActivityBroadcast(0 /* timeoutMs */)
        assertTrue(cm.isDefaultNetworkActive)

        cm.unregisterNetworkCallback(cellCb)
        cm.unregisterNetworkCallback(defaultCb)
        cm.removeDefaultNetworkActiveListener(listener)
    }

    @Test
    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun testDataActivityTracking_MultiCellNetwork() {
        val dataNetworkNc = NetworkCapabilities.Builder()
                .addTransportType(TRANSPORT_CELLULAR)
                .addCapability(NET_CAPABILITY_INTERNET)
                .addCapability(NET_CAPABILITY_NOT_SUSPENDED)
                .build()
        val dataNetworkNr = NetworkRequest.Builder()
                .clearCapabilities()
                .addTransportType(TRANSPORT_CELLULAR)
                .addCapability(NET_CAPABILITY_INTERNET)
                .build()
        val dataNetworkLp = LinkProperties().apply {
            interfaceName = DATA_CELL_IFNAME
        }

        val imsNetworkNc = NetworkCapabilities.Builder()
                .addTransportType(TRANSPORT_CELLULAR)
                .addCapability(NET_CAPABILITY_IMS)
                .addCapability(NET_CAPABILITY_NOT_SUSPENDED)
                .build()
        val imsNetworkNr = NetworkRequest.Builder()
                .clearCapabilities()
                .addTransportType(TRANSPORT_CELLULAR)
                .addCapability(NET_CAPABILITY_IMS)
                .build()
        val imsNetworkLp = LinkProperties().apply {
            interfaceName = IMS_CELL_IFNAME
        }

        val dataNetworkCb = TestableNetworkCallback()
        val imsNetworkCb = TestableNetworkCallback()

        cm.requestNetwork(dataNetworkNr, dataNetworkCb)
        cm.requestNetwork(imsNetworkNr, imsNetworkCb)

        val dataNetworkAgent = Agent(nc = dataNetworkNc, lp = dataNetworkLp)
        val imsNetworkAgent = Agent(nc = imsNetworkNc, lp = imsNetworkLp)
        val dataNetworkNetId = Integer.toString(dataNetworkAgent.network.netId)
        val imsNetworkNetId = Integer.toString(imsNetworkAgent.network.netId)

        dataNetworkAgent.connect()
        dataNetworkCb.expectAvailableCallbacks(dataNetworkAgent.network, validated = false)

        imsNetworkAgent.connect()
        imsNetworkCb.expectAvailableCallbacks(imsNetworkAgent.network, validated = false)

        // Both cell networks have idleTimers
        verify(netd).idletimerAddInterface(eq(DATA_CELL_IFNAME), anyInt(), eq(dataNetworkNetId))
        verify(netd).idletimerAddInterface(eq(IMS_CELL_IFNAME), anyInt(), eq(imsNetworkNetId))
        verify(netd, never()).idletimerRemoveInterface(eq(DATA_CELL_IFNAME), anyInt(),
                eq(dataNetworkNetId))
        verify(netd, never()).idletimerRemoveInterface(eq(IMS_CELL_IFNAME), anyInt(),
                eq(imsNetworkNetId))

        dataNetworkAgent.disconnect()
        dataNetworkCb.expect<Lost>(dataNetworkAgent.network)
        waitForIdle()
        verify(netd).idletimerRemoveInterface(eq(DATA_CELL_IFNAME), anyInt(), eq(dataNetworkNetId))

        imsNetworkAgent.disconnect()
        imsNetworkCb.expect<Lost>(imsNetworkAgent.network)
        waitForIdle()
        verify(netd).idletimerRemoveInterface(eq(IMS_CELL_IFNAME), anyInt(), eq(imsNetworkNetId))

        cm.unregisterNetworkCallback(dataNetworkCb)
        cm.unregisterNetworkCallback(imsNetworkCb)
    }
}
