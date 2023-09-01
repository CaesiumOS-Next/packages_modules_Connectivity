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

package com.android.server.remoteauth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import android.annotation.NonNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.server.remoteauth.connectivity.Connection;
import com.android.server.remoteauth.connectivity.ConnectionException;
import com.android.server.remoteauth.connectivity.ConnectionInfo;
import com.android.server.remoteauth.connectivity.ConnectivityManager;
import com.android.server.remoteauth.connectivity.DiscoveredDeviceReceiver;
import com.android.server.remoteauth.connectivity.DiscoveryFilter;
import com.android.server.remoteauth.connectivity.EventListener;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit test for {@link com.android.server.remoteauth.RemoteAuthConnectionCache} */
@RunWith(AndroidJUnit4.class)
public class RemoteAuthConnectionCacheTest {
    @Mock private Connection mConnection;
    @Mock private ConnectionInfo mConnectionInfo;
    private RemoteAuthConnectionCache mConnectionCache;
    private ConnectivityManager mConnectivityManager;

    private static final int CONNECTION_ID = 1;

    public static class ConnectivityManagerTestImpl implements ConnectivityManager {
        @Override
        public void startDiscovery(
                @NonNull DiscoveryFilter discoveryFilter,
                @NonNull DiscoveredDeviceReceiver discoveredDeviceReceiver) {}

        @Override
        public void stopDiscovery(
                @NonNull DiscoveryFilter discoveryFilter,
                @NonNull DiscoveredDeviceReceiver discoveredDeviceReceiver) {}

        @Override
        public Connection connect(
                @NonNull ConnectionInfo connectionInfo, @NonNull EventListener eventListener) {
            return mConnection;
        }

        @Override
        public void startListening(MessageReceiver messageReceiver) {}

        @Override
        public void stopListening(MessageReceiver messageReceiver) {}
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        doReturn(CONNECTION_ID).when(mConnectionInfo).getConnectionId();
        doReturn(mConnectionInfo).when(mConnection).getConnectionInfo();
        mConnectivityManager = new ConnectivityManagerTestImpl();
        mConnectionCache = new RemoteAuthConnectionCache(mConnectivityManager);
    }

    @Test
    public void testCreateCache_managerIsNull() {
        assertThrows(NullPointerException.class, () -> new RemoteAuthConnectionCache(null));
    }

    @Test
    public void testGetManager_managerExists() {
        assertEquals(mConnectivityManager, mConnectionCache.getConnectivityManager());
    }

    @Test
    public void testSetConnectionInfo_infoIsNull() {
        assertThrows(NullPointerException.class, () -> mConnectionCache.setConnectionInfo(null));
    }

    @Test
    public void testSetConnectionInfo_infoIsValid() {
        mConnectionCache.setConnectionInfo(mConnectionInfo);

        assertEquals(mConnectionInfo, mConnectionCache.getConnectionInfo(CONNECTION_ID));
    }

    @Test
    public void testSetConnection_connectionIsNull() {
        assertThrows(NullPointerException.class, () -> mConnectionCache.setConnection(null));
    }

    @Test
    public void testGetConnection_connectionAlreadyExists() {
        mConnectionCache.setConnection(mConnection);

        assertEquals(mConnection, mConnectionCache.getConnection(CONNECTION_ID));
    }

    @Test
    public void testGetConnection_connectionInfoDoesntExists() {
        assertNull(mConnectionCache.getConnection(CONNECTION_ID));
    }

    @Test
    public void testGetConnection_failedToConnect() {
        mConnectionCache.setConnectionInfo(mConnectionInfo);
        doReturn(null).when(mConnectivityManager).connect(mConnectionInfo, anyObject());

        assertNull(mConnectionCache.getConnection(CONNECTION_ID));
    }

    @Test
    public void testGetConnection_failedToConnectException() {
        mConnectionCache.setConnectionInfo(mConnectionInfo);
        doThrow(ConnectionException.class)
                .when(mConnectivityManager)
                .connect(mConnectionInfo, anyObject());

        assertNull(mConnectionCache.getConnection(CONNECTION_ID));
    }

    @Test
    public void testGetConnection_connectionSucceed() {
        mConnectionCache.setConnectionInfo(mConnectionInfo);
        doReturn(mConnection).when(mConnectivityManager).connect(mConnectionInfo, anyObject());

        assertEquals(mConnection, mConnectionCache.getConnection(CONNECTION_ID));
    }
}
