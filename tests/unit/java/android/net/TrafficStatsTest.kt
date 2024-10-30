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

import android.net.NetworkStats.UID_ALL
import android.net.TrafficStats.UNSUPPORTED
import android.net.netstats.StatsResult
import android.os.Build
import android.os.Process.SYSTEM_UID
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
class TrafficStatsTest {
    @Mock
    private lateinit var binder: INetworkStatsService
    private lateinit var originalBinder: INetworkStatsService
    private val myUid = android.os.Process.myUid()
    private val mockStatsResult = StatsResult(1L, 2L, 3L, 4L)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        originalBinder = TrafficStats.replaceServiceForTest(binder)
        doReturn(mockStatsResult).`when`(binder).getUidStats(anyInt())
    }

    @After
    fun tearDown() {
        // The check is needed in order to make the exception in other places
        // visible at the test fail callstack.
        if (this::originalBinder.isInitialized) {
            TrafficStats.replaceServiceForTest(originalBinder)
        }
        TrafficStats.replaceMyUidForTest(UID_ALL)
    }

    private fun assertUidStats(uid: Int, rxBytes: Int, rxPackets: Int,
                               txBytes: Int, txPackets: Int) {
        assertEquals(rxBytes, TrafficStats.getUidRxBytes(uid).toInt())
        assertEquals(rxPackets, TrafficStats.getUidRxPackets(uid).toInt())
        assertEquals(txBytes, TrafficStats.getUidTxBytes(uid).toInt())
        assertEquals(txPackets, TrafficStats.getUidTxPackets(uid).toInt())
    }

    // Verify a normal caller could get a quick UNSUPPORTED result in the TrafficStats
    // without accessing the service if query stats other than itself.
    @Test
    fun testGetUidStats_appCaller() {
        assertUidStats(SYSTEM_UID, UNSUPPORTED, UNSUPPORTED, UNSUPPORTED, UNSUPPORTED)
        assertUidStats(myUid + 1, UNSUPPORTED, UNSUPPORTED, UNSUPPORTED, UNSUPPORTED)
        verify(binder, never()).getUidStats(anyInt())
        assertUidStats(myUid, 1, 2, 3, 4)
    }

    // Verify that callers with SYSTEM_UID can access network
    // stats for other UIDs. While this behavior is not officially documented
    // in the API, it exists for compatibility with existing callers that may
    // rely on it.
    @Test
    fun testGetUidStats_systemCaller() {
        TrafficStats.replaceMyUidForTest(SYSTEM_UID)
        assertUidStats(SYSTEM_UID, 1, 2, 3, 4)
        assertUidStats(myUid, 1, 2, 3, 4)
        assertUidStats(myUid + 1, 1, 2, 3, 4)
    }
}
