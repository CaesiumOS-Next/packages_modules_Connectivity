/*
 * Copyright (C) 2021 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.connectivity.mdns;

import static com.android.server.connectivity.mdns.util.MdnsUtils.SocketKey;
import static com.android.testutils.DevSdkIgnoreRuleKt.SC_V2;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.net.Network;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.util.Pair;

import com.android.net.module.util.SharedLog;
import com.android.server.connectivity.mdns.MdnsSocketClientBase.SocketCreationCallback;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;
import com.android.testutils.HandlerUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Tests for {@link MdnsDiscoveryManager}. */
@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(SC_V2)
public class MdnsDiscoveryManagerTests {
    private static final long DEFAULT_TIMEOUT = 2000L;
    private static final String SERVICE_TYPE_1 = "_googlecast._tcp.local";
    private static final String SERVICE_TYPE_2 = "_test._tcp.local";
    private static final Network NETWORK_1 = Mockito.mock(Network.class);
    private static final Network NETWORK_2 = Mockito.mock(Network.class);
    private static final SocketKey SOCKET_KEY =
            new SocketKey(null /* network */, 999 /* interfaceIndex */);
    private static final SocketKey SOCKET_KEY_1 =
            new SocketKey(NETWORK_1, 998 /* interfaceIndex */);
    private static final SocketKey SOCKET_KEY_2 =
            new SocketKey(NETWORK_2, 997 /* interfaceIndex */);
    private static final Pair<String, SocketKey> PER_SOCKET_SERVICE_TYPE_1 =
            Pair.create(SERVICE_TYPE_1, SOCKET_KEY);
    private static final Pair<String, SocketKey> PER_SOCKET_SERVICE_TYPE_2 =
            Pair.create(SERVICE_TYPE_2, SOCKET_KEY);
    private static final Pair<String, SocketKey> PER_SOCKET_SERVICE_TYPE_3 =
            Pair.create(SERVICE_TYPE_1, SOCKET_KEY_1);
    private static final Pair<String, SocketKey> PER_SOCKET_SERVICE_TYPE_4 =
            Pair.create(SERVICE_TYPE_2, SOCKET_KEY_2);

    @Mock private ExecutorProvider executorProvider;
    @Mock private MdnsSocketClientBase socketClient;
    @Mock private MdnsServiceTypeClient mockServiceTypeClientOne;
    @Mock private MdnsServiceTypeClient mockServiceTypeClientTwo;
    @Mock private MdnsServiceTypeClient mockServiceTypeClientThree;
    @Mock private MdnsServiceTypeClient mockServiceTypeClientFour;

