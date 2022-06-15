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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.content.Context;

import androidx.test.InstrumentationRegistry;

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Tests for {@link MulticastNetworkInterfaceProvider}. */
@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(SC_V2)
public class MulticastNetworkInterfaceProviderTests {
    @Mock private NetworkInterfaceWrapper mLoopbackInterface;
    @Mock private NetworkInterfaceWrapper mPointToPointInterface;
    @Mock private NetworkInterfaceWrapper mVirtualInterface;
    @Mock private NetworkInterfaceWrapper mInactiveMulticastInterface;
    @Mock private NetworkInterfaceWrapper mActiveIpv6MulticastInterface1;
    @Mock private NetworkInterfaceWrapper mActiveIpv6MulticastInterface2;
    @Mock private NetworkInterfaceWrapper mNonMulticastInterface;
    @Mock private NetworkInterfaceWrapper mMulticastInterface1;
    @Mock private NetworkInterfaceWrapper mMulticastInterface2;

    private final List<NetworkInterfaceWrapper> mNetworkInterfaces = new ArrayList<>();
    private MulticastNetworkInterfaceProvider mProvider;
    private Context mContext;

    @Before
    public void setUp() throws SocketException, UnknownHostException {
        MockitoAnnotations.initMocks(this);
        mContext = InstrumentationRegistry.getContext();

        setupNetworkInterface(
                mLoopbackInterface,
                true /* isUp */,
                true /* isLoopBack */,
                false /* isPointToPoint */,
                false /* isVirtual */,
                true /* supportsMulticast */,
                false /* isIpv6 */);

        setupNetworkInterface(
                mPointToPointInterface,
                true /* isUp */,
                false /* isLoopBack */,
                true /* isPointToPoint */,
                false /* isVirtual */,
                true /* supportsMulticast */,
                false /* isIpv6 */);

        setupNetworkInterface(
                mVirtualInterface,
                true /* isUp */,
                false /* isLoopBack */,
                false /* isPointToPoint */,
                true /* isVirtual */,
                true /* supportsMulticast */,
                false /* isIpv6 */);

        setupNetworkInterface(
                mInactiveMulticastInterface,
                false /* isUp */,
                false /* isLoopBack */,
                false /* isPointToPoint */,
                false /* isVirtual */,
                true /* supportsMulticast */,
                false /* isIpv6 */);

        setupNetworkInterface(
                mNonMulticastInterface,
                true /* isUp */,
                false /* isLoopBack */,
                false /* isPointToPoint */,
                false /* isVirtual */,
                false /* supportsMulticast */,
                false /* isIpv6 */);

        setupNetworkInterface(
                mActiveIpv6MulticastInterface1,
                true /* isUp */,
                false /* isLoopBack */,
                false /* isPointToPoint */,
                false /* isVirtual */,
                true /* supportsMulticast */,
                true /* isIpv6 */);

        setupNetworkInterface(
                mActiveIpv6MulticastInterface2,
                true /* isUp */,
                false /* isLoopBack */,
                false /* isPointToPoint */,
                false /* isVirtual */,
                true /* supportsMulticast */,
                true /* isIpv6 */);

        setupNetworkInterface(
                mMulticastInterface1,
                true /* isUp */,
                false /* isLoopBack */,
                false /* isPointToPoint */,
                false /* isVirtual */,
                true /* supportsMulticast */,
                false /* isIpv6 */);

        setupNetworkInterface(
                mMulticastInterface2,
                true /* isUp */,
                false /* isLoopBack */,
                false /* isPointToPoint */,
                false /* isVirtual */,
                true /* supportsMulticast */,
                false /* isIpv6 */);

        mProvider =
                new MulticastNetworkInterfaceProvider(mContext) {
                    @Override
                    List<NetworkInterfaceWrapper> getNetworkInterfaces() {
                        return mNetworkInterfaces;
                    }
                };
    }

    @Test
    public void testGetMulticastNetworkInterfaces() {
        // getNetworkInterfaces returns 1 multicast interface and 5 interfaces that can not be used
        // to send and receive multicast packets.
        mNetworkInterfaces.add(mLoopbackInterface);
        mNetworkInterfaces.add(mPointToPointInterface);
        mNetworkInterfaces.add(mVirtualInterface);
        mNetworkInterfaces.add(mInactiveMulticastInterface);
        mNetworkInterfaces.add(mNonMulticastInterface);
        mNetworkInterfaces.add(mMulticastInterface1);

        assertEquals(Collections.singletonList(mMulticastInterface1),
                mProvider.getMulticastNetworkInterfaces());

        // getNetworkInterfaces returns 2 multicast interfaces after a connectivity change.
        mNetworkInterfaces.clear();
        mNetworkInterfaces.add(mMulticastInterface1);
        mNetworkInterfaces.add(mMulticastInterface2);

        mProvider.connectivityMonitor.notifyConnectivityChange();

        assertEquals(mNetworkInterfaces, mProvider.getMulticastNetworkInterfaces());
    }

