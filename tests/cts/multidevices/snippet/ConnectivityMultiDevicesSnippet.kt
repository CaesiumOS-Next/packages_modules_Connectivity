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

import android.Manifest.permission.OVERRIDE_WIFI_CONFIG
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager.FEATURE_TELEPHONY
import android.content.pm.PackageManager.FEATURE_WIFI
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.net.cts.util.CtsNetUtils
import android.net.cts.util.CtsTetheringUtils
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.ScanResult
import android.net.wifi.SoftApConfiguration
import android.net.wifi.SoftApConfiguration.SECURITY_TYPE_WPA2_PSK
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiSsid
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject
import androidx.test.uiautomator.UiSelector
import com.android.testutils.ConnectUtil
import com.android.testutils.NetworkCallbackHelper
import com.android.testutils.RecorderCallback.CallbackEntry.Available
import com.android.testutils.RecorderCallback.CallbackEntry.CapabilitiesChanged
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.runAsShell
import com.google.android.mobly.snippet.Snippet
import com.google.android.mobly.snippet.rpc.Rpc
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.fail

private const val SERVICE_NAME = "MultiDevicesTest"
private const val SERVICE_TYPE = "_multi_devices._tcp"
private const val P2P_GROUP_NAME = "DIRECT-MD-p2pTest"
private const val P2P_GROUP_PASSPHRASE = "MultiDevicesP2pTest"
private const val TIMEOUT_MS = 60000L

class ConnectivityMultiDevicesSnippet : Snippet {
    private val context = InstrumentationRegistry.getInstrumentation().getTargetContext()
    private val wifiManager = context.getSystemService(WifiManager::class.java)!!
    private val cm = context.getSystemService(ConnectivityManager::class.java)!!
    private val pm = context.packageManager
    private val nsdManager = context.getSystemService(NsdManager::class.java)!!
    private val wifip2pManager = context.getSystemService(WifiP2pManager::class.java)!!
    private val ctsNetUtils = CtsNetUtils(context)
    private val cbHelper = NetworkCallbackHelper()
    private val ctsTetheringUtils = CtsTetheringUtils(context)
    private var oldSoftApConfig: SoftApConfiguration? = null
    private val regListener = TestRegistrationListener()
    private val discListener = TestDiscoveryListener()
    private val resolveListener = TestResolveListener()
    private val wifiP2pChannels = ArrayList<WifiP2pManager.Channel>()
    private val receiver = WiFiDirectBroadcastReceiver()
    private val uiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    override fun shutdown() {
        cbHelper.unregisterAll()
    }

    @Rpc(description = "Check whether the device has wifi feature.")
    fun hasWifiFeature() = pm.hasSystemFeature(FEATURE_WIFI)

    @Rpc(description = "Check whether the device has telephony feature.")
    fun hasTelephonyFeature() = pm.hasSystemFeature(FEATURE_TELEPHONY)

    @Rpc(description = "Check whether the device supporters AP + STA concurrency.")
    fun isStaApConcurrencySupported() = wifiManager.isStaApConcurrencySupported()

    @Rpc(description = "Check whether the device supports Wi-Fi P2P.")
    fun isP2pSupported() = wifiManager.isP2pSupported()

    @Rpc(description = "Request cellular connection and ensure it is the default network.")
    fun requestCellularAndEnsureDefault() {
        ctsNetUtils.disableWifi()
        val network = cbHelper.requestCell()
        ctsNetUtils.expectNetworkIsSystemDefault(network)
    }

    @Rpc(description = "Unrequest cellular connection.")
    fun unrequestCellular() {
        cbHelper.unrequestCell()
    }

    @Rpc(description = "Ensure any wifi is connected and is the default network.")
    fun ensureWifiIsDefault() {
        val network = ctsNetUtils.ensureWifiConnected()
        ctsNetUtils.expectNetworkIsSystemDefault(network)
    }

