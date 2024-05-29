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

import android.Manifest.permission.NETWORK_SETTINGS
import android.Manifest.permission.OVERRIDE_WIFI_CONFIG
import android.content.pm.PackageManager.FEATURE_TELEPHONY
import android.content.pm.PackageManager.FEATURE_WIFI
import android.net.ConnectivityManager
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
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiSsid
import androidx.test.platform.app.InstrumentationRegistry
import com.android.testutils.AutoReleaseNetworkCallbackRule
import com.android.testutils.ConnectUtil
import com.android.testutils.NetworkCallbackHelper
import com.android.testutils.RecorderCallback.CallbackEntry.CapabilitiesChanged
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.runAsShell
import com.google.android.mobly.snippet.Snippet
import com.google.android.mobly.snippet.rpc.Rpc
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Rule

private const val SERVICE_NAME = "MultiDevicesTest"
private const val SERVICE_TYPE = "_multi_devices._tcp"
private const val TIMEOUT_MS = 60000L

class ConnectivityMultiDevicesSnippet : Snippet {
    @get:Rule
    val networkCallbackRule = AutoReleaseNetworkCallbackRule()
    private val context = InstrumentationRegistry.getInstrumentation().getTargetContext()
    private val wifiManager = context.getSystemService(WifiManager::class.java)!!
    private val cm = context.getSystemService(ConnectivityManager::class.java)!!
    private val pm = context.packageManager
    private val nsdManager = context.getSystemService(NsdManager::class.java)!!
    private val ctsNetUtils = CtsNetUtils(context)
    private val cbHelper = NetworkCallbackHelper()
    private val ctsTetheringUtils = CtsTetheringUtils(context)
    private var oldSoftApConfig: SoftApConfiguration? = null
    private val regListener = TestRegistrationListener()
    private val discListener = TestDiscoveryListener()
    private val resolveListener = TestResolveListener()

    override fun shutdown() {
        cbHelper.unregisterAll()
    }

    @Rpc(description = "Check whether the device has wifi feature.")
    fun hasWifiFeature() = pm.hasSystemFeature(FEATURE_WIFI)

    @Rpc(description = "Check whether the device has telephony feature.")
    fun hasTelephonyFeature() = pm.hasSystemFeature(FEATURE_TELEPHONY)

    @Rpc(description = "Check whether the device supporters AP + STA concurrency.")
    fun isStaApConcurrencySupported() = wifiManager.isStaApConcurrencySupported()

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
    fun connectToWifi(ssid: String, passphrase: String): Long {
        val specifier = WifiNetworkSpecifier.Builder()
            .setBand(ScanResult.WIFI_BAND_24_GHZ)
            .build()
        val wifiConfig = WifiConfiguration()
        wifiConfig.SSID = "\"" + ssid + "\""
        wifiConfig.preSharedKey = "\"" + passphrase + "\""
        wifiConfig.hiddenSSID = true
        wifiConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA2_PSK)
        wifiConfig.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP)
        wifiConfig.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)

        // Add the test configuration and connect to it.
        val connectUtil = ConnectUtil(context)
        connectUtil.connectToWifiConfig(wifiConfig)

        // Implement manual SSID matching. Specifying the SSID in
        // NetworkSpecifier is ineffective
        // (see WifiNetworkAgentSpecifier#canBeSatisfiedBy for details).
        // Note that holding permission is necessary when waiting for
        // the callbacks. The handler thread checks permission; if
        // it's not present, the SSID will be redacted.
        val networkCallback = TestableNetworkCallback()
        val wifiRequest = NetworkRequest.Builder().addTransportType(TRANSPORT_WIFI).build()
        return runAsShell(NETWORK_SETTINGS) {
            // Register the network callback is needed here.
            // This is to avoid the race condition where callback is fired before
            // acquiring permission.
            networkCallbackRule.registerNetworkCallback(wifiRequest, networkCallback)
            return@runAsShell networkCallback.eventuallyExpect<CapabilitiesChanged> {
                // Remove double quotes.
                val ssidFromCaps = (WifiInfo::sanitizeSsid)(it.caps.ssid)
                ssidFromCaps == ssid && it.caps.hasCapability(NET_CAPABILITY_VALIDATED)
            }.network.networkHandle
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
}
