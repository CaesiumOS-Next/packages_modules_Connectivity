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

import android.os.ConditionVariable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import androidx.test.filters.SmallTest
import com.android.net.module.util.TimerFileDescriptor.MessageTask
import com.android.net.module.util.TimerFileDescriptor.RunnableTask
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.tryTest
import com.android.testutils.visibleOnHandlerThread
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val DEFAULT_TIMEOUT_MS = 20L
private const val MSG_TEST = 1

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
class TimerFileDescriptorTest {
    private class TestHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            val cv = msg.obj as ConditionVariable
            cv.open()
        }
    }
    private val thread = HandlerThread(TimerFileDescriptorTest::class.simpleName).apply { start() }
    private val handler by lazy { TestHandler(thread.looper) }

    @After
    fun tearDown() {
        thread.quitSafely()
        thread.join()
    }

    @Test
    fun testSetDelayedTask() {
        val delayTime = 10L
        val timerFd = TimerFileDescriptor(handler)
        tryTest {
            // Verify the delayed task is executed with the self-implemented ITask
            val cv1 = ConditionVariable()
            handler.post { timerFd.setDelayedTask({ cv1.open() }, delayTime) }
            assertTrue(cv1.block(DEFAULT_TIMEOUT_MS))

            // Verify the delayed task is executed with the RunnableTask
            val cv2 = ConditionVariable()
            handler.post { timerFd.setDelayedTask(RunnableTask{ cv2.open() }, delayTime) }
            assertTrue(cv2.block(DEFAULT_TIMEOUT_MS))

            // Verify the delayed task is executed with the MessageTask
            val cv3 = ConditionVariable()
            handler.post { timerFd.setDelayedTask(
                    MessageTask(handler.obtainMessage(MSG_TEST, cv3)), delayTime) }
            assertTrue(cv3.block(DEFAULT_TIMEOUT_MS))
        } cleanup {
            visibleOnHandlerThread(handler) { timerFd.close() }
        }
    }

    @Test
    fun testCancelTask() {
        val delayTime = 10L
        val timerFd = TimerFileDescriptor(handler)
        val cv = ConditionVariable()
        tryTest {
            handler.post {
                timerFd.setDelayedTask({ cv.open() }, delayTime)
                assertTrue(timerFd.hasDelayedTask())
                timerFd.cancelTask()
                assertFalse(timerFd.hasDelayedTask())
            }
            assertFalse(cv.block(DEFAULT_TIMEOUT_MS))
        } cleanup {
            visibleOnHandlerThread(handler) { timerFd.close() }
        }
    }
}
