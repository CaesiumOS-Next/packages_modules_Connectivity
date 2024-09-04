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

package android.net.thread.utils

import android.Manifest.permission
import android.Manifest.permission.MANAGE_TEST_NETWORKS
import android.content.Context
import android.net.InetAddresses.parseNumericAddress
import android.net.IpPrefix
import android.net.LinkProperties
import android.net.MacAddress
import android.net.RouteInfo
import com.android.testutils.FunctionalUtils
import com.android.testutils.TapPacketReader
import com.android.testutils.TestNetworkTracker
import com.android.testutils.initTestNetwork
import com.android.testutils.runAsShell
import java.lang.Exception
import java.net.Inet4Address
import java.net.InetAddress
import java.time.Duration
import java.util.function.Predicate

object IntegrationTestUtilsKt {
    @JvmStatic
    @JvmOverloads
    fun startInfraDeviceAndWaitForOnLinkAddr(
        tapPacketReader: TapPacketReader,
        macAddress: MacAddress = MacAddress.fromString("1:2:3:4:5:6")
    ): InfraNetworkDevice {
        val infraDevice = InfraNetworkDevice(macAddress, tapPacketReader)
        infraDevice.runSlaac(Duration.ofSeconds(60))
        requireNotNull(infraDevice.ipv6Addr)
        return infraDevice
    }

    @JvmStatic
    @Throws(java.lang.Exception::class)
    fun setUpInfraNetwork(
        context: Context, controller: ThreadNetworkControllerWrapper
    ): TestNetworkTracker {
        val lp = LinkProperties()

        // TODO: use a fake DNS server
        lp.setDnsServers(listOf(parseNumericAddress("8.8.8.8")))
        // NAT64 feature requires the infra network to have an IPv4 default route.
        lp.addRoute(
            RouteInfo(
                IpPrefix("0.0.0.0/0"),  /* destination */
                null,  /* gateway */
                null,  /* iface */
                RouteInfo.RTN_UNICAST, 1500 /* mtu */
            )
        )
        val infraNetworkTracker: TestNetworkTracker = runAsShell(
            MANAGE_TEST_NETWORKS,
            supplier = { initTestNetwork(context, lp, setupTimeoutMs = 5000) })
        val infraNetworkName: String = infraNetworkTracker.testIface.getInterfaceName()
        controller.setTestNetworkAsUpstreamAndWait(infraNetworkName)

        return infraNetworkTracker
    }

    @JvmStatic
    fun tearDownInfraNetwork(testNetworkTracker: TestNetworkTracker) {
        runAsShell(MANAGE_TEST_NETWORKS) { testNetworkTracker.teardown() }
    }
}
