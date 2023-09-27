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

package com.android.server.net

import android.os.Build
import android.util.IndentingPrintWriter
import com.android.server.net.NetworkStatsEventLogger.POLL_REASON_GLOBAL_ALERT
import com.android.server.net.NetworkStatsEventLogger.PollEvent
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import java.io.StringWriter
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

const val TEST_PERSIST_FLAG = 0x101

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class NetworkStatsEventLoggerTest {
    @get:Rule
    val ignoreRule = DevSdkIgnoreRule()

    @Test
    fun testDump() {
        val logger = NetworkStatsEventLogger()
        val stringWriter = StringWriter()
        val pw = IndentingPrintWriter(stringWriter)
        // Verify it won't crash.
        logger.dump(pw)

        // Add 1 record, verify the output contains at least necessary information.
        val testEvent = PollEvent(POLL_REASON_GLOBAL_ALERT)
        logger.logPollEvent(TEST_PERSIST_FLAG, testEvent)
        logger.dump(pw)
        val output = stringWriter.toString()
        assertTrue(output.contains(TEST_PERSIST_FLAG.toString()))
        assertTrue(testEvent.toString().contains(POLL_REASON_GLOBAL_ALERT.toString()))
        assertTrue(output.contains(testEvent.toString()))
    }
}
