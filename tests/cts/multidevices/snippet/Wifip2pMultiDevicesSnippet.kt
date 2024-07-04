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
import com.android.net.module.util.ArrayTrackRecord
import com.android.testutils.runAsShell
import com.google.android.mobly.snippet.Snippet
import com.google.android.mobly.snippet.rpc.Rpc
import com.google.snippet.connectivity.Wifip2pMultiDevicesSnippet.IntentReceivedRecord.IntentReceivedEvent.ConnectionChanged
import com.google.snippet.connectivity.Wifip2pMultiDevicesSnippet.IntentReceivedRecord.IntentReceivedEvent.PeersChanged
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull
import kotlin.test.fail

private const val TIMEOUT_MS = 60000L

class Wifip2pMultiDevicesSnippet : Snippet {
    private val context by lazy { InstrumentationRegistry.getInstrumentation().getTargetContext() }
    private val wifiManager by lazy {
        context.getSystemService(WifiManager::class.java)
                ?: fail("Could not get WifiManager service")
    }
    private val wifip2pManager by lazy {
        context.getSystemService(WifiP2pManager::class.java)
                ?: fail("Could not get WifiP2pManager service")
    }
    private lateinit var wifip2pChannel: WifiP2pManager.Channel
    private val intentReceivedRecord = IntentReceivedRecord()

    private class IntentReceivedRecord : BroadcastReceiver() {
        val history = ArrayTrackRecord<IntentReceivedEvent>().newReadHead()

