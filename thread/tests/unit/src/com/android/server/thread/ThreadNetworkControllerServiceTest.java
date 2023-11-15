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

package com.android.server.thread;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.net.ConnectivityManager;
import android.net.NetworkProvider;
import android.content.Context;
import android.os.HandlerThread;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.server.thread.openthread.IOtDaemon;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link ThreadNetworkControllerService}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public final class ThreadNetworkControllerServiceTest {

    @Mock private IOtDaemon mMockOtDaemon;
    @Mock private ConnectivityManager mMockConnectivityManager;
    @Mock private TunInterfaceController mMockTunIfController;
    private Context mContext;
    private HandlerThread mHandlerThread;
    private ThreadNetworkControllerService mService;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mContext = ApplicationProvider.getApplicationContext();
        mHandlerThread = new HandlerThread("TestHandlerThread");
        mHandlerThread.start();
        assertThat(mHandlerThread.getLooper()).isNotNull();
        NetworkProvider networkProvider =
                new NetworkProvider(mContext, mHandlerThread.getLooper(), "ThreadNetworkProvider");
        mService = new ThreadNetworkControllerService(
            ApplicationProvider.getApplicationContext(),
            mHandlerThread,
            networkProvider,
            () -> mMockOtDaemon,
            mMockConnectivityManager,
            mMockTunIfController);
    }

    @After
    public void tearDown() throws Exception {
        mHandlerThread.quitSafely();
        mHandlerThread.join();
    }

    @Test
    public void initialize_threadTunInterfaceIsCreated() throws Exception {
        mService.initialize();

        verify(mMockTunIfController, times(1)).createTunInterface();
    }
}
