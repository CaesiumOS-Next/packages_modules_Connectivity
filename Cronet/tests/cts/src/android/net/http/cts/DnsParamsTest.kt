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

package android.net.http.cts

import android.net.http.DnsParams
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DnsParamsTest {

    @Test
    fun testDnsParams_defaultValues() {
        val params = DnsParams.Builder().build()

        assertNull(params.persistHostCache)
        assertNull(params.persistHostCachePeriod)
        assertNull(params.enableStaleDns)
        assertNull(params.staleDnsParams)
        assertNull(params.useHttpStackDnsResolver)
        assertNull(params.preestablishConnectionsToStaleDnsResults)
    }

    @Test
    fun testDnsParams_persistHostCache_returnSetValue() {
        val params = DnsParams.Builder().setPersistHostCache(true).build()

        assertNotNull(params.persistHostCache)
        assertTrue(params.persistHostCache!!)
    }

    @Test
    fun testDnsParams_persistHostCachePeriod_returnSetValue() {
        val period = Duration.ofMillis(12345)
        val params = DnsParams.Builder().setPersistHostCachePeriod(period).build()

        assertEquals(period, params.persistHostCachePeriod)
    }

    @Test
    fun testDnsParams_enableStaleDns_returnSetValue() {
        val params = DnsParams.Builder().setEnableStaleDns(true).build()

        assertNotNull(params.enableStaleDns)
        assertTrue(params.enableStaleDns!!)
    }

    @Test
    fun testDnsParams_useHttpStackDnsResolver_returnsSetValue() {
        val params = DnsParams.Builder().setUseHttpStackDnsResolver(true).build()

        assertNotNull(params.useHttpStackDnsResolver)
        assertTrue(params.useHttpStackDnsResolver!!)
    }

    @Test
    fun testDnsParams_preestablishConnectionsToStaleDnsResults_returnsSetValue() {
        val params = DnsParams.Builder().setPreestablishConnectionsToStaleDnsResults(true).build()

        assertNotNull(params.preestablishConnectionsToStaleDnsResults)
        assertTrue(params.preestablishConnectionsToStaleDnsResults!!)
    }

    @Test
    fun testStaleDnsParams_defaultValues() {
        val params = DnsParams.StaleDnsParams.Builder().build()

        assertNull(params.allowCrossNetworkUsage)
        assertNull(params.freshLookupTimeoutMillis)
        assertNull(params.maxExpiredDelayMillis)
        assertNull(params.useStaleOnNameNotResolved)
    }

    @Test
    fun testStaleDnsParams_allowCrossNetworkUsage_returnsSetValue() {
        val params = DnsParams.StaleDnsParams.Builder().setAllowCrossNetworkUsage(true).build()

        assertNotNull(params.allowCrossNetworkUsage)
        assertTrue(params.allowCrossNetworkUsage!!)
    }

    @Test
    fun testStaleDnsParams_freshLookupTimeout_returnsSetValue() {
        val duration = Duration.ofMillis(12345)
        val params = DnsParams.StaleDnsParams.Builder().setFreshLookupTimeout(duration).build()

        assertNotNull(params.freshLookupTimeoutMillis)
        assertEquals(duration.toMillis(), params.freshLookupTimeoutMillis!!)
    }

    @Test
    fun testStaleDnsParams_useStaleOnNameNotResolved_returnsSetValue() {
        val params =
                DnsParams.StaleDnsParams.Builder().setUseStaleOnNameNotResolved(true).build()

        assertNotNull(params.useStaleOnNameNotResolved)
        assertTrue(params.useStaleOnNameNotResolved!!)
    }

    @Test
    fun testStaleDnsParams_maxExpiredDelayMillis_returnsSetValue() {
        val duration = Duration.ofMillis(12345)
        val params = DnsParams.StaleDnsParams.Builder().setMaxExpiredDelay(duration).build()

        assertNotNull(params.maxExpiredDelayMillis)
        assertEquals(duration.toMillis(), params.maxExpiredDelayMillis!!)
    }
}
