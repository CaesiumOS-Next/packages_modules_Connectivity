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
// import static org.mockito.Mockito.verify;
// import static org.mockito.Mockito.when;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.server.remoteauth.connectivity.CdmConnectivityModule;
import com.google.android.server.remoteauth.connectivity.ConnectionInfo;
import com.google.android.server.remoteauth.connectivity.ConnectivityModuleImpl;
import com.google.android.server.remoteauth.connectivity.DiscoveredDevice;
import com.google.android.server.remoteauth.connectivity.DiscoveredDeviceReceiver;
import com.google.android.server.remoteauth.connectivity.DiscoveryFilter;
import com.google.common.util.concurrent.SettableFuture;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

// import org.robolectric.RuntimeEnvironment;

/** Unit tests for {@link ConnectivityModuleImpl}. */
@RunWith(AndroidJUnit4.class)
// @RunWith(MockitoJUnitRunner.class)
public class ConnectivityModuleImplTest {
    @Rule public final MockitoRule mocks = MockitoJUnit.rule();

    @Mock CdmConnectivityModule mMockCdmConnectivityModule;

    private static final int FAKE_CONNECTION_ID = 0;
    private static final String FAKE_DISPLAY_NAME = "FAKE-DISPLAY-NAME";
    private static final String FAKE_PEER_ADDRESS = "FAKE-PEER-ADDRESS";

    private Context mContext = InstrumentationRegistry.getInstrumentation().getContext();
    private ConnectivityModuleImpl mConnectivityModule;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mConnectivityModule = new ConnectivityModuleImpl(mContext, mMockCdmConnectivityModule);
    }

    private DiscoveryFilter createFakeDiscoveryFilter() {
        return DiscoveryFilter.Builder.newInstance()
                .setDeviceName("DEVICE_NAME")
                .setDeviceType(DiscoveryFilter.DeviceType.WATCH)
                .setPeerAddress("PEER_ADDRESS")
                .build();
    }

    /** Creates a fake connection info */
    private ConnectionInfo createFakeConnectionInfo() {
        return ConnectionInfo.Builder.newInstance()
                .setConnectionType(ConnectionInfo.ConnectionType.BLUETOOTH)
                .setPeerAddress(FAKE_PEER_ADDRESS)
                .setConnectionId(FAKE_CONNECTION_ID)
                .build();
    }

    /** Create a fake discovered device similar to {@link CdmConnectivityModule}. */
    private DiscoveredDevice createFakeDiscoveredDevice() {
        return DiscoveredDevice.Builder.newInstance()
                .setConnectionInfo(createFakeConnectionInfo())
                .setDisplayName(FAKE_DISPLAY_NAME)
                .build();
    }

    @Test
    public void testStartDiscovery_triggersOnDiscovered() {
        SettableFuture<Boolean> onDiscoveredFuture = SettableFuture.create();
        DiscoveredDevice fakeDiscoveredDevice = createFakeDiscoveredDevice();
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
        }).when(mMockCdmConnectivityModule)
                .startDiscovery(any(DiscoveryFilter.class), any(DiscoveredDeviceReceiver.class));

        mConnectivityModule.startDiscovery(createFakeDiscoveryFilter(), onDiscovered);

        assertTrue(onDiscoveredFuture.isDone());
    }

    @Test
    public void testConnectRequest_returnsConnectionObject() {
        SettableFuture<Boolean> connectionFuture = SettableFuture.create();
        ConnectionInfo fake_connection_info = createFakeConnectionInfo();
        ConnectionImpl connection = new ConnectionImpl();

        //        when(mMockCdmConnectivityModule.connect(fake_connection_info,
        // any(EventListener.class)))
        //                .thenReturn(connection);

        mConnectivityModule.connect(fake_connection_info, /* unused= */ null);
    }
}
