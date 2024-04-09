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
import android.content.pm.PackageManager.FEATURE_TELEPHONY
import android.content.pm.PackageManager.FEATURE_WIFI
import android.net.ConnectivityManager
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.net.cts.util.CtsNetUtils
import android.net.cts.util.CtsTetheringUtils
import android.net.util.SocketUtils
import android.net.wifi.ScanResult
import android.net.wifi.SoftApConfiguration
import android.net.wifi.SoftApConfiguration.SECURITY_TYPE_WPA2_PSK
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiSsid
import android.system.Os
import android.system.OsConstants.AF_PACKET
import android.system.OsConstants.SOCK_NONBLOCK
import android.system.OsConstants.SOCK_RAW
import androidx.test.platform.app.InstrumentationRegistry
import com.android.net.module.util.NetworkStackConstants.ETHER_BROADCAST
import com.android.net.module.util.Struct
import com.android.net.module.util.structs.EthernetHeader
import com.android.testutils.ConnectUtil
import com.android.testutils.NetworkCallbackHelper
import com.android.testutils.RecorderCallback.CallbackEntry.CapabilitiesChanged
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.runAsShell
import com.google.android.mobly.snippet.Snippet
import com.google.android.mobly.snippet.rpc.Rpc
import java.net.NetworkInterface
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.util.Objects

class ConnectivityMultiDevicesSnippet : Snippet {
    companion object {
        const val ETH_P_ETHERCAT = 0x88A4
    }
    private val context = InstrumentationRegistry.getInstrumentation().getTargetContext()
    private val wifiManager = context.getSystemService(WifiManager::class.java)!!
    private val cm = context.getSystemService(ConnectivityManager::class.java)!!
    private val pm = context.packageManager
    private val ctsNetUtils = CtsNetUtils(context)
    private val cbHelper = NetworkCallbackHelper()
    private val ctsTetheringUtils = CtsTetheringUtils(context)
    private var oldSoftApConfig: SoftApConfiguration? = null

    override fun shutdown() {
        cbHelper.unregisterAll()
    }

    @Rpc(description = "Check whether the device has wifi feature.")
    fun hasWifiFeature() = pm.hasSystemFeature(FEATURE_WIFI)

    @Rpc(description = "Check whether the device has telephony feature.")
    fun hasTelephonyFeature() = pm.hasSystemFeature(FEATURE_TELEPHONY)

    @Rpc(description = "Check whether the device supporters AP + STA concurrency.")
    fun isStaApConcurrencySupported() {
        wifiManager.isStaApConcurrencySupported()
    }

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
    fun connectToWifi(ssid: String, passphrase: String, requireValidation: Boolean): String {
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

            // Manual match ssid since specifying ssid in the NetworkSpecifier doesn't work.
            // See WifiNetworkAgentSpecifier#canBeSatisfiedBy for more detail.
            val network = networkCallback.eventuallyExpect<CapabilitiesChanged> {
                // Remove double quotes.
                val ssidFromCaps = it.caps.ssid?.trim('\"')
                ssidFromCaps == ssid && (!requireValidation ||
                        it.caps.hasCapability(NET_CAPABILITY_VALIDATED))
            }.network
            return cm.getLinkProperties(network)!!.getInterfaceName()!!
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
    fun startHotspot(ssid: String, passphrase: String): String {
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
            return ctsTetheringUtils.startWifiTethering(tetheringCallback).getInterface()
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

    @Rpc(description = "Transmit a broadcast empty EtherCat type packet.")
    fun sendBroadcastEmptyEtherCatPacket(ifaceName: String) {
        val iface = Objects.requireNonNull(NetworkInterface.getByName(ifaceName))
        val addr = SocketUtils.makePacketSocketAddress(
            ETH_P_ETHERCAT,
            iface.getIndex(),
            ETHER_BROADCAST
        )
        // 2 bytes EtherCat header + minimum 44 bytes of datagram = 46, as defined in IEC 61158.
        val buf = ByteBuffer.allocate(Struct.getSize(EthernetHeader::class.java) + 46)
        buf.put(ETHER_BROADCAST)
        buf.put(iface.hardwareAddress)
        buf.putShort(ETH_P_ETHERCAT.toShort())
        // An empty EtherCat payload consist of all 0's in the header and datagram, no
        // need to fill values for the rest of the buffer.
        transmitPacket(buf, addr)
    }

    private fun transmitPacket(packet: ByteBuffer, sockAddress: SocketAddress) {
        var sock = Os.socket(AF_PACKET, SOCK_RAW or SOCK_NONBLOCK, 0)
        try {
            Os.sendto(sock, packet.array(), 0, packet.limit(), 0, sockAddress)
        } finally {
            SocketUtils.closeSocket(sock)
        }
    }
}
