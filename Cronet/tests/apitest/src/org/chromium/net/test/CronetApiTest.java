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
package org.chromium.net.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.chromium.net.CallbackException;
import org.chromium.net.CronetEngine;
import org.chromium.net.CronetException;
import org.chromium.net.UrlRequest;
import org.chromium.net.UrlRequest.Status;
import org.chromium.net.UrlRequest.StatusListener;
import org.chromium.net.UrlResponseInfo;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class CronetApiTest {
    private static final String HTTPS_PREFIX = "https://";
    private static final int TIMEOUT_MS = 12_000;
    private static final String TAG = CronetApiTest.class.getSimpleName();
    private final String[] mTestDomains = {"www.google.com", "www.android.com"};
    @NonNull
    private CronetEngine mCronetEngine;
    @NonNull
    private ConnectivityManager mCm;
    @NonNull
    private Executor mExecutor;
    @NonNull
    private String mUrl;

    private static void assertGreaterThan(String msg, int first, int second) {
        assertTrue(msg + " Excepted " + first + " to be greater than " + second, first > second);
    }

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        mCm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        assertHasTestableNetworks();
        CronetEngine.Builder builder = new CronetEngine.Builder(context);
        builder.enableHttpCache(CronetEngine.Builder.HTTP_CACHE_IN_MEMORY, 100 * 1024)
                .enableHttp2(true)
                //.enableBrotli(true)
                .enableQuic(true);
        mCronetEngine = builder.build();
        mExecutor = new Handler(Looper.getMainLooper())::post;
        mUrl = HTTPS_PREFIX + getRandomDomain();
    }

    @Test
    public void testUrlRequestGet_CompletesSuccessfully() throws Exception {
        VerifyUrlRequestCallback callback = new VerifyUrlRequestCallback(mUrl);
        UrlRequest request = mCronetEngine.newUrlRequestBuilder(mUrl, callback, mExecutor).build();
        request.start();
        assertTrue(mUrl + " but not complete after " + TIMEOUT_MS + "ms.",
                callback.waitForAnswer());
    }

    @Test
    public void testUrlRequestStatus_Called() throws Exception {
        VerifyUrlRequestCallback callback = new VerifyUrlRequestCallback(mUrl);
        UrlRequest request = mCronetEngine.newUrlRequestBuilder(mUrl, callback, mExecutor).build();
        // Calling before request is started should give Status.INVALID,
        // since the native adapter is not created.
        TestStatusListener statusListener = new TestStatusListener();
        request.getStatus(statusListener);
        assertTrue(mUrl + " onStatus not called after " + TIMEOUT_MS + "ms.",
                statusListener.waitUntilOnStatusCalled());
    }

    @Test
    public void testUrlRequestStatus_ThrowsExceptions() throws Exception {
        VerifyUrlRequestCallback callback = new VerifyUrlRequestCallback(mUrl) {
            @Override
            public void onReadCompleted(UrlRequest request, UrlResponseInfo info,
                    ByteBuffer byteBuffer) {
                throw new IllegalStateException("Callback error");
            }

            @Override
            public void onFailed(UrlRequest request, UrlResponseInfo info, CronetException error) {
                assertTrue("Error should be a CallbackException",
                        error instanceof CallbackException);
                super.mLatch.countDown();
            }
        };

        UrlRequest request = mCronetEngine.newUrlRequestBuilder(mUrl, callback, mExecutor).build();
        request.start();
        assertTrue(mUrl + " but does not fail after " + TIMEOUT_MS + "ms.",
                callback.waitForAnswer());
    }

    public String[] getmTestDomains() {
        return mTestDomains;
    }

    private void assertHasTestableNetworks() {
        assertNotNull("This test requires a working Internet connection", mCm.getActiveNetwork());
    }

    private String getRandomDomain() {
        int index = (new Random()).nextInt(mTestDomains.length);
        return mTestDomains[index];
    }

    private static class TestStatusListener extends StatusListener {
        private final CountDownLatch mLatch = new CountDownLatch(1);

        @Override
        public void onStatus(int status) {
            assertEquals("Expected Status to be INVALID", Status.INVALID, status);
            mLatch.countDown();
        }

        public boolean waitUntilOnStatusCalled() throws InterruptedException {
            return mLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
    }

    private static class VerifyUrlRequestCallback extends UrlRequest.Callback {
        final CountDownLatch mLatch = new CountDownLatch(1);
        private final String mUrl;

        VerifyUrlRequestCallback(@NonNull String url) {
            this.mUrl = url;
        }

        public boolean waitForAnswer() throws InterruptedException {
            return mLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        @Override
        public void onRedirectReceived(UrlRequest request, UrlResponseInfo info,
                String newLocationUrl) {
            request.followRedirect();
        }

        @Override
        public void onResponseStarted(UrlRequest request, UrlResponseInfo info) {
            request.read(ByteBuffer.allocateDirect(32 * 1024));
        }

        @Override
        public void onReadCompleted(UrlRequest request, UrlResponseInfo info,
                ByteBuffer byteBuffer) {
            byteBuffer.clear();
            request.read(byteBuffer);
        }


        @Override
        public void onSucceeded(UrlRequest request, UrlResponseInfo info) {
            assertEquals("Unexpected http status code from " + mUrl + ".", 200,
                    info.getHttpStatusCode());
            assertGreaterThan("Received byte from " + mUrl + " is 0.",
                    (int) info.getReceivedByteCount(), 0);
            mLatch.countDown();
        }

        @Override
        public void onFailed(UrlRequest request, UrlResponseInfo info, CronetException error) {
            fail(mUrl + error.getMessage());
        }

        @Override
        public void onCanceled(UrlRequest request, UrlResponseInfo info) {
            fail("onCanceled should not be called");
        }
    }
}