    @Rpc(description = "Connect to specified wifi network.")
    // Suppress warning because WifiManager methods to connect to a config are
    // documented not to be deprecated for privileged users.
    @Suppress("DEPRECATION")
    fun connectToWifi(ssid: String, passphrase: String, requireValidation: Boolean): Network {
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(passphrase)
            .setBand(ScanResult.WIFI_BAND_24_GHZ)
            .build()
        val wifiConfig = WifiConfiguration()
        wifiConfig.SSID = "\"" + ssid + "\""
        wifiConfig.preSharedKey = "\"" + passphrase + "\""
        wifiConfig.hiddenSSID = true
        wifiConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA2_PSK)
        wifiConfig.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP)
        wifiConfig.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)

        // Register network callback for the specific wifi.
        val networkCallback = TestableNetworkCallback()
        val wifiRequest = NetworkRequest.Builder().addTransportType(TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()
        cm.registerNetworkCallback(wifiRequest, networkCallback)

        try {
            // Add the test configuration and connect to it.
            val connectUtil = ConnectUtil(context)
            connectUtil.connectToWifiConfig(wifiConfig)

            val event = networkCallback.expect<Available>()
            if (requireValidation) {
                networkCallback.eventuallyExpect<CapabilitiesChanged> {
                    it.caps.hasCapability(NET_CAPABILITY_VALIDATED)
                }
            }
            return event.network
        } finally {
            cm.unregisterNetworkCallback(networkCallback)
        }
    }

    @Rpc(description = "Check whether the device supports hotspot feature.")
    fun hasHotspotFeature(): Boolean {
        val tetheringCallback = ctsTetheringUtils.registerTetheringEventCallback()
        try {
            return tetheringCallback.isWifiTetheringSupported(context)
        } finally {
            ctsTetheringUtils.unregisterTetheringEventCallback(tetheringCallback)
        }
    }

    @Rpc(description = "Start a hotspot with given SSID and passphrase.")
    fun startHotspot(ssid: String, passphrase: String) {
        // Store old config.
        runAsShell(OVERRIDE_WIFI_CONFIG) {
            oldSoftApConfig = wifiManager.getSoftApConfiguration()
        }

        val softApConfig = SoftApConfiguration.Builder()
            .setWifiSsid(WifiSsid.fromBytes(ssid.toByteArray()))
            .setPassphrase(passphrase, SECURITY_TYPE_WPA2_PSK)
            .setBand(SoftApConfiguration.BAND_2GHZ)
            .build()
        runAsShell(OVERRIDE_WIFI_CONFIG) {
            wifiManager.setSoftApConfiguration(softApConfig)
        }
        val tetheringCallback = ctsTetheringUtils.registerTetheringEventCallback()
        try {
            tetheringCallback.expectNoTetheringActive()
            ctsTetheringUtils.startWifiTethering(tetheringCallback)
        } finally {
            ctsTetheringUtils.unregisterTetheringEventCallback(tetheringCallback)
        }
    }

    @Rpc(description = "Stop all tethering.")
    fun stopAllTethering() {
        ctsTetheringUtils.stopAllTethering()

        // Restore old config.
        oldSoftApConfig?.let {
            runAsShell(OVERRIDE_WIFI_CONFIG) {
                wifiManager.setSoftApConfiguration(it)
            }
        }
    }

    internal class TestRegistrationListener : NsdManager.RegistrationListener {
        val regFuture = CompletableFuture<Boolean>()
        val unregFuture = CompletableFuture<Boolean>()

        override fun onRegistrationFailed(info: NsdServiceInfo, i: Int) {}
        override fun onUnregistrationFailed(info: NsdServiceInfo, i: Int) {}
        override fun onServiceRegistered(info: NsdServiceInfo) {
            regFuture.complete(true)
        }
        override fun onServiceUnregistered(info: NsdServiceInfo) {
            unregFuture.complete(true)
        }

        fun ensureServiceRegistered() = regFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        fun ensureServiceUnregistered() = unregFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    internal class TestDiscoveryListener : NsdManager.DiscoveryListener {
        val discFuture = CompletableFuture<NsdServiceInfo>()
        val undiscFuture = CompletableFuture<Boolean>()

        override fun onStartDiscoveryFailed(s: String, i: Int) {}
        override fun onStopDiscoveryFailed(s: String, i: Int) {}
        override fun onDiscoveryStarted(s: String) {}
        override fun onServiceLost(info: NsdServiceInfo) {}
        override fun onDiscoveryStopped(s: String) {
            undiscFuture.complete(true)
        }
        override fun onServiceFound(info: NsdServiceInfo) {
            if (SERVICE_NAME.equals(info.serviceName)) {
                discFuture.complete(info)
            }
        }

        fun ensureServiceDiscovered() = discFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        fun ensureStopDisocvery() = undiscFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    internal class TestResolveListener : NsdManager.ResolveListener {
        val resolveFuture = CompletableFuture<Boolean>()

        override fun onResolveFailed(info: NsdServiceInfo, i: Int) {}
        override fun onResolutionStopped(info: NsdServiceInfo) {}
        override fun onStopResolutionFailed(info: NsdServiceInfo, i: Int) {}
        override fun onServiceResolved(info: NsdServiceInfo) {
            if (SERVICE_NAME.equals(info.serviceName)) {
                resolveFuture.complete(true)
            }
        }

        fun ensureServiceResolved() = resolveFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    @Rpc(description = "Register a mDns service")
    fun registerMDnsService() {
        val info = NsdServiceInfo()
        info.setServiceName(SERVICE_NAME)
        info.setServiceType(SERVICE_TYPE)
        info.setPort(12345)
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, regListener)
        regListener.ensureServiceRegistered()
    }

    @Rpc(description = "Unregister a mDns service")
    fun unregisterMDnsService() {
        nsdManager.unregisterService(regListener)
        regListener.ensureServiceUnregistered()
    }

    @Rpc(description = "Discover a mDns service and resolve retrieved mDns service")
    fun discoverAndResolveMDnsService() {
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discListener)
        val info = discListener.ensureServiceDiscovered()
        nsdManager.resolveService(info, resolveListener)
        resolveListener.ensureServiceResolved()
    }

    @Rpc(description = "Stop disocvery")
    fun stopMDnsServiceDiscovery() {
        nsdManager.stopServiceDiscovery(discListener)
        discListener.ensureStopDisocvery()
    }

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

        fun ensureDeviceFound() = peerChangedFuture.get(120000L, TimeUnit.MILLISECONDS)

        fun ensureGroupConnected() = groupConnectedFuture.get(120000L, TimeUnit.MILLISECONDS)

        fun ensureDeviceConnected() = deviceConnectedFuture.get(120000L, TimeUnit.MILLISECONDS)
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
