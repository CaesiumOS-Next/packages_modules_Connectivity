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
import android.net.NetworkRequest
import android.net.VpnManager
import android.net.VpnTransportInfo
import android.os.Build
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
private val IPV6_ADDRESS = InetAddresses.parseNumericAddress("2001:db8:1::1")
private val IPV6_LINK_ADDRESS = LinkAddress(IPV6_ADDRESS, 64)
private val IPV6_ADDRESS2 = InetAddresses.parseNumericAddress("2001:db8:1::2")
private val IPV6_LINK_ADDRESS2 = LinkAddress(IPV6_ADDRESS2, 64)
private val IPV6_LOCAL_ADDRRESS = InetAddresses.parseNumericAddress("fe80::1234")
private val IPV6_LINK_LOCAL_ADDRRESS = LinkAddress(IPV6_LOCAL_ADDRRESS, 64)

private fun vpnNc() = NetworkCapabilities.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
        .setTransportInfo(
                VpnTransportInfo(
                        VpnManager.TYPE_VPN_SERVICE,
                        "MySession12345",
                        false,
                        false))
        .build()

private fun wifiNc() = NetworkCapabilities.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
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
    @Test
    fun testVpnIngressDiscardRule_UpdateVpnAddress() {
        val nr = nr(NetworkCapabilities.TRANSPORT_VPN)
        val cb = TestableNetworkCallback()
        cm.registerNetworkCallback(nr, cb)

        val nc = vpnNc()
        val lp = lp(VPN_IFNAME, IPV6_LINK_ADDRESS, IPV6_LINK_LOCAL_ADDRRESS)
        val agent = Agent(nc = nc, lp = lp)

        agent.connect()
        cb.expectAvailableCallbacks(agent.network, validated = false)

        // IngressDiscardRule is added for VPN address
        verify(bpfNetMaps).setIngressDiscardRule(IPV6_ADDRESS, VPN_IFNAME)
        verify(bpfNetMaps, never()).setIngressDiscardRule(IPV6_LOCAL_ADDRRESS, VPN_IFNAME)

        // VPN address is changed
        val newlp = lp(VPN_IFNAME, IPV6_LINK_ADDRESS2, IPV6_LINK_LOCAL_ADDRRESS)
        agent.sendLinkProperties(newlp)
        cb.expect<RecorderCallback.CallbackEntry.LinkPropertiesChanged>(agent.network)

        // IngressDiscardRule is removed from old VPN address and added to new VPN address
        verify(bpfNetMaps).removeIngressDiscardRule(IPV6_ADDRESS)
        verify(bpfNetMaps).setIngressDiscardRule(IPV6_ADDRESS2, VPN_IFNAME)
        verify(bpfNetMaps, never()).setIngressDiscardRule(IPV6_LOCAL_ADDRRESS, VPN_IFNAME)

        // IngressDiscardRule is removed since the VPN is disconnected
        agent.disconnect()
        verify(bpfNetMaps).removeIngressDiscardRule(IPV6_ADDRESS2)

        cm.unregisterNetworkCallback(cb)
    }

    @Test
    fun testVpnIngressDiscardRule_UpdateInterfaceName() {
        val inorder = inOrder(bpfNetMaps)

        val nr = nr(NetworkCapabilities.TRANSPORT_VPN)
        val cb = TestableNetworkCallback()
        cm.registerNetworkCallback(nr, cb)

        val nc = vpnNc()
        val lp = lp(VPN_IFNAME, IPV6_LINK_ADDRESS, IPV6_LINK_LOCAL_ADDRRESS)
        val agent = Agent(nc = nc, lp = lp)
        agent.connect()
        cb.expectAvailableCallbacks(agent.network, validated = false)

        // IngressDiscardRule is added for VPN address
        inorder.verify(bpfNetMaps).setIngressDiscardRule(IPV6_ADDRESS, VPN_IFNAME)
        inorder.verify(bpfNetMaps, never()).setIngressDiscardRule(IPV6_LOCAL_ADDRRESS, VPN_IFNAME)

        // VPN interface is changed
        val newlp = lp(VPN_IFNAME2, IPV6_LINK_ADDRESS, IPV6_LINK_LOCAL_ADDRRESS)
        agent.sendLinkProperties(newlp)
        cb.expect<RecorderCallback.CallbackEntry.LinkPropertiesChanged>(agent.network)

        // IngressDiscardRule is removed and added with new interface name
        inorder.verify(bpfNetMaps).removeIngressDiscardRule(IPV6_ADDRESS)
        inorder.verify(bpfNetMaps).setIngressDiscardRule(IPV6_ADDRESS, VPN_IFNAME2)
        inorder.verify(bpfNetMaps, never()).setIngressDiscardRule(IPV6_LOCAL_ADDRRESS, VPN_IFNAME)

        // IngressDiscardRule is removed since the VPN is disconnected
        agent.disconnect()
        inorder.verify(bpfNetMaps).removeIngressDiscardRule(IPV6_ADDRESS)

        cm.unregisterNetworkCallback(cb)
    }

    @Test
    fun testVpnIngressDiscardRule_DuplicatedIpAddress_UpdateVpnAddress() {
        val inorder = inOrder(bpfNetMaps)

        val nr = nr(NetworkCapabilities.TRANSPORT_VPN)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        val wifiNc = wifiNc()
        val wifiLp = lp(WIFI_IFNAME, IPV6_LINK_ADDRESS, IPV6_LINK_LOCAL_ADDRRESS)
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)

        // IngressDiscardRule is not added for non-VPN interface
        wifiAgent.connect()
        inorder.verify(bpfNetMaps, never()).setIngressDiscardRule(any(), any())

        val vpnNc = vpnNc()
        val vpnLp = lp(VPN_IFNAME, IPV6_LINK_ADDRESS, IPV6_LINK_LOCAL_ADDRRESS)
        val vpnAgent = Agent(nc = vpnNc, lp = vpnLp)
        vpnAgent.connect()
        cb.expectAvailableCallbacks(vpnAgent.network, validated = false)

        // IngressDiscardRule is not added since VPN address is duplicated with WiFi address
        inorder.verify(bpfNetMaps, never()).setIngressDiscardRule(any(), any())

        // VPN address is changed to different address from the wifi network
        val newVpnlp = lp(VPN_IFNAME, IPV6_LINK_ADDRESS2, IPV6_LINK_LOCAL_ADDRRESS)
        vpnAgent.sendLinkProperties(newVpnlp)

        // IngressDiscardRule is added to VPN address since VPN address is changed to the different
        // address from WiFi address
        cb.expect<RecorderCallback.CallbackEntry.LinkPropertiesChanged>(vpnAgent.network)
        inorder.verify(bpfNetMaps).setIngressDiscardRule(IPV6_ADDRESS2, VPN_IFNAME)

        // VPN address is changed back to the same address with the wifi network
        vpnAgent.sendLinkProperties(vpnLp)
        cb.expect<RecorderCallback.CallbackEntry.LinkPropertiesChanged>(vpnAgent.network)

        // IngressDiscardRule is not added to the new address since the VPN address is the same
        // with the WiFi address
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

        val nr = nr(NetworkCapabilities.TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        val vpnNc = vpnNc()
        val vpnLp = lp(VPN_IFNAME, IPV6_LINK_ADDRESS, IPV6_LINK_LOCAL_ADDRRESS)
        val vpnAgent = Agent(nc = vpnNc, lp = vpnLp)
        vpnAgent.connect()

        // IngressDiscardRule is added for VPN address
        inorder.verify(bpfNetMaps).setIngressDiscardRule(IPV6_ADDRESS, VPN_IFNAME)
        inorder.verify(bpfNetMaps, never()).setIngressDiscardRule(IPV6_LOCAL_ADDRRESS, VPN_IFNAME)

        val wifiNc = wifiNc()
        val wifiLp = lp(WIFI_IFNAME, IPV6_LINK_ADDRESS, IPV6_LINK_LOCAL_ADDRRESS)
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        // IngressDiscardRule is removed since WiFi address is the same with VPN address
        inorder.verify(bpfNetMaps).removeIngressDiscardRule(IPV6_ADDRESS)

        val newWifilp = lp(WIFI_IFNAME, IPV6_LINK_ADDRESS2, IPV6_LINK_LOCAL_ADDRRESS)
        wifiAgent.sendLinkProperties(newWifilp)
        cb.expect<RecorderCallback.CallbackEntry.LinkPropertiesChanged>(wifiAgent.network)

        // IngressDiscardRule is not added to VPN address since WiFi address is changed to different
        // address from the VPN address
        inorder.verify(bpfNetMaps).setIngressDiscardRule(IPV6_ADDRESS, VPN_IFNAME)
        inorder.verify(bpfNetMaps, never()).setIngressDiscardRule(eq(IPV6_ADDRESS2), any())

        wifiAgent.sendLinkProperties(wifiLp)
        cb.expect<RecorderCallback.CallbackEntry.LinkPropertiesChanged>(wifiAgent.network)

        // IngressDiscardRule is removed from VPN address since WiFi address is changed to the same
        // address
        inorder.verify(bpfNetMaps).removeIngressDiscardRule(IPV6_ADDRESS)

        // IngressDiscardRule is added for VPN addr since wifi is disconnected
        wifiAgent.disconnect()
        inorder.verify(bpfNetMaps).setIngressDiscardRule(IPV6_ADDRESS, VPN_IFNAME)

        // IngressDiscardRule is removed since the VPN is disconnected
        vpnAgent.disconnect()
        inorder.verify(bpfNetMaps).removeIngressDiscardRule(IPV6_ADDRESS)

        cm.unregisterNetworkCallback(cb)
    }
}
