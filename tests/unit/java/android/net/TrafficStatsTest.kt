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

package android.net

import android.net.netstats.StatsResult
import android.net.netstats.TrafficStatsRateLimitCacheConfig
import android.os.Build
import com.android.server.net.NetworkStatsService.TRAFFICSTATS_CLIENT_RATE_LIMIT_CACHE_ENABLED_FLAG
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.com.android.testutils.SetFeatureFlagsRule
import com.android.testutils.com.android.testutils.SetFeatureFlagsRule.FeatureFlag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.HashMap

const val TEST_EXPIRY_DURATION_MS = 1000
const val TEST_IFACE = "wlan0"

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
class TrafficStatsTest {
    private val binder = mock(INetworkStatsService::class.java)
    private val myUid = android.os.Process.myUid()
    private val mockMyUidStatsResult = StatsResult(5L, 6L, 7L, 8L)
    private val mockIfaceStatsResult = StatsResult(7L, 3L, 10L, 21L)
    private val mockTotalStatsResult = StatsResult(8L, 1L, 5L, 2L)

    private val cacheDisabledConfig = TrafficStatsRateLimitCacheConfig.Builder()
            .setIsCacheEnabled(false)
            .setExpiryDurationMs(0)
            .setMaxEntries(0)
            .build()
    private val cacheEnabledConfig = TrafficStatsRateLimitCacheConfig.Builder()
            .setIsCacheEnabled(true)
            .setExpiryDurationMs(TEST_EXPIRY_DURATION_MS)
            .setMaxEntries(100)
            .build()
    private val testClock = TestClock()

    private val featureFlags = HashMap<String, Boolean>()

    // This will set feature flags from @FeatureFlag annotations
    // into the map before setUp() runs.
    @get:Rule
    val setFeatureFlagsRule = SetFeatureFlagsRule(
            { name, enabled -> featureFlags.put(name, enabled == true) },
            { name -> featureFlags.getOrDefault(name, false) }
    )

    class TestClock : Clock() {
        private var currentTimeMillis = 0L

        override fun withZone(zone: ZoneId?): Clock {
            // Not needed.
            return this
        }

        override fun getZone(): ZoneId {
            return ZoneOffset.UTC
        }

        override fun instant(): Instant? {
            return null
        }

        override fun millis(): Long {
            return currentTimeMillis
        }

        fun advanceTime(millis: Int) {
            currentTimeMillis += millis
        }
    }

    @Before
    fun setUp() {
        TrafficStats.setServiceForTest(binder)
        TrafficStats.setClockForTest(testClock)
        doReturn(mockMyUidStatsResult).`when`(binder).getUidStats(myUid)
        doReturn(mockIfaceStatsResult).`when`(binder).getIfaceStats(anyString())
        doReturn(mockTotalStatsResult).`when`(binder).getTotalStats()
        if (featureFlags.getOrDefault(TRAFFICSTATS_CLIENT_RATE_LIMIT_CACHE_ENABLED_FLAG, false)) {
            doReturn(cacheEnabledConfig).`when`(binder).getRateLimitCacheConfig()
        } else {
            doReturn(cacheDisabledConfig).`when`(binder).getRateLimitCacheConfig()
        }
        TrafficStats.reinitRateLimitCacheForTest()
    }

    @After
    fun tearDown() {
        TrafficStats.setServiceForTest(null)
        TrafficStats.setClockForTest(null)
        TrafficStats.reinitRateLimitCacheForTest()
    }

    private fun assertUidStats(uid: Int, stats: StatsResult) {
        assertEquals(stats.rxBytes, TrafficStats.getUidRxBytes(uid))
        assertEquals(stats.rxPackets, TrafficStats.getUidRxPackets(uid))
        assertEquals(stats.txBytes, TrafficStats.getUidTxBytes(uid))
        assertEquals(stats.txPackets, TrafficStats.getUidTxPackets(uid))
    }

    private fun assertIfaceStats(iface: String, stats: StatsResult) {
        assertEquals(stats.rxBytes, TrafficStats.getRxBytes(iface))
        assertEquals(stats.rxPackets, TrafficStats.getRxPackets(iface))
        assertEquals(stats.txBytes, TrafficStats.getTxBytes(iface))
        assertEquals(stats.txPackets, TrafficStats.getTxPackets(iface))
    }

    private fun assertTotalStats(stats: StatsResult) {
        assertEquals(stats.rxBytes, TrafficStats.getTotalRxBytes())
        assertEquals(stats.rxPackets, TrafficStats.getTotalRxPackets())
        assertEquals(stats.txBytes, TrafficStats.getTotalTxBytes())
        assertEquals(stats.txPackets, TrafficStats.getTotalTxPackets())
    }

    @FeatureFlag(name = TRAFFICSTATS_CLIENT_RATE_LIMIT_CACHE_ENABLED_FLAG, enabled = false)
    @Test
    fun testRateLimitCache_cacheDisabled() {
        doTestRateLimitCache(false)
    }

    @FeatureFlag(name = TRAFFICSTATS_CLIENT_RATE_LIMIT_CACHE_ENABLED_FLAG)
    @Test
    fun testRateLimitCache_cacheEnabled() {
        doTestRateLimitCache(true)
    }

    private fun doTestRateLimitCache(cacheEnabled: Boolean) {
        // Initial fetch, verify binder calls.
        assertUidStats(myUid, mockMyUidStatsResult)
        assertIfaceStats(TEST_IFACE, mockIfaceStatsResult)
        assertTotalStats(mockTotalStatsResult)
        var wantedInvocations = if (cacheEnabled) 1 else 4
        verify(binder, times(wantedInvocations)).getUidStats(myUid)
        verify(binder, times(wantedInvocations)).getIfaceStats(TEST_IFACE)
        verify(binder, times(wantedInvocations)).getTotalStats()

        // Advance time within expiry, verify cached values used.
        clearInvocations(binder)
        testClock.advanceTime(TEST_EXPIRY_DURATION_MS)
        assertUidStats(myUid, mockMyUidStatsResult)
        assertIfaceStats(TEST_IFACE, mockIfaceStatsResult)
        assertTotalStats(mockTotalStatsResult)
        wantedInvocations = if (cacheEnabled) 0 else 4
        verify(binder, times(wantedInvocations)).getUidStats(anyInt())
        verify(binder, times(wantedInvocations)).getIfaceStats(anyString())
        verify(binder, times(wantedInvocations)).getTotalStats()

        // Advance time to expire cache, verify new values fetched.
        val secondUidStatsResult = StatsResult(3L, 7L, 10L, 5L)
        val secondIfaceStatsResult = StatsResult(9L, 8L, 7L, 6L)
        val secondTotalStatsResult = StatsResult(4L, 3L, 2L, 1L)
        clearInvocations(binder)
        doReturn(secondUidStatsResult).`when`(binder).getUidStats(myUid)
        doReturn(secondIfaceStatsResult).`when`(binder).getIfaceStats(TEST_IFACE)
        doReturn(secondTotalStatsResult).`when`(binder).getTotalStats()
        testClock.advanceTime(1)
        assertUidStats(myUid, secondUidStatsResult)
        assertIfaceStats(TEST_IFACE, secondIfaceStatsResult)
        assertTotalStats(secondTotalStatsResult)
        wantedInvocations = if (cacheEnabled) 1 else 4
        verify(binder, times(wantedInvocations)).getUidStats(myUid)
        verify(binder, times(wantedInvocations)).getIfaceStats(TEST_IFACE)
        verify(binder, times(wantedInvocations)).getTotalStats()
    }
}
