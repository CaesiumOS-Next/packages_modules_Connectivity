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
import android.net.http.HttpEngine
import android.net.http.InlineExecutionProhibitedException
import android.net.http.cts.util.HttpCtsTestServer
import android.net.http.cts.util.TestUrlRequestCallback
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InlineExecutionProhibitedExceptionTests {

    @Test
    fun testInlineExecutionProhibitedException_triggeredFromUrlRequest() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val server = HttpCtsTestServer(context)
        val httpEngine = HttpEngine.Builder(context).build()
        val callback = TestUrlRequestCallback()
        callback.setAllowDirectExecutor(true)
        val executor = Executor { command -> command.run() }
        val request = httpEngine.newUrlRequestBuilder(server.successUrl, callback, executor).build()

        request.start()
        callback.blockForDone()

        assertTrue(request.isDone)
        assertIs<InlineExecutionProhibitedException>(callback.mError.cause)
        assertNotNull((callback.mError.cause as InlineExecutionProhibitedException).message)
        server.shutdown()
        httpEngine.shutdown()
    }
}
