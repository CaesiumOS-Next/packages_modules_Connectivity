/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.net.TrafficStats.Dependencies
import android.os.Build
import android.os.Process
import android.util.Log
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*

@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
@RunWith(DevSdkIgnoreRunner::class)
class TrafficStatsTest {

    private lateinit var mockDependencies: Dependencies

    private var currentTime: Long = 0

    @Before
    fun setUp() {
        mockDependencies = spy(Dependencies())
        mockRateLimitCacheSettings()
        TrafficStats.setDependencies(mockDependencies)
    }

    fun mockRateLimitCacheSettings() {
        doAnswer{getCurrentTimeMillis()}.`when`(mockDependencies).getCurrentTimeMillis()
        doReturn(1000).`when`(mockDependencies).getTrafficStatsRateLimitCacheExpiryDuration()
        doReturn(100).`when`(mockDependencies).getTrafficStatsRateLimitCacheMaxEntries()
    }

    @Test
    fun testRateLimitCacheForIfaceStats_On() {
        val delta: Long = 1
        val iface: String = "iface"
        doReturn(true).`when`(mockDependencies).getAlwaysUseTrafficStatsRateLimitCache()

        val trafficTotalStatsAPIs :List<() -> Long> = listOf(
            {TrafficStats.getRxBytes(iface)},
            {TrafficStats.getTxBytes(iface)},
            {TrafficStats.getRxPackets(iface)},
            {TrafficStats.getTxPackets(iface)},
        )

        for (api in trafficTotalStatsAPIs) {
            api()
        }
        verify(mockDependencies, times(1)).getTypelessIfaceStatsFromService(iface)
        incrementCurrentTimeMillis(1000 - delta)
        for (api in trafficTotalStatsAPIs) {
            api()
        }
        verify(mockDependencies, times(1)).getTypelessIfaceStatsFromService(iface)
        incrementCurrentTimeMillis(delta)
        for (api in trafficTotalStatsAPIs) {
            api()
        }
        verify(mockDependencies, times(2)).getTypelessIfaceStatsFromService(iface)
    }
    @Test
    fun testRateLimitCacheForTotalStats_On() {
        val delta: Long = 1
        doReturn(true).`when`(mockDependencies).getAlwaysUseTrafficStatsRateLimitCache()

        val trafficTotalStatsAPIs :List<() -> Long> = listOf(
            {TrafficStats.getTotalRxBytes()},
            {TrafficStats.getTotalTxBytes()},
            {TrafficStats.getTotalRxPackets()},
            {TrafficStats.getTotalTxPackets()},
        )

        for (api in trafficTotalStatsAPIs) {
            api()
        }
        verify(mockDependencies, times(1)).getTypelessTotalStatsFromService()
        incrementCurrentTimeMillis(1000 - delta)
        for (api in trafficTotalStatsAPIs) {
            api()
        }
        verify(mockDependencies, times(1)).getTypelessTotalStatsFromService()
        incrementCurrentTimeMillis(delta)
        for (api in trafficTotalStatsAPIs) {
            api()
        }
        verify(mockDependencies, times(2)).getTypelessTotalStatsFromService()
    }

    @Test
    fun testRateLimitCacheForUidStats_On() {
        val delta: Long = 1
        val myUid: Int = Process.myUid()
        doReturn(true).`when`(mockDependencies).getAlwaysUseTrafficStatsRateLimitCache()

        val trafficUidStatsAPIs :List<() -> Long> = listOf(
            {TrafficStats.getUidRxBytes(myUid)},
            {TrafficStats.getUidTxBytes(myUid)},
            {TrafficStats.getUidRxPackets(myUid)},
            {TrafficStats.getUidTxPackets(myUid)},
        )

        for (api in trafficUidStatsAPIs) {
            api()
        }
        verify(mockDependencies, times(1)).getTypelessUidStatsFromService(myUid)
        incrementCurrentTimeMillis(1000 - delta)
        for (api in trafficUidStatsAPIs) {
            api()
        }
        verify(mockDependencies, times(1)).getTypelessUidStatsFromService(myUid)
        incrementCurrentTimeMillis(delta)
        for (api in trafficUidStatsAPIs) {
            api()
        }
        verify(mockDependencies, times(2)).getTypelessUidStatsFromService(myUid)
    }

    @Test
    fun testRateLimitCacheForTotalStats_Off() {
        val delta: Long = 1
        var numberOfCalls: Int = 0
        doReturn(false).`when`(mockDependencies).getAlwaysUseTrafficStatsRateLimitCache()

        val trafficTotalStatsAPIs :List<() -> Long> = listOf(
            {TrafficStats.getTotalRxBytes()},
            {TrafficStats.getTotalTxBytes()},
            {TrafficStats.getTotalRxPackets()},
            {TrafficStats.getTotalTxPackets()},
        )

        for (api in trafficTotalStatsAPIs) {
            for (i in 1..2) {
                api()
                numberOfCalls += 1
                verify(mockDependencies, times(numberOfCalls)).getTypelessTotalStatsFromService()
                incrementCurrentTimeMillis(delta)
            }
        }
    }

    @Test
    fun testRateLimitCacheForUidStats_Off() {
        var numberOfCalls: Int = 0
        val delta: Long = 1
        val myUid: Int = Process.myUid()
        doReturn(false).`when`(mockDependencies).getAlwaysUseTrafficStatsRateLimitCache()

        val trafficUidStatsAPIs :List<() -> Long> = listOf(
            {TrafficStats.getUidRxBytes(myUid)},
            {TrafficStats.getUidTxBytes(myUid)},
            {TrafficStats.getUidRxPackets(myUid)},
            {TrafficStats.getUidTxPackets(myUid)},
        )

        for (api in trafficUidStatsAPIs) {
            for (i in 1..2) {
                api()
                numberOfCalls += 1
                verify(mockDependencies, times(numberOfCalls)).getTypelessUidStatsFromService(myUid)
                incrementCurrentTimeMillis(delta)
            }
        }
    }
    // Test not valid entries
    //
    @After
    fun tearDown() {
        TrafficStats.setDefaultDependencies()
    }

    private fun getCurrentTimeMillis(): Long {
        return currentTime
    }

    private fun incrementCurrentTimeMillis(delta: Long) {
        currentTime += delta
    }
}
