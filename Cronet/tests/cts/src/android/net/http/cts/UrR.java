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

package android.net.http.cts;

import static android.net.http.cts.util.TestUtilsKt.assertOKStatusCode;
import static android.net.http.cts.util.TestUtilsKt.skipIfNoInternetConnection;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.net.http.HttpEngine;
import android.net.http.UrlRequest;
import android.net.http.UrlResponseInfo;
import android.net.http.cts.util.TestUrlRequestCallback;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.services.storage.TestStorage;

import org.chromium.net.NativeTestServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class UrR {
    private TestUrlRequestCallback mCallback;
    private HttpEngine mHttpEngine;

    @Before
    public void setUp() throws Exception {
        System.loadLibrary("cronet_tests");
        Context context = ApplicationProvider.getApplicationContext();
        assertEquals("", TestStorage.getInputFileUri("success.txt").toString());
        skipIfNoInternetConnection(context);
        HttpEngine.Builder builder = new HttpEngine.Builder(context);
        mHttpEngine = builder.build();
        mCallback = new TestUrlRequestCallback();
        NativeTestServer.startNativeTestServer(context);
    }

    @After
    public void tearDown() throws Exception {
        if (mHttpEngine != null) {
            mHttpEngine.shutdown();
        }
        NativeTestServer.shutdownNativeTestServer();
    }

    private UrlRequest.Builder createUrlRequestBuilder(String url) {
        return mHttpEngine.newUrlRequestBuilder(url, mCallback, mCallback.getExecutor());
    }

    @Test
    public void testUrlRequestGet_CompletesSuccessfully() throws Exception {
        String url = NativeTestServer.getSuccessURL();
        UrlRequest request = createUrlRequestBuilder(url).build();
        request.start();

        mCallback.expectCallback(TestUrlRequestCallback.ResponseStep.ON_SUCCEEDED);
        UrlResponseInfo info = mCallback.mResponseInfo;
        assertOKStatusCode(info);
        assertThat("Received byte count must be > 0", info.getReceivedByteCount(), greaterThan(0L));
    }
}
