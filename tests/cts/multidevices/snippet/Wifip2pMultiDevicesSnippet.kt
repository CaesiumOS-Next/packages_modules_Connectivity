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

import android.Manifest.permission.MANAGE_WIFI_NETWORK_SELECTION
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.MacAddress
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import androidx.test.platform.app.InstrumentationRegistry
import com.android.testutils.runAsShell
import com.android.testutils.tryTest
import com.google.android.mobly.snippet.Snippet
import com.google.android.mobly.snippet.rpc.Rpc
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private const val TIMEOUT_MS = 60000L
private const val P2P_GROUP_NAME = "DIRECT-MD-p2pTest"
private const val P2P_GROUP_PASSPHRASE = "MultiDevicesP2pTest"

class Wifip2pMultiDevicesSnippet : Snippet {
    private val context = InstrumentationRegistry.getInstrumentation().getTargetContext()
    private val wifiManager = context.getSystemService(WifiManager::class.java)!!
    private val wifip2pManager = context.getSystemService(WifiP2pManager::class.java)!!
    private val wifip2pChannels = ArrayList<WifiP2pManager.Channel>()
    private val devices = ArrayList<WifiP2pDevice>()
    private val wifip2pConfig = WifiP2pConfig.Builder()
            .setNetworkName(P2P_GROUP_NAME)
            .setPassphrase(P2P_GROUP_PASSPHRASE)
            .build()

    @Rpc(description = "Check whether the device supports Wi-Fi P2P.")
    fun isP2pSupported() = wifiManager.isP2pSupported()

    @Rpc(description = "Start Wi-Fi P2P")
    fun startWifiP2p() {
        // Initialize Wi-Fi P2P
        val channel = wifip2pManager.initialize(context, context.mainLooper, null)
        wifip2pChannels.add(channel)

        // Ensure the Wi-Fi P2P channel is available
        val p2pStateEnabledFuture = CompletableFuture<Boolean>()
        wifip2pManager.requestP2pState(channel, object : WifiP2pManager.P2pStateListener {
            override fun onP2pStateAvailable(state: Int) {
                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    p2pStateEnabledFuture.complete(true)
                }
            }
        })
        p2pStateEnabledFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    @Rpc(description = "Stop Wi-Fi P2P")
    fun stopWifiP2p() {
        for (channel in wifip2pChannels) {
            wifip2pManager.cancelConnect(channel, null)
            wifip2pManager.removeGroup(channel, null)
        }
        wifip2pChannels.clear()
    }

