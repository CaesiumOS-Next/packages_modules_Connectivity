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

import static com.android.testutils.ContextUtils.mockService;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.TetheringManager;
import android.net.TetheringManager.TetheringEventCallback;
import android.net.wifi.WifiManager.MulticastLock;
import android.os.Build;

import androidx.annotation.NonNull;

import com.android.server.connectivity.mdns.MdnsSocketManager.Dependencies;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.net.SocketException;
import java.util.List;

@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
public class MdnsSocketManagerTest {
    private static final String TEST_IFACE_NAME = "test";
    private static final String LOCAL_IFACE_NAME = "local";

    @Mock private Context mContext;
    @Mock private MulticastLock mLock;
    @Mock private NetworkInterfaceWrapper mTestNetworkIfaceWrapper;
    @Mock private NetworkInterfaceWrapper mLocalNetworkIfaceWrapper;
    @Mock private MdnsInterfaceSocket mTestSocket;
    @Mock private MdnsInterfaceSocket mLocalSocket;
    @Mock private ConnectivityManager mCm;
    @Mock private TetheringManager mTm;
    private MdnsSocketManager mSocketManager;
    private NetworkCallback mNetworkCallback;
    private TetheringEventCallback mTetheringEventCallback;

    private class TestListener implements MdnsServiceBrowserListener {
        @Override
        public void onServiceFound(@NonNull MdnsServiceInfo serviceInfo) {}

        @Override
        public void onServiceUpdated(@NonNull MdnsServiceInfo serviceInfo) {}

        @Override
        public void onServiceRemoved(@NonNull MdnsServiceInfo serviceInfo) {}

        @Override
        public void onSearchStoppedWithError(int error) {}

        @Override
        public void onSearchFailedToStart() {}

        @Override
        public void onDiscoveryQuerySent(@NonNull List<String> subtypes, int transactionId) {}

        @Override
        public void onFailedToParseMdnsResponse(int receivedPacketNumber, int errorCode) {}

        @Override
        public void onServiceResolved(@NonNull MdnsServiceInfo serviceInfo) {}
    }

    private class TestSocketClient extends MdnsSocketClient {
        TestSocketClient(Context context, MulticastLock multicastLock,
                NetworkInterfaceWrapper networkInterface, Network network) {
            super(context, multicastLock, networkInterface, network);
        }

        @Override
        MdnsSocket createMdnsInterfaceSocket(NetworkInterfaceWrapper networkInterface, int port) {
            if (networkInterface == mTestNetworkIfaceWrapper) {
                return mTestSocket;
            } else if (networkInterface == mLocalNetworkIfaceWrapper) {
                return mLocalSocket;
            }
            return null;
        }
    }

    private class TestDependencies extends Dependencies {
        @Override
        public NetworkInterfaceWrapper getNetworkInterfaceByName(String interfaceName) {
            if (TEST_IFACE_NAME.equals(interfaceName)) {
                return mTestNetworkIfaceWrapper;
            } else if (LOCAL_IFACE_NAME.equals(interfaceName)) {
                return mLocalNetworkIfaceWrapper;
            } else {
                return null;
            }
        }

        @Override
        public boolean canScanOnInterface(NetworkInterfaceWrapper networkInterface) {
            return true;
        }

        @Override
        public MdnsSocketClient createMdnsSocketClient(Context context, MulticastLock multicastLock,
                NetworkInterfaceWrapper networkInterface, Network network) {
            return new TestSocketClient(context, multicastLock, networkInterface, network);
        }
    }

    @Before
    public void setUp() throws SocketException {
        MockitoAnnotations.initMocks(this);
        mockService(mContext, ConnectivityManager.class, Context.CONNECTIVITY_SERVICE, mCm);
        mockService(mContext, TetheringManager.class, Context.TETHERING_SERVICE, mTm);

        final ArgumentCaptor<NetworkCallback> nwCallbackCaptor =
                ArgumentCaptor.forClass(NetworkCallback.class);
        final ArgumentCaptor<TetheringEventCallback> teCallbackCaptor =
                ArgumentCaptor.forClass(TetheringEventCallback.class);
        mSocketManager = new MdnsSocketManager(mContext, mLock, new TestDependencies());
        mSocketManager.startMonitoringMulticastNetworks();
        verify(mCm).registerNetworkCallback(any(NetworkRequest.class), nwCallbackCaptor.capture());
        verify(mTm).registerTetheringEventCallback(any(), teCallbackCaptor.capture());

        mNetworkCallback = nwCallbackCaptor.getValue();
        mTetheringEventCallback = teCallbackCaptor.getValue();
    }

    @Test
    public void testStartMonitorMulticastNetworks() {
        final Network network = new Network(999);
        // No socket client for the network and local interface.
        assertNull(mSocketManager.getSocketClientForNetwork(network));
        assertNull(mSocketManager.getSocketClientForLocalOnlyInterface(LOCAL_IFACE_NAME));

        // Send the LinkProperties change and verify the socket client has been created.
        final LinkProperties lp = new LinkProperties();
        lp.setInterfaceName(TEST_IFACE_NAME);
        mNetworkCallback.onLinkPropertiesChanged(network, lp);
        assertNotNull(mSocketManager.getSocketClientForNetwork(network));

        // Send the local only interface change and verify the socket client has been created.
        mTetheringEventCallback.onLocalOnlyInterfacesChanged(List.of(LOCAL_IFACE_NAME));
        assertNotNull(mSocketManager.getSocketClientForLocalOnlyInterface(LOCAL_IFACE_NAME));
    }

    @Test
    public void testDiscovery() throws IOException {
        final Network network = new Network(999);
        final LinkProperties lp = new LinkProperties();
        lp.setInterfaceName(TEST_IFACE_NAME);
        mNetworkCallback.onLinkPropertiesChanged(network, lp);
        mTetheringEventCallback.onLocalOnlyInterfacesChanged(List.of(LOCAL_IFACE_NAME));

        // Verify socket clients are created but not doing discovery.
        final MdnsSocketClient testSocket = mSocketManager.getSocketClientForNetwork(network);
        final MdnsSocketClient localSocket =
                mSocketManager.getSocketClientForLocalOnlyInterface(LOCAL_IFACE_NAME);
        assertNotNull(testSocket);
        assertNotNull(localSocket);
        assertFalse(testSocket.isDiscovering());
        assertFalse(localSocket.isDiscovering());

        // Verify the discovery starts on given network socket client only.
        final TestListener listener1 = new TestListener();
        mSocketManager.startDiscovery(listener1, network);
        assertTrue(testSocket.isDiscovering());
        assertFalse(localSocket.isDiscovering());

        // Verify the discovery starts on all networks if no target network.
        final TestListener listener2 = new TestListener();
        mSocketManager.startDiscovery(listener2, null);
        assertTrue(testSocket.isDiscovering());
        assertTrue(localSocket.isDiscovering());

        // Verify the discovery is still running on all networks because it still has other request.
        mSocketManager.stopDiscovery(listener1);
        assertTrue(testSocket.isDiscovering());
        assertTrue(localSocket.isDiscovering());

        // Verify the discovery stops on all networks if no matched request.
        mSocketManager.stopDiscovery(listener2);
        assertFalse(testSocket.isDiscovering());
        assertFalse(localSocket.isDiscovering());
    }
}
