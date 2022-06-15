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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;

/** Tests for {@link MdnsSocket}. */
@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(SC_V2)
public class MdnsSocketTests {
    @Mock private NetworkInterfaceWrapper mNetworkInterfaceWrapper;
    @Mock private MulticastSocket mMulticastSocket;
    @Mock private MulticastNetworkInterfaceProvider mMulticastNetworkInterfaceProvider;
    private SocketAddress mSocketIPv4Address;
    private SocketAddress mSocketIPv6Address;

    private byte[] mData = new byte[25];
    private final DatagramPacket mDatagramPacket = new DatagramPacket(mData, mData.length);
    private NetworkInterface mNetworkInterface;

    private MdnsSocket mMdnsSocket;

    @Before
    public void setUp() throws SocketException, UnknownHostException {
        MockitoAnnotations.initMocks(this);

        mNetworkInterface = createEmptyNetworkInterface();
        when(mNetworkInterfaceWrapper.getNetworkInterface()).thenReturn(mNetworkInterface);
        when(mMulticastNetworkInterfaceProvider.getMulticastNetworkInterfaces())
                .thenReturn(Collections.singletonList(mNetworkInterfaceWrapper));
        mSocketIPv4Address = new InetSocketAddress(
                InetAddress.getByName("224.0.0.251"), MdnsConstants.MDNS_PORT);
        mSocketIPv6Address = new InetSocketAddress(
                InetAddress.getByName("FF02::FB"), MdnsConstants.MDNS_PORT);
    }

    @Test
    public void testMdnsSocket() throws IOException {
        mMdnsSocket =
                new MdnsSocket(mMulticastNetworkInterfaceProvider, MdnsConstants.MDNS_PORT) {
                    @Override
                    MulticastSocket createMulticastSocket(int port) throws IOException {
                        return mMulticastSocket;
                    }
                };
        mMdnsSocket.send(mDatagramPacket);
        verify(mMulticastSocket).setNetworkInterface(mNetworkInterface);
        verify(mMulticastSocket).send(mDatagramPacket);

        mMdnsSocket.receive(mDatagramPacket);
        verify(mMulticastSocket).receive(mDatagramPacket);

        mMdnsSocket.joinGroup();
        verify(mMulticastSocket).joinGroup(mSocketIPv4Address, mNetworkInterface);

        mMdnsSocket.leaveGroup();
        verify(mMulticastSocket).leaveGroup(mSocketIPv4Address, mNetworkInterface);

        mMdnsSocket.close();
        verify(mMulticastSocket).close();
    }

    @Test
    public void testIPv6OnlyNetwork_IPv6Enabled() throws IOException {
        // Have mMulticastNetworkInterfaceProvider send back an IPv6Only mNetworkInterfaceWrapper
        mNetworkInterface = createEmptyNetworkInterface();
        when(mNetworkInterfaceWrapper.getNetworkInterface()).thenReturn(mNetworkInterface);
        when(mMulticastNetworkInterfaceProvider.getMulticastNetworkInterfaces())
                .thenReturn(Collections.singletonList(mNetworkInterfaceWrapper));

        mMdnsSocket =
                new MdnsSocket(mMulticastNetworkInterfaceProvider, MdnsConstants.MDNS_PORT) {
                    @Override
                    MulticastSocket createMulticastSocket(int port) throws IOException {
                        return mMulticastSocket;
                    }
                };

        when(mMulticastNetworkInterfaceProvider.isOnIpV6OnlyNetwork(
                Collections.singletonList(mNetworkInterfaceWrapper)))
                .thenReturn(true);

        mMdnsSocket.joinGroup();
        verify(mMulticastSocket).joinGroup(mSocketIPv6Address, mNetworkInterface);

        mMdnsSocket.leaveGroup();
        verify(mMulticastSocket).leaveGroup(mSocketIPv6Address, mNetworkInterface);

        mMdnsSocket.close();
        verify(mMulticastSocket).close();
    }

    @Test
    public void testIPv6OnlyNetwork_IPv6Toggle() throws IOException {
        // Have mMulticastNetworkInterfaceProvider send back a mNetworkInterfaceWrapper
        mNetworkInterface = createEmptyNetworkInterface();
        when(mNetworkInterfaceWrapper.getNetworkInterface()).thenReturn(mNetworkInterface);
        when(mMulticastNetworkInterfaceProvider.getMulticastNetworkInterfaces())
                .thenReturn(Collections.singletonList(mNetworkInterfaceWrapper));

        mMdnsSocket =
                new MdnsSocket(mMulticastNetworkInterfaceProvider, MdnsConstants.MDNS_PORT) {
                    @Override
                    MulticastSocket createMulticastSocket(int port) throws IOException {
                        return mMulticastSocket;
                    }
                };

        when(mMulticastNetworkInterfaceProvider.isOnIpV6OnlyNetwork(
                Collections.singletonList(mNetworkInterfaceWrapper)))
                .thenReturn(true);

        mMdnsSocket.joinGroup();
        verify(mMulticastSocket).joinGroup(mSocketIPv6Address, mNetworkInterface);

        mMdnsSocket.leaveGroup();
        verify(mMulticastSocket).leaveGroup(mSocketIPv6Address, mNetworkInterface);

        mMdnsSocket.close();
        verify(mMulticastSocket).close();
    }

    private NetworkInterface createEmptyNetworkInterface() {
        try {
            Constructor<NetworkInterface> constructor =
                    NetworkInterface.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
