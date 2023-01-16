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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.http.cts.util.TestUrlRequestCallback;
import android.net.http.cts.util.TestUrlRequestCallback.ResponseStep;
import android.net.http.cts.util.TestUtilsKt;

import androidx.annotation.NonNull;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.chromium.net.CronetEngine;
import org.chromium.net.UrlRequest;
import org.chromium.net.UrlResponseInfo;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CronetEngineTest {
    private static final String TAG = CronetEngineTest.class.getSimpleName();

    @NonNull private CronetEngine.Builder mEngineBuilder;
    @NonNull private CronetEngine mEngine;
    @NonNull private ConnectivityManager mConnectivityManager;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        TestUtilsKt.skipIfNoInternetConnection(context);
        mEngineBuilder = new CronetEngine.Builder(context);
    }

    @After
    public void tearDown() throws Exception {
        if (mEngine != null) {
            mEngine.shutdown();
        }
    }

    @Test
    public void testCronetEngine_Default() throws Exception {
        final String url = "https://source.android.com";
        mEngine = mEngineBuilder.build();
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder =
                mEngine.newUrlRequestBuilder(url, callback, callback.getExecutor());
        builder.build().start();

        callback.expectCallback(ResponseStep.ON_SUCCEEDED);
        UrlResponseInfo info = callback.mResponseInfo;
        assertEquals(
                "Unexpected http status code from " + url + ".", 200, info.getHttpStatusCode());
        assertEquals("h2", info.getNegotiatedProtocol());
    }

    @Test
    public void testCronetEngine_DisableHttp2() throws Exception {
        final String url = "https://source.android.com";
        mEngine = mEngineBuilder.enableHttp2(false).build();
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder =
                mEngine.newUrlRequestBuilder(url, callback, callback.getExecutor());
        builder.build().start();

        callback.expectCallback(ResponseStep.ON_SUCCEEDED);
        UrlResponseInfo info = callback.mResponseInfo;
        assertEquals(
                "Unexpected http status code from " + url + ".", 200, info.getHttpStatusCode());
        assertEquals("http/1.1", info.getNegotiatedProtocol());
    }

    @Test
    public void testCronetEngine_EnableQuic() throws Exception {
        final String url = "https://source.android.com";
        mEngine =
                mEngineBuilder.enableQuic(true).addQuicHint("source.android.com", 443, 443).build();
        TestUrlRequestCallback callback = new TestUrlRequestCallback();
        UrlRequest.Builder builder =
                mEngine.newUrlRequestBuilder(url, callback, callback.getExecutor());
        builder.build().start();
        callback.expectCallback(ResponseStep.ON_SUCCEEDED);
        UrlResponseInfo info = callback.mResponseInfo;
        assertEquals(
                "Unexpected http status code from " + url + ".", 200, info.getHttpStatusCode());
        assertEquals("h3", info.getNegotiatedProtocol());
    }

    @Test
    public void testCronetEngine_GetDefaultUserAgent() throws Exception {
        assertTrue(mEngineBuilder.getDefaultUserAgent().contains("android.net.http.cts"));
    }
}