    @Test
    public void testStartWatchingConnectivityChanges() {
        ConnectivityMonitor mockMonitor = mock(ConnectivityMonitor.class);
        mProvider.connectivityMonitor = mockMonitor;

        InOrder inOrder = inOrder(mockMonitor);

        mProvider.startWatchingConnectivityChanges();
        inOrder.verify(mockMonitor).startWatchingConnectivityChanges();

        mProvider.stopWatchingConnectivityChanges();
        inOrder.verify(mockMonitor).stopWatchingConnectivityChanges();
    }

    @Test
    public void testIpV6OnlyNetwork_EmptyNetwork() {
        // getNetworkInterfaces returns no network interfaces.
        assertFalse(mProvider.isOnIpV6OnlyNetwork(mNetworkInterfaces));
    }

    @Test
    public void testIpV6OnlyNetwork_IPv4Only() {
        // getNetworkInterfaces returns two IPv4 network interface.
        mNetworkInterfaces.add(mMulticastInterface1);
        mNetworkInterfaces.add(mMulticastInterface2);
        assertFalse(mProvider.isOnIpV6OnlyNetwork(mNetworkInterfaces));
    }

    @Test
    public void testIpV6OnlyNetwork_MixedNetwork() {
        // getNetworkInterfaces returns one IPv6 network interface.
        mNetworkInterfaces.add(mActiveIpv6MulticastInterface1);
        mNetworkInterfaces.add(mMulticastInterface1);
        mNetworkInterfaces.add(mActiveIpv6MulticastInterface2);
        mNetworkInterfaces.add(mMulticastInterface2);
        assertFalse(mProvider.isOnIpV6OnlyNetwork(mNetworkInterfaces));
    }

    @Test
    public void testIpV6OnlyNetwork_IPv6Only() {
        // getNetworkInterfaces returns one IPv6 network interface.
        mNetworkInterfaces.add(mActiveIpv6MulticastInterface1);
        mNetworkInterfaces.add(mActiveIpv6MulticastInterface2);
        assertTrue(mProvider.isOnIpV6OnlyNetwork(mNetworkInterfaces));
    }

    @Test
    public void testIpV6OnlyNetwork_IPv6Enabled() {
        // getNetworkInterfaces returns one IPv6 network interface.
        mNetworkInterfaces.add(mActiveIpv6MulticastInterface1);
        assertTrue(mProvider.isOnIpV6OnlyNetwork(mNetworkInterfaces));

        final List<NetworkInterfaceWrapper> interfaces = mProvider.getMulticastNetworkInterfaces();
        assertEquals(1, interfaces.size());
        assertEquals(Collections.singletonList(mActiveIpv6MulticastInterface1), interfaces);
    }

    private void setupNetworkInterface(
            @NonNull NetworkInterfaceWrapper networkInterfaceWrapper,
            boolean isUp,
            boolean isLoopback,
            boolean isPointToPoint,
            boolean isVirtual,
            boolean supportsMulticast,
            boolean isIpv6)
            throws SocketException, UnknownHostException {
        when(networkInterfaceWrapper.isUp()).thenReturn(isUp);
        when(networkInterfaceWrapper.isLoopback()).thenReturn(isLoopback);
        when(networkInterfaceWrapper.isPointToPoint()).thenReturn(isPointToPoint);
        when(networkInterfaceWrapper.isVirtual()).thenReturn(isVirtual);
        when(networkInterfaceWrapper.supportsMulticast()).thenReturn(supportsMulticast);
        if (isIpv6) {
            InterfaceAddress interfaceAddress = mock(InterfaceAddress.class);
            InetAddress ip6Address = Inet6Address.getByName("2001:4860:0:1001::68");
            when(interfaceAddress.getAddress()).thenReturn(ip6Address);
            when(networkInterfaceWrapper.getInterfaceAddresses())
                    .thenReturn(Collections.singletonList(interfaceAddress));
        } else {
            Inet4Address ip = (Inet4Address) Inet4Address.getByName("192.168.0.1");
            InterfaceAddress interfaceAddress = mock(InterfaceAddress.class);
            when(interfaceAddress.getAddress()).thenReturn(ip);
            when(networkInterfaceWrapper.getInterfaceAddresses())
                    .thenReturn(Collections.singletonList(interfaceAddress));
        }
    }
}