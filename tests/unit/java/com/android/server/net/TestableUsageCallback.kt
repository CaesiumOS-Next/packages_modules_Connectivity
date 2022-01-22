/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.net.DataUsageRequest
import android.net.netstats.IUsageCallback
import android.os.IBinder
import com.android.net.module.util.ArrayTrackRecord
import kotlin.test.assertEquals
import kotlin.test.fail

private const val DEFAULT_TIMEOUT_MS = 200L

open class TestableUsageCallback(private val binder: IBinder) : IUsageCallback.Stub() {
    sealed class CallbackType {
        object OnThresholdReached : CallbackType()
        object OnCallbackReleased : CallbackType()
    }

    private val history = ArrayTrackRecord<CallbackType>().ReadHead()

    override fun onThresholdReached(request: DataUsageRequest) {
        history.add(CallbackType.OnThresholdReached)
    }

    override fun onCallbackReleased(request: DataUsageRequest) {
        history.add(CallbackType.OnCallbackReleased)
    }

    fun expectOnThresholdReached() {
        assertEquals(CallbackType.OnThresholdReached, history.poll(DEFAULT_TIMEOUT_MS))
    }

    fun expectOnCallbackReleased() {
        assertEquals(CallbackType.OnCallbackReleased, history.poll(DEFAULT_TIMEOUT_MS))
    }

    @JvmOverloads
    fun assertNoCallback(timeout: Long = DEFAULT_TIMEOUT_MS) {
        val cb = history.poll(timeout)
        cb?.let { fail("Expected no callback but got $cb") }
    }

    override fun asBinder(): IBinder {
        return binder
    }
}