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

import android.net.TrafficStats.getEntryValueForType
import android.net.TrafficStats.TYPE_RX_BYTES
import android.net.TrafficStats.TYPE_RX_PACKETS
import android.net.TrafficStats.TYPE_TX_BYTES
import android.net.TrafficStats.TYPE_TX_PACKETS
import android.net.TrafficStats.UNSUPPORTED
import android.net.netstats.StatsResult
import android.os.Build
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

private const val TEST_IFACE1 = "test_iface1"

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
class TrafficStatsTest {

    @Test
    fun testGetEntryValueForType() {
        var stats = StatsResult(1, 2, 3, 4)
        // empty stats
        assertEquals(getEntryValueForType(null, TYPE_RX_BYTES), UNSUPPORTED.toLong())
        // invalid type
        assertEquals(getEntryValueForType(stats, 1000), UNSUPPORTED.toLong())
        // valid type
        assertEquals(getEntryValueForType(stats, TYPE_RX_BYTES), 1)
        assertEquals(getEntryValueForType(stats, TYPE_RX_PACKETS), 2)
        assertEquals(getEntryValueForType(stats, TYPE_TX_BYTES), 3)
        assertEquals(getEntryValueForType(stats, TYPE_TX_PACKETS), 4)
    }
}