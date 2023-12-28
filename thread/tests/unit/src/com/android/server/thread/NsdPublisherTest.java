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

import static android.net.nsd.NsdManager.FAILURE_INTERNAL_ERROR;
import static android.net.nsd.NsdManager.PROTOCOL_DNS_SD;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.test.TestLooper;

import com.android.server.thread.openthread.DnsTxtAttribute;
import com.android.server.thread.openthread.INsdStatusReceiver;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Set;

/** Unit tests for {@link NsdPublisher}. */
public final class NsdPublisherTest {
    @Mock private NsdManager mMockNsdManager;

    @Mock private INsdStatusReceiver mRegistrationReceiver;
    @Mock private INsdStatusReceiver mUnregistrationReceiver;

    private TestLooper mTestLooper;
    private NsdPublisher mNsdPublisher;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mTestLooper = new TestLooper();
        Handler handler = new Handler(mTestLooper.getLooper());
        mNsdPublisher = new NsdPublisher(mMockNsdManager, handler);
    }

    @Test
    public void registerService_nsdManagerSucceeds_serviceRegistrationSucceeds() throws Exception {
        DnsTxtAttribute txt1 = new DnsTxtAttribute();
        DnsTxtAttribute txt2 = new DnsTxtAttribute();

        txt1.name = "key1";
        txt1.value = new byte[] {(byte) 0x01, (byte) 0x02};
        txt2.name = "key2";
        txt2.value = new byte[] {(byte) 0x03};

        mNsdPublisher.registerService(
                null,
                "MyService",
                "_test._tcp",
                List.of("_subtype1", "_subtype2"),
                12345,
                List.of(txt1, txt2),
                mRegistrationReceiver,
                16 /* listenerId */);

        mTestLooper.dispatchAll();

        ArgumentCaptor<NsdServiceInfo> actualServiceInfoCaptor =
                ArgumentCaptor.forClass(NsdServiceInfo.class);
        ArgumentCaptor<NsdManager.RegistrationListener> registrationListenerCaptor =
                ArgumentCaptor.forClass(NsdManager.RegistrationListener.class);

        verify(mMockNsdManager, times(1))
                .registerService(
                        actualServiceInfoCaptor.capture(),
                        eq(PROTOCOL_DNS_SD),
                        registrationListenerCaptor.capture());

        NsdServiceInfo actualServiceInfo = actualServiceInfoCaptor.getValue();
        NsdManager.RegistrationListener actualRegistrationListener =
                registrationListenerCaptor.getValue();

        assertThat(actualServiceInfo.getServiceName()).isEqualTo("MyService");
        assertThat(actualServiceInfo.getServiceType()).isEqualTo("_test._tcp");
        assertThat(actualServiceInfo.getSubtypes()).isEqualTo(Set.of("_subtype1", "_subtype2"));
        assertThat(actualServiceInfo.getPort()).isEqualTo(12345);
        assertThat(actualServiceInfo.getAttributes().size()).isEqualTo(2);
        assertThat(actualServiceInfo.getAttributes().get("key1"))
                .isEqualTo(new byte[] {(byte) 0x01, (byte) 0x02});
        assertThat(actualServiceInfo.getAttributes().get("key2"))
                .isEqualTo(new byte[] {(byte) 0x03});

        actualRegistrationListener.onServiceRegistered(actualServiceInfo);
        mTestLooper.dispatchAll();
        verify(mRegistrationReceiver, times(1)).onSuccess();
    }

    @Test
    public void registerService_nsdManagerFails_serviceRegistrationFails() throws Exception {
        DnsTxtAttribute txt1 = new DnsTxtAttribute();
        DnsTxtAttribute txt2 = new DnsTxtAttribute();

        txt1.name = "key1";
        txt1.value = new byte[] {(byte) 0x01, (byte) 0x02};
        txt2.name = "key2";
        txt2.value = new byte[] {(byte) 0x03};

        mNsdPublisher.registerService(
                null,
                "MyService",
                "_test._tcp",
                List.of("_subtype1", "_subtype2"),
                12345,
                List.of(txt1, txt2),
                mRegistrationReceiver,
                16 /* listenerId */);

        mTestLooper.dispatchAll();

        ArgumentCaptor<NsdServiceInfo> actualServiceInfoCaptor =
                ArgumentCaptor.forClass(NsdServiceInfo.class);
        ArgumentCaptor<NsdManager.RegistrationListener> registrationListenerCaptor =
                ArgumentCaptor.forClass(NsdManager.RegistrationListener.class);

        verify(mMockNsdManager, times(1))
                .registerService(
                        actualServiceInfoCaptor.capture(),
                        eq(PROTOCOL_DNS_SD),
                        registrationListenerCaptor.capture());

        NsdServiceInfo actualServiceInfo = actualServiceInfoCaptor.getValue();
        NsdManager.RegistrationListener actualRegistrationListener =
                registrationListenerCaptor.getValue();

        assertThat(actualServiceInfo.getServiceName()).isEqualTo("MyService");
        assertThat(actualServiceInfo.getServiceType()).isEqualTo("_test._tcp");
        assertThat(actualServiceInfo.getSubtypes()).isEqualTo(Set.of("_subtype1", "_subtype2"));
        assertThat(actualServiceInfo.getPort()).isEqualTo(12345);
        assertThat(actualServiceInfo.getAttributes().size()).isEqualTo(2);
        assertThat(actualServiceInfo.getAttributes().get("key1"))
                .isEqualTo(new byte[] {(byte) 0x01, (byte) 0x02});
        assertThat(actualServiceInfo.getAttributes().get("key2"))
                .isEqualTo(new byte[] {(byte) 0x03});

        actualRegistrationListener.onRegistrationFailed(actualServiceInfo, FAILURE_INTERNAL_ERROR);
        mTestLooper.dispatchAll();
        verify(mRegistrationReceiver, times(1)).onError(FAILURE_INTERNAL_ERROR);
    }

    @Test
    public void unregisterService_nsdManagerSucceeds_serviceUnregistrationSucceeds()
            throws Exception {
        DnsTxtAttribute txt1 = new DnsTxtAttribute();
        DnsTxtAttribute txt2 = new DnsTxtAttribute();

        txt1.name = "key1";
        txt1.value = new byte[] {(byte) 0x01, (byte) 0x02};
        txt2.name = "key2";
        txt2.value = new byte[] {(byte) 0x03};

        mNsdPublisher.registerService(
                null,
                "MyService",
                "_test._tcp",
                List.of("_subtype1", "_subtype2"),
                12345,
                List.of(txt1, txt2),
                mRegistrationReceiver,
                16 /* listenerId */);

        mTestLooper.dispatchAll();

        ArgumentCaptor<NsdServiceInfo> actualServiceInfoCaptor =
                ArgumentCaptor.forClass(NsdServiceInfo.class);
        ArgumentCaptor<NsdManager.RegistrationListener> registrationListenerCaptor =
                ArgumentCaptor.forClass(NsdManager.RegistrationListener.class);

        verify(mMockNsdManager, times(1))
                .registerService(
                        actualServiceInfoCaptor.capture(),
                        eq(PROTOCOL_DNS_SD),
                        registrationListenerCaptor.capture());

        NsdServiceInfo actualServiceInfo = actualServiceInfoCaptor.getValue();
        NsdManager.RegistrationListener actualRegistrationListener =
                registrationListenerCaptor.getValue();

        actualRegistrationListener.onServiceRegistered(actualServiceInfo);
        mNsdPublisher.unregisterService(mUnregistrationReceiver, 16 /* listenerId */);
        mTestLooper.dispatchAll();
        verify(mMockNsdManager, times(1)).unregisterService(actualRegistrationListener);

        actualRegistrationListener.onServiceUnregistered(actualServiceInfo);
        mTestLooper.dispatchAll();
        verify(mUnregistrationReceiver, times(1)).onSuccess();
    }

    @Test
    public void unregisterService_nsdManagerFails_serviceUnregistrationFails() throws Exception {
        DnsTxtAttribute txt1 = new DnsTxtAttribute();
        DnsTxtAttribute txt2 = new DnsTxtAttribute();

        txt1.name = "key1";
        txt1.value = new byte[] {(byte) 0x01, (byte) 0x02};
        txt2.name = "key2";
        txt2.value = new byte[] {(byte) 0x03};

        mNsdPublisher.registerService(
                null,
                "MyService",
                "_test._tcp",
                List.of("_subtype1", "_subtype2"),
                12345,
                List.of(txt1, txt2),
                mRegistrationReceiver,
                16 /* listenerId */);

        mTestLooper.dispatchAll();

        ArgumentCaptor<NsdServiceInfo> actualServiceInfoCaptor =
                ArgumentCaptor.forClass(NsdServiceInfo.class);
        ArgumentCaptor<NsdManager.RegistrationListener> registrationListenerCaptor =
                ArgumentCaptor.forClass(NsdManager.RegistrationListener.class);

        verify(mMockNsdManager, times(1))
                .registerService(
                        actualServiceInfoCaptor.capture(),
                        eq(PROTOCOL_DNS_SD),
                        registrationListenerCaptor.capture());

        NsdServiceInfo actualServiceInfo = actualServiceInfoCaptor.getValue();
        NsdManager.RegistrationListener actualRegistrationListener =
                registrationListenerCaptor.getValue();

        actualRegistrationListener.onServiceRegistered(actualServiceInfo);
        mNsdPublisher.unregisterService(mUnregistrationReceiver, 16 /* listenerId */);
        mTestLooper.dispatchAll();
        verify(mMockNsdManager, times(1)).unregisterService(actualRegistrationListener);

        actualRegistrationListener.onUnregistrationFailed(
                actualServiceInfo, FAILURE_INTERNAL_ERROR);
        mTestLooper.dispatchAll();
        verify(mUnregistrationReceiver, times(1)).onError(0);
    }
}
