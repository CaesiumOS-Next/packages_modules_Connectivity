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
import com.android.testutils.tryTest
import com.google.android.mobly.snippet.Snippet
import com.google.android.mobly.snippet.rpc.Rpc
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.fail

private const val P2P_GROUP_NAME = "DIRECT-MD-p2pTest"
private const val P2P_GROUP_PASSPHRASE = "MultiDevicesP2pTest"
private const val TIMEOUT_MS = 60000L

class Wifip2pMultiDevicesSnippet : Snippet {
    private val context = InstrumentationRegistry.getInstrumentation().getTargetContext()
    private val wifiManager = context.getSystemService(WifiManager::class.java)!!
    private val wifip2pManager = context.getSystemService(WifiP2pManager::class.java)!!
    private val wifip2pChannels = ArrayList<WifiP2pManager.Channel>()
    private val uiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val wifip2pConfig = WifiP2pConfig.Builder()
            .setNetworkName(P2P_GROUP_NAME)
            .setPassphrase(P2P_GROUP_PASSPHRASE)
            .build()

    @Rpc(description = "Check whether the device supports Wi-Fi P2P.")
    fun isP2pSupported() = wifiManager.isP2pSupported()

    @Rpc(description = "Start Wi-Fi P2P")
    fun startWifiP2p(): String {
        // Init wifi p2p and ensure its enabled
        val channel = wifip2pManager.initialize(context, context.mainLooper, null)
        wifip2pChannels.add(channel)

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
        // Return current device name
        return deviceFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun ensureIntentReceived(action: String, predicate: (Intent) -> Boolean) {
        val future = CompletableFuture<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (predicate(intent) == true) {
                    future.complete(true)
                }
            }
        }

        tryTest {
            context.registerReceiver(receiver, IntentFilter(action))
            future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } cleanup {
            context.unregisterReceiver(receiver)
        }
    }

    @Rpc(description = "Create Wi-Fi P2P group")
    fun createWifiP2pGroup() {
        val channel = wifip2pChannels.get(0)
        val createGroupFuture = CompletableFuture<Boolean>()
        wifip2pManager.createGroup(
                channel,
                wifip2pConfig,
                object : WifiP2pManager.ActionListener {
                    override fun onFailure(reason: Int) { }
                    override fun onSuccess() { createGroupFuture.complete(true) }
                }
        )
        createGroupFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

        // Check the group is created.
        ensureIntentReceived(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) {
            val p2pGroup: WifiP2pGroup? =
                    it.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
            return@ensureIntentReceived if (p2pGroup?.networkName == P2P_GROUP_NAME) true else false
        }

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
        val channel = wifip2pChannels.get(0)
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
        // Check the target device is appeared in device list
        ensureIntentReceived(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION) {
            val peers: WifiP2pDeviceList? =
                    it.getParcelableExtra(WifiP2pManager.EXTRA_P2P_DEVICE_LIST)
            for (peer in peers?.deviceList ?: listOf()) {
                if (peer.deviceName == deviceName) {
                    return@ensureIntentReceived true
                }
            }
            return@ensureIntentReceived false
        }

        val channel = wifip2pChannels.get(0)
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
                wifip2pConfig.deviceAddress = device.deviceAddress
                val connectedFuture = CompletableFuture<Boolean>()
                wifip2pManager.connect(
                    channel,
                    wifip2pConfig,
                        object : WifiP2pManager.ActionListener {
                            override fun onFailure(reason: Int) {}
                            override fun onSuccess() { connectedFuture.complete(true) }
                        }
                )
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
                        .resourceIdMatches("android:id/button1\$")
        )
        if (acceptButton.waitForExists(TIMEOUT_MS) == false) {
            fail("No invitation dialog")
        }
        acceptButton.click()
    }

    @Rpc(description = "Ensure Wi-Fi P2P device connected")
    fun ensureDeviceConnected(deviceName: String) {
        // Check the target device is connected
        ensureIntentReceived(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION) {
            val peers: WifiP2pDeviceList? =
                    it.getParcelableExtra(WifiP2pManager.EXTRA_P2P_DEVICE_LIST)
            for (peer in peers?.deviceList ?: listOf()) {
                if (peer.deviceName == deviceName && peer.status == WifiP2pDevice.CONNECTED) {
                    return@ensureIntentReceived true
                }
            }
            return@ensureIntentReceived false
        }
    }

    @Rpc(description = "Stop Wi-Fi P2P")
    fun stopWifiP2p() {
        for (channel in wifip2pChannels) {
            wifip2pManager.cancelConnect(channel, null)
            wifip2pManager.removeGroup(channel, null)
        }
        wifip2pChannels.clear()
    }
}
