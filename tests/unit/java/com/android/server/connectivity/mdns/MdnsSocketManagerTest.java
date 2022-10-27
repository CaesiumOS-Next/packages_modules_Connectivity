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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.net.Network;
import android.net.wifi.WifiManager.MulticastLock;
import android.os.Build;

import com.android.server.connectivity.mdns.MdnsSocketManager.Dependencies;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.net.SocketException;
import java.util.List;

@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
public class MdnsSocketManagerTest {
    @Mock private Context mContext;
    @Mock private MulticastLock mLock;
    @Mock private MdnsSocketProvider mProvider;
    @Mock private Dependencies mDeps;
    @Mock private MdnsInterfaceSocket mSocket;
    @Mock private MdnsSocketClient mSocketClient;
    private MdnsSocketManager mSocketManager;

    @Before
    public void setUp() throws SocketException {
        MockitoAnnotations.initMocks(this);
        doReturn(mSocketClient).when(mDeps)
                .createMdnsSocketClient(any(), any(), eq(mSocket), any());

        mSocketManager = new MdnsSocketManager(mContext, mProvider, mLock, mDeps);
    }

    @Test
    public void testSocketCreated() throws IOException {
        final Network network = new Network(999);
        mSocketManager.onSocketCreated(network, mSocket, List.of());
        assertEquals(mSocketClient, mSocketManager.getSocketClient(mSocket));
        verify(mSocketClient, never()).startDiscovery();

        final MdnsServiceBrowserListener listener = mock(MdnsServiceBrowserListener.class);
        mSocketManager.startDiscovery(listener, network);
        mSocketManager.onSocketCreated(network, mSocket, List.of());
        verify(mSocketClient).startDiscovery();
    }

    @Test
    public void testInterfaceDestroyed() throws IOException {
        final Network network = new Network(999);
        final MdnsServiceBrowserListener listener = mock(MdnsServiceBrowserListener.class);
        mSocketManager.startDiscovery(listener, network);
        mSocketManager.onSocketCreated(network, mSocket, List.of());
        verify(mSocketClient).startDiscovery();
        assertEquals(mSocketClient, mSocketManager.getSocketClient(mSocket));

        mSocketManager.onInterfaceDestroyed(network, mock(MdnsInterfaceSocket.class));
        verify(mSocketClient, never()).stopDiscovery();
        assertEquals(mSocketClient, mSocketManager.getSocketClient(mSocket));

        mSocketManager.onInterfaceDestroyed(network, mSocket);
        verify(mSocketClient).stopDiscovery();
        assertNull(mSocketManager.getSocketClient(mSocket));
    }

    @Test
    public void testDiscovery() throws IOException {
        final Network network = new Network(999);
        final MdnsServiceBrowserListener listener = mock(MdnsServiceBrowserListener.class);
        mSocketManager.startDiscovery(listener, network);
        mSocketManager.onSocketCreated(network, mSocket, List.of());
        verify(mSocketClient).startDiscovery();
        assertEquals(mSocketClient, mSocketManager.getSocketClient(mSocket));

        mSocketManager.stopDiscovery(listener);
        verify(mSocketClient).stopDiscovery();
        assertEquals(mSocketClient, mSocketManager.getSocketClient(mSocket));
    }
}
