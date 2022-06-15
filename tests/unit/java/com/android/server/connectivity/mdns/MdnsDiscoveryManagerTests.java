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

import static com.android.testutils.DevSdkIgnoreRuleKt.SC_V2;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.text.TextUtils;

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.util.Collections;

/** Tests for {@link MdnsDiscoveryManager}. */
@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(SC_V2)
public class MdnsDiscoveryManagerTests {
    private static final String SERVICE_TYPE_1 = "_googlecast._tcp.local";
    private static final String SERVICE_TYPE_2 = "_test._tcp.local";

    @Mock private ExecutorProvider mExecutorProvider;
    @Mock private MdnsSocketClient mSocketClient;
    @Mock private MdnsServiceTypeClient mServiceTypeClient1;
    @Mock private MdnsServiceTypeClient mServiceTypeClient2;

    @Mock MdnsServiceBrowserListener mListener1;
    @Mock MdnsServiceBrowserListener mListener2;
    private MdnsDiscoveryManager mDiscoveryManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mServiceTypeClient1.getServiceTypeLabels())
                .thenReturn(TextUtils.split(SERVICE_TYPE_1, "\\."));
        when(mServiceTypeClient2.getServiceTypeLabels())
                .thenReturn(TextUtils.split(SERVICE_TYPE_2, "\\."));

        mDiscoveryManager = new MdnsDiscoveryManager(mExecutorProvider, mSocketClient) {
                    @Override
                    MdnsServiceTypeClient createServiceTypeClient(@NonNull String serviceType) {
                        if (serviceType.equals(SERVICE_TYPE_1)) {
                            return mServiceTypeClient1;
                        } else if (serviceType.equals(SERVICE_TYPE_2)) {
                            return mServiceTypeClient2;
                        }
                        return null;
                    }
                };
    }

    @Test
    public void registerListener_unregisterListener() throws IOException {
        mDiscoveryManager.registerListener(
                SERVICE_TYPE_1, mListener1, MdnsSearchOptions.getDefaultOptions());
        verify(mSocketClient).startDiscovery();
        verify(mServiceTypeClient1)
                .startSendAndReceive(mListener1, MdnsSearchOptions.getDefaultOptions());

        when(mServiceTypeClient1.stopSendAndReceive(mListener1)).thenReturn(true);
        mDiscoveryManager.unregisterListener(SERVICE_TYPE_1, mListener1);
        verify(mServiceTypeClient1).stopSendAndReceive(mListener1);
        verify(mSocketClient).stopDiscovery();
    }

    @Test
    public void registerMultipleListeners() throws IOException {
        mDiscoveryManager.registerListener(
                SERVICE_TYPE_1, mListener1, MdnsSearchOptions.getDefaultOptions());
        verify(mSocketClient).startDiscovery();
        verify(mServiceTypeClient1)
                .startSendAndReceive(mListener1, MdnsSearchOptions.getDefaultOptions());

        mDiscoveryManager.registerListener(
                SERVICE_TYPE_2, mListener2, MdnsSearchOptions.getDefaultOptions());
        verify(mServiceTypeClient2)
                .startSendAndReceive(mListener2, MdnsSearchOptions.getDefaultOptions());
    }

    @Test
    public void onResponseReceived() {
        mDiscoveryManager.registerListener(
                SERVICE_TYPE_1, mListener1, MdnsSearchOptions.getDefaultOptions());
        mDiscoveryManager.registerListener(
                SERVICE_TYPE_2, mListener2, MdnsSearchOptions.getDefaultOptions());

        MdnsResponse responseForServiceTypeOne = createMockResponse(SERVICE_TYPE_1);
        mDiscoveryManager.onResponseReceived(responseForServiceTypeOne);
        verify(mServiceTypeClient1).processResponse(responseForServiceTypeOne);

        MdnsResponse responseForServiceTypeTwo = createMockResponse(SERVICE_TYPE_2);
        mDiscoveryManager.onResponseReceived(responseForServiceTypeTwo);
        verify(mServiceTypeClient2).processResponse(responseForServiceTypeTwo);

        MdnsResponse responseForSubtype = createMockResponse("subtype._sub._googlecast._tcp.local");
        mDiscoveryManager.onResponseReceived(responseForSubtype);
        verify(mServiceTypeClient1).processResponse(responseForSubtype);
    }

    private MdnsResponse createMockResponse(String serviceType) {
        MdnsPointerRecord mockPointerRecord = mock(MdnsPointerRecord.class);
        MdnsResponse mockResponse = mock(MdnsResponse.class);
        when(mockResponse.getPointerRecords())
                .thenReturn(Collections.singletonList(mockPointerRecord));
        when(mockPointerRecord.getName()).thenReturn(TextUtils.split(serviceType, "\\."));
        return mockResponse;
    }
}
