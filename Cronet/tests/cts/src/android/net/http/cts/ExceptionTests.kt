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

package android.net.http.cts

import android.net.http.CallbackException
import android.net.http.HttpException
import android.net.http.InlineExecutionProhibitedException
import android.net.http.NetworkException
import android.net.http.QuicException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.junit.runner.RunWith

private const val MESSAGE = "failed"
private val CAUSE = Throwable("exception")

@SmallTest
@RunWith(AndroidJUnit4::class)
class ExceptionTests {

    @Test
    fun testCallbackException_returnsInputParameters() {
        val callbackException = object : CallbackException(MESSAGE, CAUSE) {}

        assertEquals(MESSAGE, callbackException.message)
        assertSame(CAUSE, callbackException.cause)
    }

    @Test
    fun testHttpException_returnsInputParameters() {
        val httpException = HttpException(MESSAGE, CAUSE)

        assertEquals(MESSAGE, httpException.message)
        assertSame(CAUSE, httpException.cause)
    }

    @Test
    fun testInlineExecutionProhibitedException_returnsInputParameters() {
        assertEquals(
            "Inline execution is prohibited for this request",
            InlineExecutionProhibitedException().message
        )
    }

    @Test
    fun testNetworkException_returnsInputParameters() {
        val networkException = object : NetworkException(MESSAGE, CAUSE) {
            override fun getErrorCode() = 0
            override fun isImmediatelyRetryable() = false
        }

        assertEquals(MESSAGE, networkException.message)
        assertSame(CAUSE, networkException.cause)
    }

    @Test
    fun testQuicException_returnsInputParameters() {
        val quicException = object : QuicException(MESSAGE, CAUSE) {
            override fun getErrorCode() = 0
            override fun isImmediatelyRetryable() = false
        }

        assertEquals(MESSAGE, quicException.message)
        assertSame(CAUSE, quicException.cause)
    }
}
