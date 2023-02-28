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

import android.content.Context
import android.net.http.BidirectionalStream
import android.net.http.HttpEngine
import android.net.http.cts.util.TestBidirectionalStreamCallback
import android.net.http.cts.util.assumeOKStatusCode
import android.net.http.cts.util.skipIfNoInternetConnection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith

private const val URL = "https://source.android.com"

/**
 * This tests uses a non-hermetic server. Instead of asserting, assume the next callback. This way,
 * if the request were to fail, the test would just be skipped instead of failing.
 */
@RunWith(AndroidJUnit4::class)
class BidirectionalStreamTest {
    private var mCallback: TestBidirectionalStreamCallback? = null
    private var mHttpEngine: HttpEngine? = null
    private var mStream: BidirectionalStream? = null

    @Before
    @Throws(Exception::class)
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        skipIfNoInternetConnection(context)
        mHttpEngine = HttpEngine.Builder(context).build()
        mCallback = TestBidirectionalStreamCallback()
    }

    @After
    @Throws(Exception::class)
    fun tearDown() {
        // cancel active requests to enable engine shutdown.
        mStream?.let {
            it.cancel()
            mCallback?.blockForDone()
        }
        mHttpEngine?.shutdown()
    }

    private fun createBidirectionalStreamBuilder(url: String): BidirectionalStream.Builder {
        return mHttpEngine!!.newBidirectionalStreamBuilder(url, mCallback, mCallback!!.executor)
    }

    @Test
    @Throws(Exception::class)
    fun testBidirectionalStream_GetStream_CompletesSuccessfully() {
        mStream = createBidirectionalStreamBuilder(URL).setHttpMethod("GET").build()
        mStream!!.start()
        mCallback!!.assumeCallback(TestBidirectionalStreamCallback.ResponseStep.ON_SUCCEEDED)
        val info = mCallback!!.mResponseInfo
        assumeOKStatusCode(info)
        MatcherAssert.assertThat(
            "Received byte count must be > 0", info.receivedByteCount, Matchers.greaterThan(0L))
        assertEquals("h2", info.negotiatedProtocol)
    }
}