        sealed class IntentReceivedEvent {
            abstract val intent: Intent
            data class ConnectionChanged(override val intent: Intent) : IntentReceivedEvent()
            data class PeersChanged(override val intent: Intent) : IntentReceivedEvent()
        }

        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) {
                history.add(ConnectionChanged(intent))
            } else {
                history.add(PeersChanged(intent))
            }
        }

        inline fun <reified T : IntentReceivedEvent> eventullyExpectedIntent(
                timeoutMs: Long = TIMEOUT_MS,
                crossinline predicate: (T) -> Boolean = { true }
        ): T = history.poll(timeoutMs) { it is T && predicate(it) }.also {
            assertNotNull(it, "Intent not received within ${timeoutMs}ms.")
        } as T
    }

    @Rpc(description = "Check whether the device supports Wi-Fi P2P.")
    fun isP2pSupported() = wifiManager.isP2pSupported()

    @Rpc(description = "Start Wi-Fi P2P")
    fun startWifiP2p() {
        // Initialize Wi-Fi P2P
        wifip2pChannel = wifip2pManager.initialize(context, context.mainLooper, null)

        // Ensure the Wi-Fi P2P channel is available
        val p2pStateEnabledFuture = CompletableFuture<Boolean>()
        wifip2pManager.requestP2pState(wifip2pChannel) { state ->
            if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                p2pStateEnabledFuture.complete(true)
            }
        }
        p2pStateEnabledFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        context.registerReceiver(intentReceivedRecord, filter)
    }

    @Rpc(description = "Stop Wi-Fi P2P")
    fun stopWifiP2p() {
        if (this::wifip2pChannel.isInitialized) {
            wifip2pManager.cancelConnect(wifip2pChannel, null)
            wifip2pManager.removeGroup(wifip2pChannel, null)
        }
        context.unregisterReceiver(intentReceivedRecord)
    }

    @Rpc(description = "Get the current device name")
    fun getDeviceName(): String {
        // Retrieve current device info
        val deviceFuture = CompletableFuture<String>()
        wifip2pManager.requestDeviceInfo(wifip2pChannel) { wifiP2pDevice ->
            if (wifiP2pDevice != null) {
                deviceFuture.complete(wifiP2pDevice.deviceName)
            }
        }
        // Return current device name
        return deviceFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    @Rpc(description = "Wait for a p2p connection changed intent and check the group")
    @Suppress("DEPRECATION")
    fun waitForP2pConnectionChanged(ignoreGroupCheck: Boolean, groupName: String) {
        intentReceivedRecord.eventullyExpectedIntent<ConnectionChanged>() {
            val p2pGroup: WifiP2pGroup? =
                    it.intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
            val groupMatched = p2pGroup?.networkName == groupName
            return@eventullyExpectedIntent ignoreGroupCheck || groupMatched
        }
    }

    @Rpc(description = "Create a Wi-Fi P2P group")
    fun createGroup(groupName: String, groupPassphrase: String) {
        // Create a Wi-Fi P2P group
        val wifip2pConfig = WifiP2pConfig.Builder()
                .setNetworkName(groupName)
                .setPassphrase(groupPassphrase)
                .build()
        val createGroupFuture = CompletableFuture<Boolean>()
        wifip2pManager.createGroup(
                wifip2pChannel,
                wifip2pConfig,
                object : WifiP2pManager.ActionListener {
                    override fun onFailure(reason: Int) = Unit
                    override fun onSuccess() { createGroupFuture.complete(true) }
                }
        )

        // Ensure the Wi-Fi P2P group is created.
        waitForP2pConnectionChanged(false, groupName)
    }

    @Rpc(description = "Start Wi-Fi P2P peers discovery")
    fun startPeersDiscovery() {
        // Start discovery Wi-Fi P2P peers
        wifip2pManager.discoverPeers(wifip2pChannel, null)

        // Ensure the discovery is started
        val p2pDiscoveryStartedFuture = CompletableFuture<Boolean>()
        wifip2pManager.requestDiscoveryState(wifip2pChannel) { state ->
            if (state == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED) {
                p2pDiscoveryStartedFuture.complete(true)
            }
        }
        p2pDiscoveryStartedFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    fun getDeviceAddress(peersChangedIntent: Intent, deviceName: String): String? {
        val peers: WifiP2pDeviceList? =
                peersChangedIntent.getParcelableExtra(WifiP2pManager.EXTRA_P2P_DEVICE_LIST)
        return peers?.deviceList?.firstOrNull { it.deviceName == deviceName }?.deviceAddress
    }

    /**
     * Ensure the given device has been discovered and returns the associated device addresss for
     * connection.
     *
     * @param deviceName the target device name
     * @return the address of the target device.
     */
    @Rpc(description = "Ensure the target Wi-Fi P2P device is discovered")
    @Suppress("DEPRECATION")
    fun ensureDeviceDiscovered(deviceName: String): String {
        val changedEvent = intentReceivedRecord.eventullyExpectedIntent<PeersChanged>() {
            return@eventullyExpectedIntent getDeviceAddress(it.intent, deviceName) != null
        }
        return getDeviceAddress(changedEvent.intent, deviceName)
                ?: fail("Missing device in filtered intent")
    }

    @Rpc(description = "Invite a Wi-Fi P2P device to the group")
    fun inviteDeviceToGroup(groupName: String, groupPassphrase: String, deviceAddress: String) {
        // Connect to the device to send invitation
        val wifip2pConfig = WifiP2pConfig.Builder()
                .setNetworkName(groupName)
                .setPassphrase(groupPassphrase)
                .setDeviceAddress(MacAddress.fromString(deviceAddress))
                .build()
        val connectedFuture = CompletableFuture<Boolean>()
        wifip2pManager.connect(
                wifip2pChannel,
                wifip2pConfig,
                object : WifiP2pManager.ActionListener {
                    override fun onFailure(reason: Int) = Unit
                    override fun onSuccess() {
                        connectedFuture.complete(true)
                    }
                }
        )
        connectedFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    @Rpc(description = "Accept P2P group invitation from device")
    fun acceptGroupInvitation(deviceAddress: String) {
        // Accept the Wi-Fi P2P group invitation
        val peer = MacAddress.fromString(deviceAddress)
        runAsShell(MANAGE_WIFI_NETWORK_SELECTION) {
            val attachedFuture = CompletableFuture<Boolean>()
            wifip2pManager.addExternalApprover(
                    wifip2pChannel,
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
                    wifip2pChannel,
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
    fun waitForPeerConnectionRequestAndAcceptJoining(deviceAddress: String) {
        // Wait for connection request from the peer and accept joining
        val peer = MacAddress.fromString(deviceAddress)
        runAsShell(MANAGE_WIFI_NETWORK_SELECTION) {
            val connectionRequestFuture = CompletableFuture<Boolean>()
            wifip2pManager.addExternalApprover(
                    wifip2pChannel,
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
                    wifip2pChannel,
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
        val connectedFuture = CompletableFuture<Boolean>()
        wifip2pManager.requestPeers(wifip2pChannel) { peers ->
            for (peer in peers?.deviceList ?: listOf()) {
                if (peer.deviceName == deviceName && peer.status == WifiP2pDevice.CONNECTED) {
                    connectedFuture.complete(true)
                }
            }
        }
        connectedFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }
}
