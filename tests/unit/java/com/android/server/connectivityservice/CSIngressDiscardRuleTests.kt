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

import android.net.InetAddresses
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.net.VpnManager.TYPE_VPN_SERVICE
import android.net.VpnTransportInfo
import android.os.Build
import androidx.test.filters.SmallTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.RecorderCallback
import com.android.testutils.TestableNetworkCallback
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

private const val VPN_IFNAME = "tun10041"
private const val VPN_IFNAME2 = "tun10042"
private const val WIFI_IFNAME = "wlan0"

private fun vpnNc() = NetworkCapabilities.Builder()
        .addTransportType(TRANSPORT_VPN)
        .removeCapability(NET_CAPABILITY_NOT_VPN)
        .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
        .setTransportInfo(
                VpnTransportInfo(
                        TYPE_VPN_SERVICE,
                        "MySession12345",
                        false,
                        false))
        .build()

private fun wifiNc() = NetworkCapabilities.Builder()
        .addTransportType(TRANSPORT_WIFI)
        .build()

private fun nr(transport: Int) = NetworkRequest.Builder()
        .clearCapabilities()
        .addTransportType(transport)
        .build()

private fun lp(iface: String, vararg linkAddresses: LinkAddress) = LinkProperties().apply {
    interfaceName = iface
    for (linkAddress in linkAddresses) {
        addLinkAddress(linkAddress)
    }
}

@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
class CSIngressDiscardRuleTests : CSTest() {
    private val IPV6_ADDRESS = InetAddresses.parseNumericAddress("2001:db8:1::1")
    private val IPV6_LINK_ADDRESS = LinkAddress(IPV6_ADDRESS, 64)
    private val IPV6_ADDRESS2 = InetAddresses.parseNumericAddress("2001:db8:1::2")
    private val IPV6_LINK_ADDRESS2 = LinkAddress(IPV6_ADDRESS2, 64)
    private val LOCAL_IPV6_ADDRRESS = InetAddresses.parseNumericAddress("fe80::1234")
    private val LOCAL_IPV6_LINK_ADDRRESS = LinkAddress(LOCAL_IPV6_ADDRRESS, 64)

    @Test
    fun testVpnIngressDiscardRule_UpdateVpnAddress() {
        val nr = nr(TRANSPORT_VPN)
        val cb = TestableNetworkCallback()
        cm.registerNetworkCallback(nr, cb)

        val nc = vpnNc()
        val lp = lp(VPN_IFNAME, IPV6_LINK_ADDRESS, LOCAL_IPV6_LINK_ADDRRESS)
        val agent = Agent(nc = nc, lp = lp)

        agent.connect()
        cb.expectAvailableCallbacks(agent.network, validated = false)

        // IngressDiscardRule is added to VPN address
        verify(bpfNetMaps).setIngressDiscardRule(IPV6_ADDRESS, VPN_IFNAME)
        verify(bpfNetMaps, never()).setIngressDiscardRule(LOCAL_IPV6_ADDRRESS, VPN_IFNAME)

        // VPN address is changed
        val newLp = lp(VPN_IFNAME, IPV6_LINK_ADDRESS2, LOCAL_IPV6_LINK_ADDRRESS)
        agent.sendLinkProperties(newLp)
        cb.expect<RecorderCallback.CallbackEntry.LinkPropertiesChanged>(agent.network)

        // IngressDiscardRule is removed from old VPN address and added to new VPN address
        verify(bpfNetMaps).removeIngressDiscardRule(IPV6_ADDRESS)
        verify(bpfNetMaps).setIngressDiscardRule(IPV6_ADDRESS2, VPN_IFNAME)
        verify(bpfNetMaps, never()).setIngressDiscardRule(LOCAL_IPV6_ADDRRESS, VPN_IFNAME)

        agent.disconnect()
        verify(bpfNetMaps).removeIngressDiscardRule(IPV6_ADDRESS2)

        cm.unregisterNetworkCallback(cb)
    }

    @Test
    fun testVpnIngressDiscardRule_UpdateInterfaceName() {
        val inorder = inOrder(bpfNetMaps)

        val nr = nr(TRANSPORT_VPN)
        val cb = TestableNetworkCallback()
        cm.registerNetworkCallback(nr, cb)

        val nc = vpnNc()
        val lp = lp(VPN_IFNAME, IPV6_LINK_ADDRESS, LOCAL_IPV6_LINK_ADDRRESS)
        val agent = Agent(nc = nc, lp = lp)
        agent.connect()
        cb.expectAvailableCallbacks(agent.network, validated = false)

        // IngressDiscardRule is added to VPN address
        inorder.verify(bpfNetMaps).setIngressDiscardRule(IPV6_ADDRESS, VPN_IFNAME)
        inorder.verify(bpfNetMaps, never()).setIngressDiscardRule(LOCAL_IPV6_ADDRRESS, VPN_IFNAME)

        // VPN interface name is changed
        val newlp = lp(VPN_IFNAME2, IPV6_LINK_ADDRESS, LOCAL_IPV6_LINK_ADDRRESS)
        agent.sendLinkProperties(newlp)
        cb.expect<RecorderCallback.CallbackEntry.LinkPropertiesChanged>(agent.network)

        // IngressDiscardRule is removed and added with the new interface name
        inorder.verify(bpfNetMaps).removeIngressDiscardRule(IPV6_ADDRESS)
        inorder.verify(bpfNetMaps).setIngressDiscardRule(IPV6_ADDRESS, VPN_IFNAME2)
        inorder.verify(bpfNetMaps, never()).setIngressDiscardRule(LOCAL_IPV6_ADDRRESS, VPN_IFNAME)

        agent.disconnect()
        inorder.verify(bpfNetMaps).removeIngressDiscardRule(IPV6_ADDRESS)

        cm.unregisterNetworkCallback(cb)
    }

