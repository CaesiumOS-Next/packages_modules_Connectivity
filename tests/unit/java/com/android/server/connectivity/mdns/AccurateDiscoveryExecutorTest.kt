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

package com.android.server.connectivity.mdns

import android.os.Build
import android.os.HandlerThread
import android.testing.TestableLooper
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private const val DEFAULT_TIMEOUT = 2000L

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
class AccurateDiscoveryExecutorTest {
    private val thread = HandlerThread(AccurateDiscoveryExecutorTest::class.simpleName)
            .apply { start() }

    @After
    fun tearDown() {
        thread.quitSafely()
        thread.join()
    }

    @Test
    fun testExecute() {
        val testableLooper = TestableLooper(thread.looper)
        val executor = AccurateDiscoveryExecutor(testableLooper.looper)
        try {
            val future = CompletableFuture<Boolean>()
            executor.execute { future.complete(true) }
            testableLooper.processAllMessages()
            assertTrue(future.isDone)
            assertTrue(future.get(DEFAULT_TIMEOUT, TimeUnit.MILLISECONDS))
        } finally {
            testableLooper.destroy()
        }
    }

    @Test
    fun testExecuteDelayed() {
        val testableLooper = TestableLooper(thread.looper)
        val executor = AccurateDiscoveryExecutor(testableLooper.looper)
        try {
            // Verify the executeDelayed method
            val future = CompletableFuture<Boolean>()
            // Schedule a task with 50ms delay
            executor.executeDelayed({ future.complete(true) }, 30L)
            for (i in 1..5) {
                testableLooper.processAllMessages()
                if (future.isDone) break
                Thread.sleep(10L)
            }
            assertTrue(future.isDone)
            assertTrue(future.get(500L, TimeUnit.MILLISECONDS))
        } finally {
            testableLooper.destroy()
        }
    }
}