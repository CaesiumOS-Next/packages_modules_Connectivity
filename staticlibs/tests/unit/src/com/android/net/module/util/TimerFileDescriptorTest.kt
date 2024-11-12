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

package com.android.net.module.util

import android.os.Build
import android.os.ConditionVariable
import android.os.Handler
import android.os.HandlerThread
import androidx.test.filters.SmallTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.waitForIdle
import java.util.concurrent.CompletableFuture
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val DEFAULT_TIMEOUT_MS = 1000L

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
class TimerFileDescriptorTest {
    private val thread = HandlerThread(TimerFileDescriptorTest::class.simpleName).apply { start() }
    private val handler by lazy { Handler(thread.looper) }

    @After
    fun tearDown() {
        thread.quitSafely()
        thread.join()
    }

    @Test
    fun testSetDelayedTask() {
        val delayTime = 100L
        val timerFd = TimerFileDescriptor(handler.looper)
        val cv = ConditionVariable()
        handler.post { timerFd.setDelayedTask({ cv.open() }, delayTime) }
        handler.waitForIdle(DEFAULT_TIMEOUT_MS)
        cv.block(DEFAULT_TIMEOUT_MS)
        handler.post{ timerFd.close() }
        handler.waitForIdle(DEFAULT_TIMEOUT_MS)
    }

    @Test
    fun testCancelTask() {
        val delayTime = 500L
        val timerFd = TimerFileDescriptor(handler.looper)
        val future = CompletableFuture<Boolean>()
        handler.post {
            timerFd.setDelayedTask({ future.complete(true) }, delayTime)
            assertTrue(timerFd.hasDelayedTask())
            timerFd.cancelTask()
            assertFalse(timerFd.hasDelayedTask())
        }
        handler.waitForIdle(DEFAULT_TIMEOUT_MS)
        assertFailsWith<TimeoutException> { future.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        handler.post{ timerFd.close() }
        handler.waitForIdle(DEFAULT_TIMEOUT_MS)
    }
}