    @Test
    fun testVpnIngressDiscardRule_DuplicatedIpAddress_UpdateVpnAddress() {
        val inorder = inOrder(bpfNetMaps)

        val nr = nr(TRANSPORT_VPN)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        val wifiNc = wifiNc()
        val wifiLp = lp(WIFI_IFNAME, IPV6_LINK_ADDRESS, LOCAL_IPV6_LINK_ADDRRESS)
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)

        // IngressDiscardRule is not added to non-VPN interface
        wifiAgent.connect()
        inorder.verify(bpfNetMaps, never()).setIngressDiscardRule(any(), any())

        val vpnNc = vpnNc()
        val vpnLp = lp(VPN_IFNAME, IPV6_LINK_ADDRESS, LOCAL_IPV6_LINK_ADDRRESS)
        val vpnAgent = Agent(nc = vpnNc, lp = vpnLp)
        vpnAgent.connect()
        cb.expectAvailableCallbacks(vpnAgent.network, validated = false)

        // IngressDiscardRule is not added since VPN address is duplicated with Wi-Fi address
        inorder.verify(bpfNetMaps, never()).setIngressDiscardRule(any(), any())

        // VPN address is changed to different address from the Wi-Fi network
        val newVpnlp = lp(VPN_IFNAME, IPV6_LINK_ADDRESS2, LOCAL_IPV6_LINK_ADDRRESS)
        vpnAgent.sendLinkProperties(newVpnlp)

        // IngressDiscardRule is added to VPN address since VPN address is changed to the different
        // address from Wi-Fi address
        cb.expect<RecorderCallback.CallbackEntry.LinkPropertiesChanged>(vpnAgent.network)
        inorder.verify(bpfNetMaps).setIngressDiscardRule(IPV6_ADDRESS2, VPN_IFNAME)

        // VPN address is changed back to the same address with the Wi-Fi network
        vpnAgent.sendLinkProperties(vpnLp)
        cb.expect<RecorderCallback.CallbackEntry.LinkPropertiesChanged>(vpnAgent.network)

        // IngressDiscardRule is not added to the new address since the VPN address is the same
        // with the Wi-Fi address
        inorder.verify(bpfNetMaps).removeIngressDiscardRule(IPV6_ADDRESS2)
        inorder.verify(bpfNetMaps, never()).setIngressDiscardRule(any(), any())

        vpnAgent.disconnect()
        inorder.verify(bpfNetMaps, never()).removeIngressDiscardRule(any())
        inorder.verify(bpfNetMaps, never()).setIngressDiscardRule(any(), any())

        cm.unregisterNetworkCallback(cb)
    }

    @Test
    fun testVpnIngressDiscardRule_DuplicatedIpAddress_UpdateNonVpnAddress() {
        val inorder = inOrder(bpfNetMaps)

        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        val vpnNc = vpnNc()
        val vpnLp = lp(VPN_IFNAME, IPV6_LINK_ADDRESS, LOCAL_IPV6_LINK_ADDRRESS)
        val vpnAgent = Agent(nc = vpnNc, lp = vpnLp)
        vpnAgent.connect()

        // IngressDiscardRule is added to VPN address
        inorder.verify(bpfNetMaps).setIngressDiscardRule(IPV6_ADDRESS, VPN_IFNAME)
        inorder.verify(bpfNetMaps, never()).setIngressDiscardRule(LOCAL_IPV6_ADDRRESS, VPN_IFNAME)

        val wifiNc = wifiNc()
        val wifiLp = lp(WIFI_IFNAME, IPV6_LINK_ADDRESS, LOCAL_IPV6_LINK_ADDRRESS)
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        // IngressDiscardRule is removed since Wi-Fi address is the same with VPN address
        inorder.verify(bpfNetMaps).removeIngressDiscardRule(IPV6_ADDRESS)

        val newWifilp = lp(WIFI_IFNAME, IPV6_LINK_ADDRESS2, LOCAL_IPV6_LINK_ADDRRESS)
        wifiAgent.sendLinkProperties(newWifilp)
        cb.expect<RecorderCallback.CallbackEntry.LinkPropertiesChanged>(wifiAgent.network)

        // IngressDiscardRule is not added to VPN address since Wi-Fi address is changed to
        // different address from the VPN address
        inorder.verify(bpfNetMaps).setIngressDiscardRule(IPV6_ADDRESS, VPN_IFNAME)
        inorder.verify(bpfNetMaps, never()).setIngressDiscardRule(eq(IPV6_ADDRESS2), any())

        wifiAgent.sendLinkProperties(wifiLp)
        cb.expect<RecorderCallback.CallbackEntry.LinkPropertiesChanged>(wifiAgent.network)

        // IngressDiscardRule is removed from VPN address since Wi-Fi address is changed to the same
        // address
        inorder.verify(bpfNetMaps).removeIngressDiscardRule(IPV6_ADDRESS)

        // IngressDiscardRule is added for VPN addr since Wi-Fi is disconnected
        wifiAgent.disconnect()
        inorder.verify(bpfNetMaps).setIngressDiscardRule(IPV6_ADDRESS, VPN_IFNAME)

        vpnAgent.disconnect()
        inorder.verify(bpfNetMaps).removeIngressDiscardRule(IPV6_ADDRESS)

        cm.unregisterNetworkCallback(cb)
    }
}
