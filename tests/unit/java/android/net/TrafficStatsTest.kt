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

import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.TrafficStats.getValueForTypeFromFirstEntry
import android.net.TrafficStats.TYPE_RX_BYTES
import android.net.TrafficStats.UNSUPPORTED
import android.net.TrafficStats.Dependencies
import android.net.netstats.TrafficStatsConfig
import android.os.Build
import org.mockito.Mockito.any
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.*
import kotlin.test.assertEquals
import org.mockito.MockitoAnnotations


private const val TEST_IFACE1 = "test_iface1"

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
class TrafficStatsTest {
    var currentTime: Long = 0
    @Mock
    private lateinit var statsService: INetworkStatsService
    @Mock
    private lateinit var context: Context
    @Mock
    private lateinit var statsManager: NetworkStatsManager


    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        doReturn(Context.NETWORK_STATS_SERVICE).`when`(context)
            .getSystemServiceName(NetworkStatsManager::class.java)
        doReturn(statsManager).`when`(context)
            .getSystemService(Context.NETWORK_STATS_SERVICE)
        doReturn(statsService).`when`(statsManager).getBinder()
        TrafficStats.initDependenciesAndCaches()
        TrafficStats.setDependencies(TestDependencies())
        val trafficStatsConfig: TrafficStatsConfig = TrafficStatsConfig();
        trafficStatsConfig.alwaysUseRateLimitCache = true
        trafficStatsConfig.rateLimitCacheExpiryDuration = 1000
        trafficStatsConfig.rateLimitIfaceCacheMaxEntries = 100
        doReturn(trafficStatsConfig).`when`(statsService).getTrafficStatsConfig()
    }

    @After
    fun tearDown() {

    }

    fun incrementCurrentTimeMillis(delta: Long) {
        currentTime += delta
    }
    @Test
    fun testRateLimitCacheForIfaceStats_On() {
        val delta: Long = 1
        val iface: String = "iface"

        doReturn(NetworkStats(0,0)).`when`(statsService).getTypelessIfaceStats(iface)
        val trafficTotalStatsAPIs :List<() -> Long> = listOf(
            {TrafficStats.getRxBytes(iface)},
            {TrafficStats.getTxBytes(iface)},
            {TrafficStats.getRxPackets(iface)},
            {TrafficStats.getTxPackets(iface)},
        )

        for (api in trafficTotalStatsAPIs) {
            api()
        }
        verify(statsService, times(1)).getTypelessIfaceStats(iface)
        incrementCurrentTimeMillis(1000 - delta)
        for (api in trafficTotalStatsAPIs) {
            api()
        }
        verify(statsService, times(1)).getTypelessIfaceStats(iface)
        incrementCurrentTimeMillis(delta)
        for (api in trafficTotalStatsAPIs) {
            api()
        }
        verify(statsService, times(2)).getTypelessIfaceStats(iface)
    }
    @Test
    fun testGetValueForTypeFromFirstEntry() {
        var stats: NetworkStats = NetworkStats(0, 0)
        // empty stats
        assertEquals(getValueForTypeFromFirstEntry(stats, TYPE_RX_BYTES), UNSUPPORTED.toLong())
        // invalid type
        stats.insertEntry(TEST_IFACE1, 1, 2, 3, 4)
        assertEquals(getValueForTypeFromFirstEntry(stats, 1000), UNSUPPORTED.toLong())
        // valid type
        assertEquals(getValueForTypeFromFirstEntry(stats, TYPE_RX_BYTES), 1)
    }

    private inner class TestDependencies: Dependencies() {
        override fun getCurrentTimeMillis(): Long {
            return currentTime
        }

        override fun getStatsServiceFromDeps(): INetworkStatsService {
            return statsService
        }
    }
}