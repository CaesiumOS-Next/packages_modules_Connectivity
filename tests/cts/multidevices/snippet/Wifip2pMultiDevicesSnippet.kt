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

package com.google.snippet.connectivity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject
import androidx.test.uiautomator.UiSelector
import com.google.android.mobly.snippet.Snippet
import com.google.android.mobly.snippet.rpc.Rpc
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.fail

private const val P2P_GROUP_NAME = "DIRECT-MD-p2pTest"
private const val P2P_GROUP_PASSPHRASE = "MultiDevicesP2pTest"
private const val TIMEOUT_MS = 60000L
private const val LONG_TIMEOUT_MS = 120000L

class Wifip2pMultiDevicesSnippet : Snippet {
    private val context = InstrumentationRegistry.getInstrumentation().getTargetContext()
    private val wifiManager = context.getSystemService(WifiManager::class.java)!!
    private val wifip2pManager = context.getSystemService(WifiP2pManager::class.java)!!
    private val wifiP2pChannels = ArrayList<WifiP2pManager.Channel>()
    private val receiver = WiFiDirectBroadcastReceiver()
    private val uiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Rpc(description = "Check whether the device supports Wi-Fi P2P.")
    fun isP2pSupported() = wifiManager.isP2pSupported()

    internal class WiFiDirectBroadcastReceiver : BroadcastReceiver() {
        val peerChangedFuture = CompletableFuture<Boolean>()
        val groupConnectedFuture = CompletableFuture<Boolean>()
        val deviceConnectedFuture = CompletableFuture<Boolean>()
        var targetDeviceName: String = ""
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            when (action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    val peers: WifiP2pDeviceList? = intent
                            .getParcelableExtra(WifiP2pManager.EXTRA_P2P_DEVICE_LIST)
                    for (peer in peers?.deviceList ?: listOf()) {
                        if (peer.deviceName == targetDeviceName) {
                            peerChangedFuture.complete(true)
                            if (peer.status == WifiP2pDevice.CONNECTED) {
                                deviceConnectedFuture.complete(true)
                            }
                        }
                    }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    // Respond to new connection or disconnections
                    val p2pGroup: WifiP2pGroup? =
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
                    if (p2pGroup?.networkName == P2P_GROUP_NAME) {
                        groupConnectedFuture.complete(true)
                    }
                }
            }
        }

        fun updateTargetDeviceName(deviceName: String) { targetDeviceName = deviceName }

        fun ensureDeviceFound() = peerChangedFuture.get(LONG_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        fun ensureGroupConnected() = groupConnectedFuture.get(
            LONG_TIMEOUT_MS,
            TimeUnit.MILLISECONDS
        )

        fun ensureDeviceConnected() = deviceConnectedFuture.get(
            LONG_TIMEOUT_MS,
            TimeUnit.MILLISECONDS
        )
    }

    @Rpc(description = "Start Wi-Fi P2P")
    fun startWifiP2p(): String {
        // Add wifi p2p intent filter
        val filer = IntentFilter()
        filer.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        filer.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        filer.setPriority(999)
        context.registerReceiver(receiver, filer)

        // Init wifi p2p and ensure its enabled
        val channel = wifip2pManager.initialize(context, context.mainLooper, null)
        val p2pStateEnabledFuture = CompletableFuture<Boolean>()
        wifip2pManager.requestP2pState(channel, object : WifiP2pManager.P2pStateListener {
            override fun onP2pStateAvailable(state: Int) {
                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    p2pStateEnabledFuture.complete(true)
                }
            }
        })
        p2pStateEnabledFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

        // Retrieve current device info
        val deviceFuture = CompletableFuture<String>()
        wifip2pManager.requestDeviceInfo(channel, object : WifiP2pManager.DeviceInfoListener {
            override fun onDeviceInfoAvailable(wifiP2pDevice: WifiP2pDevice?) {
                if (wifiP2pDevice != null) {
                    deviceFuture.complete(wifiP2pDevice.deviceName)
                }
            }
        })
        val deviceName = deviceFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

        // Return current device name
        wifiP2pChannels.add(channel)
        return deviceName
    }

    @Rpc(description = "Create Wi-Fi P2P group")
    fun creteWifiP2pGroup() {
        val channel = wifiP2pChannels.get(0)
        val configBuilder = WifiP2pConfig.Builder()
        configBuilder.setNetworkName(P2P_GROUP_NAME)
        configBuilder.setPassphrase(P2P_GROUP_PASSPHRASE)
        val createGroupFuture = CompletableFuture<Boolean>()
        wifip2pManager.createGroup(
                channel,
                configBuilder.build(),
                object : WifiP2pManager.ActionListener {
                    override fun onFailure(reason: Int) { }
                    override fun onSuccess() { createGroupFuture.complete(true) }
                }
        )
        createGroupFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        receiver.ensureGroupConnected()

        val groupAvailableFuture = CompletableFuture<Boolean>()
        wifip2pManager.requestGroupInfo(channel, object : WifiP2pManager.GroupInfoListener {
            override fun onGroupInfoAvailable(group: WifiP2pGroup) {
                if (group.isGroupOwner()) {
                    groupAvailableFuture.complete(true)
                }
            }
        })
        groupAvailableFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    @Rpc(description = "Discover Wi-Fi P2P peers")
    fun discoverWifiP2pPeers(deviceName: String) {
        receiver.updateTargetDeviceName(deviceName)
        val channel = wifiP2pChannels.get(0)
        wifip2pManager.discoverPeers(channel, null)

        val p2pDiscoveryStartedFuture = CompletableFuture<Boolean>()
        wifip2pManager.requestDiscoveryState(
                channel,
                object : WifiP2pManager.DiscoveryStateListener {
                    override fun onDiscoveryStateAvailable(state: Int) {
                        if (state == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED) {
                            p2pDiscoveryStartedFuture.complete(true)
                        }
                    }
                }
        )
        p2pDiscoveryStartedFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    @Rpc(description = "Connect to a Wi-Fi P2P device")
    fun connectToWifiP2pDevice(deviceName: String) {
        val channel = wifiP2pChannels.get(0)
        receiver.ensureDeviceFound()

        val peersFuture = CompletableFuture<WifiP2pDeviceList>()
        wifip2pManager.requestPeers(channel, object : WifiP2pManager.PeerListListener {
            override fun onPeersAvailable(peers: WifiP2pDeviceList?) {
                if (peers != null) {
                    peersFuture.complete(peers)
                }
            }
        })
        val peers = peersFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        for (device in peers.deviceList) {
            if (device.deviceName == deviceName) {
                val configBuilder = WifiP2pConfig.Builder()
                configBuilder.setNetworkName(P2P_GROUP_NAME)
                configBuilder.setPassphrase(P2P_GROUP_PASSPHRASE)
                val config = configBuilder.build()
                config.deviceAddress = device.deviceAddress
                val connectedFuture = CompletableFuture<Boolean>()
                wifip2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
                    override fun onFailure(reason: Int) {}
                    override fun onSuccess() { connectedFuture.complete(true) }
                })
                connectedFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    @Rpc(description = "Accept P2P group invitation from device")
    fun acceptGroupInvitation() {
        uiDevice.waitForIdle()
        val acceptButton = UiObject(
                UiSelector().className("android.widget.Button")
                        .clickable(true)
                        .textMatches("A[Cc][Cc][Ee][Pp][Tt]")
        )
        if (acceptButton.waitForExists(TIMEOUT_MS) == false) {
            fail("No invitation dialog")
        }
        acceptButton.click()
    }

    @Rpc(description = "Ensure Wi-Fi P2P device connected")
    fun ensureDeviceConnected() {
        receiver.ensureDeviceConnected()
    }

    @Rpc(description = "Stop Wi-Fi P2P")
    fun stopWifiP2p() {
        for (channel in wifiP2pChannels) {
            wifip2pManager.cancelConnect(channel, null)
            wifip2pManager.removeGroup(channel, null)
        }
        wifiP2pChannels.clear()
        context.unregisterReceiver(receiver)
    }
}
