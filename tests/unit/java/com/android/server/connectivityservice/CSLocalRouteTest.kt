package com.android.server.connectivityservice

import android.net.INetd
import android.net.InetAddresses
import android.net.IpPrefix
import android.net.LinkAddress
import android.net.RouteInfo
import android.os.Build
import androidx.test.filters.SmallTest
import com.android.server.CSTest
import com.android.server.ConnectivityService
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import java.net.Inet6Address
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

private const val WIFI_IFNAME = "wlan0"
private const val NON_LOCAL_NET_ID = 102

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
class CSLocalRouteTest : CSTest() {
    private val LOCAL_IPV6_IP_ADDRESS_PREFIX = IpPrefix("fe80::1cf1:35ff:fe8c:db87/64")
    private val LOCAL_IPV6_LINK_ADDRESS = LinkAddress(
        LOCAL_IPV6_IP_ADDRESS_PREFIX.getAddress(),
        LOCAL_IPV6_IP_ADDRESS_PREFIX.getPrefixLength()
    )

    private val LOCAL_IPV4_IP_ADDRESS_PREFIX_1 = IpPrefix("10.0.0.184/24")
    private val LOCAL_IPV4_LINK_ADDRRESS_1 =
        LinkAddress(
            LOCAL_IPV4_IP_ADDRESS_PREFIX_1.getAddress(),
            LOCAL_IPV4_IP_ADDRESS_PREFIX_1.getPrefixLength()
        )

    private val TEST_IPV6_GATEWAY = InetAddresses.parseNumericAddress("2001:db8::1") as Inet6Address

    private val NON_LOCAL_IPV4_IP_ADDRESS_PREFIX_1 = IpPrefix("8.8.0.0/24")
    private val NON_LOCAL_IPV4_LINK_ADDRRESS_1 =
        LinkAddress(
            NON_LOCAL_IPV4_IP_ADDRESS_PREFIX_1.getAddress(),
            NON_LOCAL_IPV4_IP_ADDRESS_PREFIX_1.getPrefixLength()
        )

    @Test
    fun testIsLocalRouteForLocalIPv6_TrueForLocalRoute() {
        val route = RouteInfo(LOCAL_IPV6_LINK_ADDRESS, null, WIFI_IFNAME)
        Assert.assertTrue(ConnectivityService.isLocalRoute(route, NON_LOCAL_NET_ID))
    }

    @Test
    fun testIsLocalRouteWithNextHopForLocalIPv6_FalseForLocalRoute() {
        val route = RouteInfo(LOCAL_IPV6_LINK_ADDRESS, TEST_IPV6_GATEWAY, WIFI_IFNAME)
        Assert.assertFalse(ConnectivityService.isLocalRoute(route, NON_LOCAL_NET_ID))
    }

    @Test
    fun testIsLocalRouteWithLocalNetIdForLocalIPv6_FalseForLocalRoute() {
        val route = RouteInfo(LOCAL_IPV6_LINK_ADDRESS, null, WIFI_IFNAME)
        Assert.assertFalse(ConnectivityService.isLocalRoute(route, INetd.LOCAL_NET_ID))
    }

    @Test
    fun testIsLocalRouteForLocalIPv4_TrueForLocalRoute() {
        val route = RouteInfo(LOCAL_IPV4_LINK_ADDRRESS_1, null, WIFI_IFNAME)
        Assert.assertTrue(ConnectivityService.isLocalRoute(route, NON_LOCAL_NET_ID))
    }

    @Test
    fun testIsLocalRouteForNonLocalIPv4_FalseForLocalRoute() {
        val route = RouteInfo(NON_LOCAL_IPV4_LINK_ADDRRESS_1, null, WIFI_IFNAME)
        Assert.assertFalse(ConnectivityService.isLocalRoute(route, NON_LOCAL_NET_ID))
    }
}
