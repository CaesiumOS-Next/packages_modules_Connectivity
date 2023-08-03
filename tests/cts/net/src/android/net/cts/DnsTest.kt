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

package android.net.cts

import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.LinkProperties
import android.net.Network
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val DBG = false
private const val TAG = "DnsTest"
private const val PROXY_NETWORK_TYPE = "PROXY"

@RunWith(AndroidJUnit4::class)
class DnsTest {
    private val context = InstrumentationRegistry.getInstrumentation().getContext()
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    companion object {
        init {
            System.loadLibrary("nativedns_jni")
        }
    }

    @JvmName("testNativeDns") external fun testNativeDns(): Boolean

    @Test
    fun testDnsWorks() {
        // Only run this test on devices with IPv6 support
        assumeIpv6Connectivity()

        var addrs =
            try {
                InetAddress.getAllByName("www.google.com")
            } catch (e: UnknownHostException) {
                emptyArray()
            }
        assertFalse(
            addrs.isEmpty(),
            "[RERUN] DNS could not resolve www.google.com. Check internet connection"
        )
        var foundV4 = false
        var foundV6 = false
        for (addr in addrs) {
            when (addr) {
                is Inet4Address -> foundV4 = true
                is Inet6Address -> foundV6 = true
            }
            if (DBG) Log.e(TAG, "www.google.com gave " + addr)
        }

        // We should have at least one of the addresses to connect!
        assertTrue(foundV4 || foundV6, "www.google.com must have IPv4 and/or IPv6 address")

        // Skip the rest of the test if the active network for watch is PROXY.
        if (
            context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH) &&
                activeNetworkInfoIsProxy()
        ) {
            Log.i(TAG, "Skipping test because the active network type name is PROXY.")
            return
        }

        // Clear test state so we don't get confused with the previous results.
        addrs =
            try {
                InetAddress.getAllByName("ipv6.google.com")
            } catch (e: UnknownHostException) {
                emptyArray()
            }
        var msg =
            "[RERUN] DNS could not resolve ipv6.google.com, check the network supports IPv6. lp=" +
                cm.getActiveLinkProperties()
        assertFalse(addrs.isEmpty(), msg)
        foundV6 = false
        for (addr in addrs) {
            msg =
                "[RERUN] ipv6.google.com returned IPv4 address: " +
                    addr.getHostAddress() +
                    ", check your network's DNS server. lp=" +
                    cm.getActiveLinkProperties()
            assertFalse(addr is Inet4Address, msg)
            foundV6 = foundV6 or (addr is Inet6Address)
            if (DBG) Log.e(TAG, "ipv6.google.com gave $addr")
        }

        assertTrue(foundV6)

        assertTrue(testNativeDns())
    }

    private fun activeNetworkInfoIsProxy(): Boolean {
        val info = cm.getActiveNetworkInfo()
        return PROXY_NETWORK_TYPE.equals(info.getTypeName())
    }

    private fun assumeIpv6Connectivity() {
        val latch = CountDownLatch(1)
        val TIMEOUT_MS: Long = 5_000

        var callback =
            object : NetworkCallback() {
                @Override
                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                    if (lp.hasGlobalIpv6Address()) {
                        latch.countDown()
                    }
                }
            }
        cm.registerDefaultNetworkCallback(callback)

        val msg =
            "Default network did not provide IPv6 connectivity after $TIMEOUT_MS" +
                "ms. Please connect to an IPv6-capable network. lp=" +
                "${cm.getActiveLinkProperties()}"
        try {
            assumeTrue(msg, latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        } finally {
            cm.unregisterNetworkCallback(callback)
        }
    }
}
