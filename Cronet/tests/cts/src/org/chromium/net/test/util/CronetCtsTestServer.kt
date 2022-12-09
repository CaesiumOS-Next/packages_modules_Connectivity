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

package org.chromium.net.test.util

import android.content.Context
import android.webkit.cts.CtsTestServer
import java.net.URI
import org.apache.http.HttpEntityEnclosingRequest
import org.apache.http.HttpRequest
import org.apache.http.HttpResponse
import org.apache.http.HttpStatus

/** Extends CtsTestServer to handle POST requests and other cronet specific test requests */
class CronetCtsTestServer(context: Context) : CtsTestServer(context) {

    companion object {
        private const val ECHO_BODY_PATH = "/echo_body"
    }

    override fun onPost(request: HttpRequest): HttpResponse? {
        val path: String = URI.create(request.requestLine.uri).path
        var res: HttpResponse? = null

        if (path.startsWith(ECHO_BODY_PATH)) {
            res = createResponse(HttpStatus.SC_OK)
            val req: HttpEntityEnclosingRequest = request as HttpEntityEnclosingRequest
            res.entity = req.entity
            res.addHeader("Content-Length", req.entity.contentLength.toString())
        }

        return res
    }

    fun getEchoBodyUrl(): String {
        return baseUri + ECHO_BODY_PATH
    }

    fun getSuccessUrl(): String {
        return getAssetUrl("html/hello_world.html")
    }
}
