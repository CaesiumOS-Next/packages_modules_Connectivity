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

package com.android.server.thread;

import static org.mockito.Mockito.verifyZeroInteractions;

import android.content.Context;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.thread.ThreadNetworkController;
import android.os.test.TestLooper;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link ThreadNetworkFactory}. */
@RunWith(AndroidJUnit4.class)
@SmallTest
public final class ThreadNetworkFactoryTest {
    private Context mContext;
    private TestLooper mTestLooper;
    @Mock private ThreadNetworkController mMockController;

    private ThreadNetworkFactory mFactory;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mContext = ApplicationProvider.getApplicationContext();
        mTestLooper = new TestLooper();
        mFactory =
                new ThreadNetworkFactory(mContext, mTestLooper.getLooper(), () -> mMockController);
    }

    @Test
    public void needNetworkFor_noThreadTransport_doNothing() {
        NetworkRequest request =
                new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .build();

        mFactory.needNetworkFor(request);

        verifyZeroInteractions(mMockController);
    }

    @Test
    public void needNetworkFor_threadTransportAndNoSpecifier_joinIsCalled() {
        NetworkRequest request =
                new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_THREAD)
                        .build();

        mFactory.needNetworkFor(request);

        verify(mMockController).
    }
}
