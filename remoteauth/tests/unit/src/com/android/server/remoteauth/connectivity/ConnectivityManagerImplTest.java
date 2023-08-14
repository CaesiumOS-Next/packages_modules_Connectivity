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

package com.android.server.remoteauth.connectivity;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.server.remoteauth.connectivity.CdmConnectivityManager;
import com.google.android.server.remoteauth.connectivity.ConnectivityManagerImpl;
import com.google.android.server.remoteauth.connectivity.DiscoveredDevice;
import com.google.android.server.remoteauth.connectivity.DiscoveredDeviceReceiver;
import com.google.android.server.remoteauth.connectivity.DiscoveryFilter;
import com.google.common.util.concurrent.SettableFuture;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link ConnectivityManagerImpl}. */
@RunWith(AndroidJUnit4.class)
public class ConnectivityManagerImplTest {
    @Rule public final MockitoRule mocks = MockitoJUnit.rule();

    @Mock CdmConnectivityManager mCdmConnectivityManager;

    private Context mContext;
    private ConnectivityManagerImpl mConnectivityManagerImpl;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        mConnectivityManagerImpl = new ConnectivityManagerImpl(mContext, mCdmConnectivityManager);
    }

    @Test
    public void testStartDiscovery_triggersOnDiscovered() {
        SettableFuture<Boolean> onDiscoveredFuture = SettableFuture.create();
        DiscoveredDevice fakeDiscoveredDevice = Utils.getFakeDiscoveredDevice();
        DiscoveredDeviceReceiver onDiscovered =
                new DiscoveredDeviceReceiver() {
                    @Override
                    public void onDiscovered(DiscoveredDevice discoveredDevice) {
                        if (discoveredDevice.equals(fakeDiscoveredDevice)) {
                            onDiscoveredFuture.set(true);
                        }
                    }
                };

        doAnswer(invocation -> {
            DiscoveredDeviceReceiver cb = invocation.getArgument(1);
            cb.onDiscovered(fakeDiscoveredDevice);
            return null;
        }).when(mCdmConnectivityManager)
                .startDiscovery(any(DiscoveryFilter.class), any(DiscoveredDeviceReceiver.class));

        mConnectivityManagerImpl.startDiscovery(Utils.getFakeDiscoveryFilter(), onDiscovered);

        assertTrue(onDiscoveredFuture.isDone());
    }

    @Test
    public void testMultipleStartDiscovery_callsCdmDiscovery() {
        DiscoveryFilter fakeDiscoveryFilter = Utils.getFakeDiscoveryFilter();
        DiscoveredDeviceReceiver callback1 = Utils.getFakeDiscoveredDeviceReceiver();
        DiscoveredDeviceReceiver callback2 = Utils.getFakeDiscoveredDeviceReceiver();

        mConnectivityManagerImpl.startDiscovery(fakeDiscoveryFilter, callback1);
        mConnectivityManagerImpl.startDiscovery(fakeDiscoveryFilter, callback2);

        verify(mCdmConnectivityManager, times(2))
                .startDiscovery(any(DiscoveryFilter.class),
                        any(DiscoveredDeviceReceiver.class));
    }
}
