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

package com.android.server.connectivity.mdns;

import static com.android.server.connectivity.mdns.MdnsSocketProvider.SocketCallback;

import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.net.Network;
import android.net.wifi.WifiManager.MulticastLock;
import android.os.Build;
import android.os.HandlerThread;

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.SocketException;
import java.util.List;

@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
public class MdnsSocketManagerTest {
    private static final Network NETWORK = mock(Network.class);
    @Mock private MulticastLock mLock;
    @Mock private MdnsSocketProvider mProvider;
    @Mock private MdnsInterfaceSocket mSocket;
    private MdnsSocketManager mSocketManager;

    @Before
    public void setUp() throws SocketException {
        MockitoAnnotations.initMocks(this);
        final HandlerThread thread = new HandlerThread("MdnsSocketManagerTest");
        thread.start();

        mSocketManager = new MdnsSocketManager(thread.getLooper(), mProvider, mLock);
    }

    @Test
    public void testDiscovery() {
        final MdnsServiceBrowserListener listener = mock(MdnsServiceBrowserListener.class);
        final ArgumentCaptor<SocketCallback> callbackCaptor =
                ArgumentCaptor.forClass(SocketCallback.class);
        mSocketManager.startDiscovery(listener, NETWORK);
        verify(mProvider).requestSocket(eq(NETWORK), callbackCaptor.capture());
        final SocketCallback callback = callbackCaptor.getValue();
        callback.onSocketCreated(NETWORK, mSocket, List.of());
    }
}