    @Rpc(description = "Get the current device name")
    fun getDeviceName(): String {
        // Retrieve current device info
        val channel = wifip2pChannels.get(0)
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

    @Rpc(description = "Wait for a p2p connection changed intent and check the group")
    fun waitForP2pConnectionChanged(ignoreGroupCheck: Boolean) {
        ensureIntentReceived(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) {
            val p2pGroup: WifiP2pGroup? =
                    it.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
            val groupMatched = p2pGroup?.networkName == P2P_GROUP_NAME
            return@ensureIntentReceived if (ignoreGroupCheck || groupMatched) true else false
        }
    }

    @Rpc(description = "Create a Wi-Fi P2P group")
    fun createGroup() {
        // Crate a Wi-Fi P2P group
        val channel = wifip2pChannels.get(0)
        val createGroupFuture = CompletableFuture<Boolean>()
        wifip2pManager.createGroup(
                channel,
                wifip2pConfig,
                object : WifiP2pManager.ActionListener {
                    override fun onFailure(reason: Int) = Unit
                    override fun onSuccess() { createGroupFuture.complete(true) }
                }
        )
        createGroupFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

        // Ensure the Wi-Fi P2P group is created.
        waitForP2pConnectionChanged(false)
    }

    @Rpc(description = "Discover Wi-Fi P2P peers")
    fun discoverPeers() {
        // Start discovery Wi-Fi P2P peers
        val channel = wifip2pChannels.get(0)
        wifip2pManager.discoverPeers(channel, null)

        // Ensure the discovery is started
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

    @Rpc(description = "Ensure the target Wi-Fi P2P device is discovered")
    fun ensureDeviceDiscovered(deviceName: String) {
        // Ensure the target device is appeared in device list
        ensureIntentReceived(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION) {
            val peers: WifiP2pDeviceList? =
                    it.getParcelableExtra(WifiP2pManager.EXTRA_P2P_DEVICE_LIST)
            for (peer in peers?.deviceList ?: listOf()) {
                if (peer.deviceName == deviceName) {
                    devices.add(peer)
                    return@ensureIntentReceived true
                }
            }
            return@ensureIntentReceived false
        }
    }

    @Rpc(description = "Invite a Wi-Fi P2P device to the group")
    fun inviteDeviceToGroup() {
        // Connect to the device to send invitation
        wifip2pConfig.deviceAddress = devices.get(0).deviceAddress
        val connectedFuture = CompletableFuture<Boolean>()
        wifip2pManager.connect(
                wifip2pChannels.get(0),
                wifip2pConfig,
                object : WifiP2pManager.ActionListener {
                    override fun onFailure(reason: Int) {}
                    override fun onSuccess() {
                        connectedFuture.complete(true)
                    }
                }
        )
        connectedFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    @Rpc(description = "Accept P2P group invitation from device")
    fun acceptGroupInvitation() {
        // Accept the Wi-Fi P2P group invitation
        val channel = wifip2pChannels.get(0)
        val peer = MacAddress.fromString(devices.get(0).deviceAddress)
        runAsShell(MANAGE_WIFI_NETWORK_SELECTION) {
            val attachedFuture = CompletableFuture<Boolean>()
            wifip2pManager.addExternalApprover(
                    channel,
                    peer,
                    object : WifiP2pManager.ExternalApproverRequestListener {
                        override fun onAttached(deviceAddress: MacAddress) {
                            attachedFuture.complete(true)
                        }
                        override fun onDetached(deviceAddress: MacAddress, reason: Int) = Unit
                        override fun onConnectionRequested(
                                requestType: Int,
                                config: WifiP2pConfig,
                                device: WifiP2pDevice
                        ) = Unit
                        override fun onPinGenerated(deviceAddress: MacAddress, pin: String) = Unit
                    }
            )
            attachedFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

            val acceptFuture = CompletableFuture<Boolean>()
            wifip2pManager.setConnectionRequestResult(
                    channel,
                    peer,
                    WifiP2pManager.CONNECTION_REQUEST_ACCEPT,
                    object : WifiP2pManager.ActionListener {
                        override fun onFailure(reason: Int) = Unit
                        override fun onSuccess() {
                            acceptFuture.complete(true)
                        }
                    }
            )
            acceptFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    @Rpc(description = "Wait for connection request from the peer and accept joining")
    fun waitForPeerConnectionRequestAndAcceptJoining() {
        // Wait for connection request from the peer and accept joining
        val channel = wifip2pChannels.get(0)
        val peer = MacAddress.fromString(devices.get(0).deviceAddress)
        runAsShell(MANAGE_WIFI_NETWORK_SELECTION) {
            val connectionRequestFuture = CompletableFuture<Boolean>()
            wifip2pManager.addExternalApprover(
                    channel,
                    peer,
                    object : WifiP2pManager.ExternalApproverRequestListener {
                        override fun onAttached(deviceAddress: MacAddress) = Unit
                        override fun onDetached(deviceAddress: MacAddress, reason: Int) = Unit
                        override fun onConnectionRequested(
                                requestType: Int,
                                config: WifiP2pConfig,
                                device: WifiP2pDevice
                        ) {
                            connectionRequestFuture.complete(true)
                        }
                        override fun onPinGenerated(deviceAddress: MacAddress, pin: String) = Unit
                    }
            )
            connectionRequestFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

            val acceptFuture = CompletableFuture<Boolean>()
            wifip2pManager.setConnectionRequestResult(
                    channel,
                    peer,
                    WifiP2pManager.CONNECTION_REQUEST_ACCEPT,
                    object : WifiP2pManager.ActionListener {
                        override fun onFailure(reason: Int) = Unit
                        override fun onSuccess() {
                            acceptFuture.complete(true)
                        }
                    }
            )
            acceptFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    @Rpc(description = "Ensure the target device is connected")
    fun ensureDeviceConnected(deviceName: String) {
        // Retrieve peers and ensure the target device is connected
        val channel = wifip2pChannels.get(0)
        val connectedFuture = CompletableFuture<Boolean>()
        wifip2pManager.requestPeers(channel, object : WifiP2pManager.PeerListListener {
            override fun onPeersAvailable(peers: WifiP2pDeviceList?) {
                for (peer in peers?.deviceList ?: listOf()) {
                    if (peer.deviceName == deviceName && peer.status == WifiP2pDevice.CONNECTED) {
                        connectedFuture.complete(true)
                    }
                }
            }
        })
        connectedFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }
}