    @Mock MdnsServiceBrowserListener mockListenerOne;
    @Mock MdnsServiceBrowserListener mockListenerTwo;
    @Mock SharedLog sharedLog;
    private MdnsDiscoveryManager discoveryManager;
    private HandlerThread thread;
    private Handler handler;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        thread = new HandlerThread("MdnsDiscoveryManagerTests");
        thread.start();
        handler = new Handler(thread.getLooper());
        discoveryManager = new MdnsDiscoveryManager(executorProvider, socketClient, sharedLog,
                    thread.getLooper()) {
                    @Override
                    MdnsServiceTypeClient createServiceTypeClient(@NonNull String serviceType,
                            @NonNull SocketKey socketKey) {
                        final Pair<String, SocketKey> perNetworkServiceType =
                                Pair.create(serviceType, socketKey);
                        if (perNetworkServiceType.equals(PER_SOCKET_SERVICE_TYPE_1)) {
                            return mockServiceTypeClientOne;
                        } else if (perNetworkServiceType.equals(PER_SOCKET_SERVICE_TYPE_2)) {
                            return mockServiceTypeClientTwo;
                        } else if (perNetworkServiceType.equals(PER_SOCKET_SERVICE_TYPE_3)) {
                            return mockServiceTypeClientThree;
                        } else if (perNetworkServiceType.equals(PER_SOCKET_SERVICE_TYPE_4)) {
                            return mockServiceTypeClientFour;
                        }
                        return null;
                    }
                };
    }

    @After
    public void tearDown() {
        if (thread != null) {
            thread.quitSafely();
        }
    }

    private void runOnHandler(Runnable r) {
        handler.post(r);
        HandlerUtils.waitForIdle(handler, DEFAULT_TIMEOUT);
    }

    private SocketCreationCallback expectSocketCreationCallback(String serviceType,
            MdnsServiceBrowserListener listener, MdnsSearchOptions options) throws IOException {
        final ArgumentCaptor<SocketCreationCallback> callbackCaptor =
                ArgumentCaptor.forClass(SocketCreationCallback.class);
        runOnHandler(() -> discoveryManager.registerListener(serviceType, listener, options));
        verify(socketClient).startDiscovery();
        verify(socketClient).notifyNetworkRequested(
                eq(listener), eq(options.getNetwork()), callbackCaptor.capture());
        return callbackCaptor.getValue();
    }

    @Test
    public void registerListener_unregisterListener() throws IOException {
        final MdnsSearchOptions options =
                MdnsSearchOptions.newBuilder().setNetwork(null /* network */).build();
        final SocketCreationCallback callback = expectSocketCreationCallback(
                SERVICE_TYPE_1, mockListenerOne, options);
        runOnHandler(() -> callback.onSocketCreated(SOCKET_KEY));
        verify(mockServiceTypeClientOne).startSendAndReceive(mockListenerOne, options);

        when(mockServiceTypeClientOne.stopSendAndReceive(mockListenerOne)).thenReturn(true);
        runOnHandler(() -> discoveryManager.unregisterListener(SERVICE_TYPE_1, mockListenerOne));
        verify(mockServiceTypeClientOne).stopSendAndReceive(mockListenerOne);
        verify(socketClient).stopDiscovery();
    }

    @Test
    public void registerMultipleListeners() throws IOException {
        final MdnsSearchOptions options =
                MdnsSearchOptions.newBuilder().setNetwork(null /* network */).build();
        final SocketCreationCallback callback = expectSocketCreationCallback(
                SERVICE_TYPE_1, mockListenerOne, options);
        runOnHandler(() -> callback.onSocketCreated(SOCKET_KEY));
        verify(mockServiceTypeClientOne).startSendAndReceive(mockListenerOne, options);
        runOnHandler(() -> callback.onSocketCreated(SOCKET_KEY_1));
        verify(mockServiceTypeClientThree).startSendAndReceive(mockListenerOne, options);

        final SocketCreationCallback callback2 = expectSocketCreationCallback(
                SERVICE_TYPE_2, mockListenerTwo, options);
        runOnHandler(() -> callback2.onSocketCreated(SOCKET_KEY));
        verify(mockServiceTypeClientTwo).startSendAndReceive(mockListenerTwo, options);
        runOnHandler(() -> callback2.onSocketCreated(SOCKET_KEY_2));
        verify(mockServiceTypeClientFour).startSendAndReceive(mockListenerTwo, options);
    }

    @Test
    public void onResponseReceived() throws IOException {
        final MdnsSearchOptions options1 =
                MdnsSearchOptions.newBuilder().setNetwork(null /* network */).build();
        final SocketCreationCallback callback = expectSocketCreationCallback(
                SERVICE_TYPE_1, mockListenerOne, options1);
        runOnHandler(() -> callback.onSocketCreated(SOCKET_KEY));
        verify(mockServiceTypeClientOne).startSendAndReceive(mockListenerOne, options1);
        runOnHandler(() -> callback.onSocketCreated(SOCKET_KEY_1));
        verify(mockServiceTypeClientThree).startSendAndReceive(mockListenerOne, options1);

        final MdnsSearchOptions options2 =
                MdnsSearchOptions.newBuilder().setNetwork(NETWORK_2).build();
        final SocketCreationCallback callback2 = expectSocketCreationCallback(
                SERVICE_TYPE_2, mockListenerTwo, options2);
        runOnHandler(() -> callback2.onSocketCreated(SOCKET_KEY_2));
        verify(mockServiceTypeClientFour).startSendAndReceive(mockListenerTwo, options2);

        final MdnsPacket responseForServiceTypeOne = createMdnsPacket(SERVICE_TYPE_1);
        runOnHandler(() -> discoveryManager.onResponseReceived(
                responseForServiceTypeOne, SOCKET_KEY));
        verify(mockServiceTypeClientOne).processResponse(
                responseForServiceTypeOne, 999 /* interfaceIndex */, null /* network */);
        verify(mockServiceTypeClientThree, never()).processResponse(any(), anyInt(), any());
        verify(mockServiceTypeClientFour, never()).processResponse(any(), anyInt(), any());

        final MdnsPacket responseForServiceTypeTwo = createMdnsPacket(SERVICE_TYPE_2);
        runOnHandler(() -> discoveryManager.onResponseReceived(
                responseForServiceTypeTwo, SOCKET_KEY_1));
        verify(mockServiceTypeClientOne, never()).processResponse(
                responseForServiceTypeTwo, 998 /* interfaceIndex */, NETWORK_1);
        verify(mockServiceTypeClientThree).processResponse(
                responseForServiceTypeTwo, 998 /* interfaceIndex */, NETWORK_1);
        verify(mockServiceTypeClientFour, never()).processResponse(any(), anyInt(), any());

        final MdnsPacket responseForSubtype =
                createMdnsPacket("subtype._sub._googlecast._tcp.local");
        runOnHandler(() -> discoveryManager.onResponseReceived(
                responseForSubtype, SOCKET_KEY_2));
        verify(mockServiceTypeClientOne, never()).processResponse(
                responseForSubtype, 997 /* interfaceIndex */, NETWORK_2);
        verify(mockServiceTypeClientThree, never()).processResponse(
                responseForSubtype, 997 /* interfaceIndex */, NETWORK_2);
        verify(mockServiceTypeClientFour).processResponse(
                responseForSubtype, 997 /* interfaceIndex */, NETWORK_2);
    }

    @Test
    public void testSocketCreatedAndDestroyed() throws IOException {
        // Create a ServiceTypeClient for SERVICE_TYPE_1 and NETWORK_1
        final MdnsSearchOptions options1 =
                MdnsSearchOptions.newBuilder().setNetwork(NETWORK_1).build();
        final SocketCreationCallback callback = expectSocketCreationCallback(
                SERVICE_TYPE_1, mockListenerOne, options1);
        runOnHandler(() -> callback.onSocketCreated(SOCKET_KEY_1));
        verify(mockServiceTypeClientThree).startSendAndReceive(mockListenerOne, options1);

        // Create a ServiceTypeClient for SERVICE_TYPE_2 and NETWORK_2
        final MdnsSearchOptions options2 =
                MdnsSearchOptions.newBuilder().setNetwork(NETWORK_2).build();
        final SocketCreationCallback callback2 = expectSocketCreationCallback(
                SERVICE_TYPE_2, mockListenerTwo, options2);
        runOnHandler(() -> callback2.onSocketCreated(SOCKET_KEY_2));
        verify(mockServiceTypeClientFour).startSendAndReceive(mockListenerTwo, options2);

        // Receive responses, it should be processed on both clients.
        final MdnsPacket response = createMdnsPacket(SERVICE_TYPE_1);
        runOnHandler(() -> discoveryManager.onResponseReceived(response, SOCKET_KEY_1));
        runOnHandler(() -> discoveryManager.onResponseReceived(response, SOCKET_KEY_2));
        verify(mockServiceTypeClientThree).processResponse(
                response, 998 /* interfaceIndex */, NETWORK_1);
        verify(mockServiceTypeClientFour).processResponse(
                response, 997 /* interfaceIndex */, NETWORK_2);

        // The client for NETWORK_1 receives the callback that the NETWORK_1 has been destroyed,
        // mockServiceTypeClientOne1 should send service removed notifications and remove from the
        // list of clients.
        runOnHandler(() -> callback.onAllSocketsDestroyed(SOCKET_KEY_1));
        verify(mockServiceTypeClientThree).notifySocketDestroyed();

        // Receive responses again, it should be processed only on mockServiceTypeClientFour.
        // Because the mockServiceTypeClientThree is removed from the list of clients, it is no
        // longer able to process responses.
        runOnHandler(() -> discoveryManager.onResponseReceived(response, SOCKET_KEY_1));
        runOnHandler(() -> discoveryManager.onResponseReceived(response, SOCKET_KEY_2));
        verify(mockServiceTypeClientThree, times(1))
                .processResponse(response, 998 /* interfaceIndex */, NETWORK_1);
        verify(mockServiceTypeClientFour, times(2))
                .processResponse(response, 997 /* interfaceIndex */, NETWORK_2);

        // The client for NETWORK_2 receives the callback that the NETWORK_1 has been destroyed,
        // mockServiceTypeClientTwo2 shouldn't send any notifications.
        runOnHandler(() -> callback2.onAllSocketsDestroyed(SOCKET_KEY_1));
        verify(mockServiceTypeClientFour, never()).notifySocketDestroyed();

        // Receive responses again, mockServiceTypeClientTwo2 is still in the list of clients, it's
        // still able to process responses.
        runOnHandler(() -> discoveryManager.onResponseReceived(response, SOCKET_KEY_1));
        runOnHandler(() -> discoveryManager.onResponseReceived(response, SOCKET_KEY_2));
        verify(mockServiceTypeClientThree, times(1))
                .processResponse(response, 998 /* interfaceIndex */, NETWORK_1);
        verify(mockServiceTypeClientFour, times(3))
                .processResponse(response, 997 /* interfaceIndex */, NETWORK_2);
    }

    private MdnsPacket createMdnsPacket(String serviceType) {
        final String[] type = TextUtils.split(serviceType, "\\.");
        final ArrayList<String> name = new ArrayList<>(type.length + 1);
        name.add("TestName");
        name.addAll(Arrays.asList(type));
        return new MdnsPacket(0 /* flags */,
                Collections.emptyList() /* questions */,
                List.of(new MdnsPointerRecord(
                        type,
                        0L /* receiptTimeMillis */,
                        false /* cacheFlush */,
                        120000 /* ttlMillis */,
                        name.toArray(new String[0])
                        )) /* answers */,
                Collections.emptyList() /* authorityRecords */,
                Collections.emptyList() /* additionalRecords */);
    }
}