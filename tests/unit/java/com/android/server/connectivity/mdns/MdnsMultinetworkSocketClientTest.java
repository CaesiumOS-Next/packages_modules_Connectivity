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

import static com.android.server.connectivity.mdns.MdnsConstants.IPV4_SOCKET_ADDR;
import static com.android.server.connectivity.mdns.MdnsConstants.IPV6_SOCKET_ADDR;
import static com.android.server.connectivity.mdns.MdnsSocketProvider.SocketCallback;
import static com.android.server.connectivity.mdns.MulticastPacketReader.PacketHandler;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.net.InetAddresses;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import com.android.net.module.util.HexDump;
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
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.List;

@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
public class MdnsMultinetworkSocketClientTest {
    private static final byte[] BUFFER = new byte[10];
    private static final long DEFAULT_TIMEOUT = 2000L;
    @Mock private Network mNetwork;
    @Mock private MdnsSocketProvider mProvider;
    @Mock private MdnsInterfaceSocket mSocket;
    @Mock private MdnsServiceBrowserListener mListener;
    @Mock private MdnsSocketClientBase.Callback mCallback;
    @Mock private SocketCreationCallback mSocketCreationCallback;
    @Mock private SharedLog mSharedLog;
    @Mock private MdnsSender mSender;
    private MdnsMultinetworkSocketClient mSocketClient;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private SocketKey mSocketKey;

    @Before
    public void setUp() throws SocketException {
        MockitoAnnotations.initMocks(this);

        mHandlerThread = new HandlerThread("MdnsMultinetworkSocketClientTest");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mSocketKey = new SocketKey(1000 /* interfaceIndex */);
        mSocketClient = new MdnsMultinetworkSocketClient(
                mHandlerThread.getLooper(), mProvider, mSharedLog);
        mHandler.post(() -> mSocketClient.setCallback(mCallback));
    }

    @After
    public void tearDown() throws Exception {
        if (mHandlerThread != null) {
            mHandlerThread.quitSafely();
            mHandlerThread.join();
        }
    }

    private SocketCallback expectSocketCallback() {
        return expectSocketCallback(mListener, mNetwork);
    }

    private SocketCallback expectSocketCallback(MdnsServiceBrowserListener listener,
            Network requestedNetwork) {
        final ArgumentCaptor<SocketCallback> callbackCaptor =
                ArgumentCaptor.forClass(SocketCallback.class);
        mHandler.post(() -> mSocketClient.notifyNetworkRequested(
                listener, requestedNetwork, mSocketCreationCallback));
        verify(mProvider, timeout(DEFAULT_TIMEOUT))
                .requestSocket(eq(requestedNetwork), callbackCaptor.capture());
        return callbackCaptor.getValue();
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

    private void sendPacket(MdnsSender sender, MdnsPacket packet, InetSocketAddress destination,
            boolean onlyUseIpv6OnIpv6OnlyNetworks, boolean expectUnicastResponse) {
        mHandler.post(() -> {
            try {
                sender.sendNow(packet, destination, onlyUseIpv6OnIpv6OnlyNetworks,
                        expectUnicastResponse);
            } catch (IOException e) {}
        });
        HandlerUtils.waitForIdle(mHandler, DEFAULT_TIMEOUT);
    }

    @Test
    public void testSendPacket() throws IOException {
        final SocketCallback callback = expectSocketCallback();
        final MdnsPacket mdnsPacket = new MdnsPacket(
                123 /* transactionId */,
                MdnsConstants.FLAGS_QUERY /* flags */,
                List.of(new MdnsPointerRecord(
                        "_testtype._tcp.local".split("\\."),
                        false /* unicast */
                )) /* questions */,
                Collections.emptyList()/* answers */,
                Collections.emptyList() /* authorityRecords */,
                Collections.emptyList() /* additionalRecords */);

        final MdnsInterfaceSocket tetherIfaceSock1 = mock(MdnsInterfaceSocket.class);
        final MdnsInterfaceSocket tetherIfaceSock2 = mock(MdnsInterfaceSocket.class);
        for (MdnsInterfaceSocket socket : List.of(mSocket, tetherIfaceSock1, tetherIfaceSock2)) {
            doReturn(true).when(socket).hasJoinedIpv4();
            doReturn(true).when(socket).hasJoinedIpv6();
            doReturn(createEmptyNetworkInterface()).when(socket).getInterface();
        }

        final SocketKey tetherSocketKey1 = new SocketKey(1001 /* interfaceIndex */);
        final SocketKey tetherSocketKey2 = new SocketKey(1002 /* interfaceIndex */);
        final ArgumentCaptor<MdnsSender> senderCaptor = ArgumentCaptor.forClass(MdnsSender.class);
        // Notify socket created
        callback.onSocketCreated(mSocketKey, mSocket, List.of());
        verify(mSocketCreationCallback).onSocketCreated(eq(mSocketKey), senderCaptor.capture());
        callback.onSocketCreated(tetherSocketKey1, tetherIfaceSock1, List.of());
        verify(mSocketCreationCallback).onSocketCreated(
                eq(tetherSocketKey1), senderCaptor.capture());
        callback.onSocketCreated(tetherSocketKey2, tetherIfaceSock2, List.of());
        verify(mSocketCreationCallback).onSocketCreated(
                eq(tetherSocketKey2), any(MdnsSender.class));

        // Send packet to IPv4 with mSocketKey and verify sending has been called.
        final MdnsSender sender = senderCaptor.getAllValues().get(0);
        sendPacket(sender, mdnsPacket, IPV4_SOCKET_ADDR,
                false /* onlyUseIpv6OnIpv6OnlyNetworks */, false /* expectUnicastResponse */);
        verify(mSocket).send(argThat(
                dPacket -> dPacket.getAddress().equals(IPV4_SOCKET_ADDR.getAddress())));
        verify(tetherIfaceSock1, never()).send(any());
        verify(tetherIfaceSock2, never()).send(any());

        // Send packet to IPv4 with onlyUseIpv6OnIpv6OnlyNetworks = true, the packet will be sent.
        sendPacket(sender, mdnsPacket, IPV4_SOCKET_ADDR,
                true /* onlyUseIpv6OnIpv6OnlyNetworks */, false /* expectUnicastResponse */);
        verify(mSocket, times(2)).send(argThat(
                dPacket -> dPacket.getAddress().equals(IPV4_SOCKET_ADDR.getAddress())));
        verify(tetherIfaceSock1, never()).send(any());
        verify(tetherIfaceSock2, never()).send(any());

        // Send packet to IPv6 with tetherSocketKey1 and verify sending has been called.
        final MdnsSender tetherSender = senderCaptor.getAllValues().get(1);
        sendPacket(tetherSender, mdnsPacket, IPV6_SOCKET_ADDR,
                false /* onlyUseIpv6OnIpv6OnlyNetworks */, false /* expectUnicastResponse */);
        verify(mSocket, never()).send(argThat(
                dPacket -> dPacket.getAddress().equals(IPV6_SOCKET_ADDR.getAddress())));
        verify(tetherIfaceSock1).send(argThat(
                dPacket -> dPacket.getAddress().equals(IPV6_SOCKET_ADDR.getAddress())));
        verify(tetherIfaceSock2, never()).send(any(DatagramPacket.class));

        // Send packet to IPv6 with onlyUseIpv6OnIpv6OnlyNetworks = true, the packet will not be
        // sent. Therefore, the tetherIfaceSock1.send() and tetherIfaceSock2.send() are still be
        // called once.
        sendPacket(tetherSender, mdnsPacket, IPV6_SOCKET_ADDR,
                true /* onlyUseIpv6OnIpv6OnlyNetworks */, false /* expectUnicastResponse */);
        verify(mSocket, never()).send(argThat(
                dPacket -> dPacket.getAddress().equals(IPV6_SOCKET_ADDR.getAddress())));
        verify(tetherIfaceSock1, times(1)).send(argThat(
                dPacket -> dPacket.getAddress().equals(IPV6_SOCKET_ADDR.getAddress())));
        verify(tetherIfaceSock2, never()).send(any(DatagramPacket.class));
    }

    @Test
    public void testReceivePacket() {
        final SocketCallback callback = expectSocketCallback();
        final byte[] data = HexDump.hexStringToByteArray(
                // scapy.raw(scapy.dns_compress(
                //     scapy.DNS(rd=0, qr=1, aa=1, qd = None,
                //     an =
                //     scapy.DNSRR(type='PTR', rrname='_testtype._tcp.local',
                //         rdata='testservice._testtype._tcp.local', rclass='IN', ttl=4500) /
                //     scapy.DNSRRSRV(rrname='testservice._testtype._tcp.local', rclass=0x8001,
                //         port=31234, target='Android.local', ttl=120))
                // )).hex().upper()
                "000084000000000200000000095F7465737474797065045F746370056C6F63616C00000C0001000011"
                        + "94000E0B7465737473657276696365C00CC02C00218001000000780010000000007A0207"
                        + "416E64726F6964C01B");

        doReturn(createEmptyNetworkInterface()).when(mSocket).getInterface();
        // Notify socket created
        callback.onSocketCreated(mSocketKey, mSocket, List.of());
        verify(mSocketCreationCallback).onSocketCreated(eq(mSocketKey), any(MdnsSender.class));

        final ArgumentCaptor<PacketHandler> handlerCaptor =
                ArgumentCaptor.forClass(PacketHandler.class);
        verify(mSocket).addPacketHandler(handlerCaptor.capture());

        // Send the data and verify the received records.
        final PacketHandler handler = handlerCaptor.getValue();
        handler.handlePacket(data, data.length, null /* src */);
        final ArgumentCaptor<MdnsPacket> responseCaptor =
                ArgumentCaptor.forClass(MdnsPacket.class);
        verify(mCallback).onResponseReceived(responseCaptor.capture(), any());
        final MdnsPacket response = responseCaptor.getValue();
        assertEquals(0, response.questions.size());
        assertEquals(0, response.additionalRecords.size());
        assertEquals(0, response.authorityRecords.size());

        final String[] serviceName = "testservice._testtype._tcp.local".split("\\.");
        assertEquals(List.of(
                new MdnsPointerRecord("_testtype._tcp.local".split("\\."),
                        0L /* receiptTimeMillis */, false /* cacheFlush */, 4500000 /* ttlMillis */,
                        serviceName),
                new MdnsServiceRecord(serviceName, 0L /* receiptTimeMillis */,
                        false /* cacheFlush */, 4500000 /* ttlMillis */, 0 /* servicePriority */,
                        0 /* serviceWeight */, 31234 /* servicePort */,
                        new String[] { "Android", "local" } /* serviceHost */)
        ), response.answers);
    }

    @Test
    public void testSocketRemovedAfterNetworkUnrequested() throws IOException {
        // Request sockets on all networks
        final SocketCallback callback = expectSocketCallback(mListener, null);
        final MdnsPacket mdnsPacket = new MdnsPacket(
                123 /* transactionId */,
                MdnsConstants.FLAGS_QUERY /* flags */,
                List.of(new MdnsPointerRecord(
                        "_testtype._tcp.local".split("\\."),
                        false /* unicast */
                )) /* questions */,
                Collections.emptyList()/* answers */,
                Collections.emptyList() /* authorityRecords */,
                Collections.emptyList() /* additionalRecords */);

        // Notify 3 socket created, including 2 tethered interfaces (null network)
        final MdnsInterfaceSocket socket2 = mock(MdnsInterfaceSocket.class);
        final MdnsInterfaceSocket socket3 = mock(MdnsInterfaceSocket.class);
        doReturn(true).when(mSocket).hasJoinedIpv4();
        doReturn(true).when(mSocket).hasJoinedIpv6();
        doReturn(true).when(socket2).hasJoinedIpv4();
        doReturn(true).when(socket2).hasJoinedIpv6();
        doReturn(true).when(socket3).hasJoinedIpv4();
        doReturn(true).when(socket3).hasJoinedIpv6();
        doReturn(createEmptyNetworkInterface()).when(mSocket).getInterface();
        doReturn(createEmptyNetworkInterface()).when(socket2).getInterface();
        doReturn(createEmptyNetworkInterface()).when(socket3).getInterface();

        final SocketKey socketKey2 = new SocketKey(1001 /* interfaceIndex */);
        final SocketKey socketKey3 = new SocketKey(1002 /* interfaceIndex */);
        final ArgumentCaptor<MdnsSender> senderCaptor = ArgumentCaptor.forClass(MdnsSender.class);
        callback.onSocketCreated(mSocketKey, mSocket, List.of());
        callback.onSocketCreated(socketKey2, socket2, List.of());
        callback.onSocketCreated(socketKey3, socket3, List.of());
        verify(mSocketCreationCallback).onSocketCreated(eq(mSocketKey), senderCaptor.capture());
        verify(mSocketCreationCallback).onSocketCreated(eq(socketKey2), any(MdnsSender.class));
        verify(mSocketCreationCallback).onSocketCreated(eq(socketKey3), any(MdnsSender.class));

        // Send IPv4 packet on the mSocketKey and verify sending has been called.
        final MdnsSender sender = senderCaptor.getAllValues().get(0);
        sendPacket(sender, mdnsPacket, IPV4_SOCKET_ADDR,
                false /* onlyUseIpv6OnIpv6OnlyNetworks */, false /* expectUnicastResponse */);
        verify(mSocket).send(argThat(
                dPacket -> dPacket.getAddress().equals(IPV4_SOCKET_ADDR.getAddress())));
        verify(socket2, never()).send(any());
        verify(socket3, never()).send(any());

        // Request another socket with null network, get the same interfaces
        final SocketCreationCallback socketCreationCb2 = mock(SocketCreationCallback.class);
        final MdnsServiceBrowserListener listener2 = mock(MdnsServiceBrowserListener.class);

        // requestSocket is called a second time
        final ArgumentCaptor<SocketCallback> callback2Captor =
                ArgumentCaptor.forClass(SocketCallback.class);
        mHandler.post(() -> mSocketClient.notifyNetworkRequested(
                listener2, null, socketCreationCb2));
        HandlerUtils.waitForIdle(mHandler, DEFAULT_TIMEOUT);
        verify(mProvider, times(2)).requestSocket(eq(null), callback2Captor.capture());
        final SocketCallback callback2 = callback2Captor.getAllValues().get(1);

        // Notify socket created for all networks.
        callback2.onSocketCreated(mSocketKey, mSocket, List.of());
        callback2.onSocketCreated(socketKey2, socket2, List.of());
        callback2.onSocketCreated(socketKey3, socket3, List.of());
        verify(socketCreationCb2).onSocketCreated(mSocketKey, sender);
        verify(socketCreationCb2).onSocketCreated(eq(socketKey2), senderCaptor.capture());
        verify(socketCreationCb2).onSocketCreated(eq(socketKey3), any(MdnsSender.class));

        // Send IPv4 packet on socket2 and verify sending to the socket2 only.
        final MdnsSender sender2 = senderCaptor.getAllValues().get(1);
        sendPacket(sender2, mdnsPacket, IPV4_SOCKET_ADDR,
                false /* onlyUseIpv6OnIpv6OnlyNetworks */, false /* expectUnicastResponse */);
        // ipv4Packet still sent only once on mSocket: times(1) matches the packet sent earlier on
        // mNetwork
        verify(mSocket, times(1)).send(argThat(
                dPacket -> dPacket.getAddress().equals(IPV4_SOCKET_ADDR.getAddress())));
        verify(socket2).send(argThat(
                dPacket -> dPacket.getAddress().equals(IPV4_SOCKET_ADDR.getAddress())));
        verify(socket3, never()).send(any(DatagramPacket.class));

        // Unregister the second request
        mHandler.post(() -> mSocketClient.notifyNetworkUnrequested(listener2));
        verify(mProvider, timeout(DEFAULT_TIMEOUT)).unrequestSocket(callback2);

        // Send IPv4 packet again and verify it's still sent a second time
        sendPacket(sender2, mdnsPacket, IPV4_SOCKET_ADDR,
                false /* onlyUseIpv6OnIpv6OnlyNetworks */, false /* expectUnicastResponse */);
        verify(socket2, times(2)).send(argThat(
                dPacket -> dPacket.getAddress().equals(IPV4_SOCKET_ADDR.getAddress())));
        verify(socket3, never()).send(any(DatagramPacket.class));

        // Unrequest remaining sockets
        mHandler.post(() -> mSocketClient.notifyNetworkUnrequested(mListener));
        verify(mProvider, timeout(DEFAULT_TIMEOUT)).unrequestSocket(callback);

        // Send IPv4 packet and verify no more sending.
        sendPacket(sender, mdnsPacket, IPV4_SOCKET_ADDR,
                false /* onlyUseIpv6OnIpv6OnlyNetworks */, false /* expectUnicastResponse */);
        verify(mSocket, times(1)).send(argThat(
                dPacket -> dPacket.getAddress().equals(IPV4_SOCKET_ADDR.getAddress())));
        verify(socket2, times(2)).send(argThat(
                dPacket -> dPacket.getAddress().equals(IPV4_SOCKET_ADDR.getAddress())));
        verify(socket3, never()).send(any(DatagramPacket.class));
    }

    @Test
    public void testNotifyNetworkUnrequested_SocketsOnNullNetwork() {
        final MdnsInterfaceSocket otherSocket = mock(MdnsInterfaceSocket.class);
        final SocketKey otherSocketKey = new SocketKey(1001 /* interfaceIndex */);
        final SocketCallback callback = expectSocketCallback(
                mListener, null /* requestedNetwork */);
        doReturn(createEmptyNetworkInterface()).when(mSocket).getInterface();
        doReturn(createEmptyNetworkInterface()).when(otherSocket).getInterface();

        callback.onSocketCreated(mSocketKey, mSocket, List.of());
        verify(mSocketCreationCallback).onSocketCreated(eq(mSocketKey), any(MdnsSender.class));
        callback.onSocketCreated(otherSocketKey, otherSocket, List.of());
        verify(mSocketCreationCallback).onSocketCreated(eq(otherSocketKey), any(MdnsSender.class));

        verify(mSocketCreationCallback, never()).onSocketDestroyed(mSocketKey);
        verify(mSocketCreationCallback, never()).onSocketDestroyed(otherSocketKey);
        mHandler.post(() -> mSocketClient.notifyNetworkUnrequested(mListener));
        HandlerUtils.waitForIdle(mHandler, DEFAULT_TIMEOUT);

        verify(mProvider).unrequestSocket(callback);
        verify(mSocketCreationCallback).onSocketDestroyed(mSocketKey);
        verify(mSocketCreationCallback).onSocketDestroyed(otherSocketKey);
    }

    @Test
    public void testSocketCreatedAndDestroyed_NullNetwork() throws IOException {
        final MdnsInterfaceSocket otherSocket = mock(MdnsInterfaceSocket.class);
        final SocketKey otherSocketKey = new SocketKey(1001 /* interfaceIndex */);
        final SocketCallback callback = expectSocketCallback(mListener, null /* network */);
        doReturn(createEmptyNetworkInterface()).when(mSocket).getInterface();
        doReturn(createEmptyNetworkInterface()).when(otherSocket).getInterface();

        callback.onSocketCreated(mSocketKey, mSocket, List.of());
        verify(mSocketCreationCallback).onSocketCreated(eq(mSocketKey), any(MdnsSender.class));
        callback.onSocketCreated(otherSocketKey, otherSocket, List.of());
        verify(mSocketCreationCallback).onSocketCreated(eq(otherSocketKey), any(MdnsSender.class));

        // Notify socket destroyed
        mHandler.post(() -> callback.onInterfaceDestroyed(mSocketKey, mSocket));
        HandlerUtils.waitForIdle(mHandler, DEFAULT_TIMEOUT);
        verify(mSocketCreationCallback).onSocketDestroyed(mSocketKey);
        mHandler.post(() -> callback.onInterfaceDestroyed(otherSocketKey, otherSocket));
        HandlerUtils.waitForIdle(mHandler, DEFAULT_TIMEOUT);
        verify(mSocketCreationCallback).onSocketDestroyed(otherSocketKey);
    }
}
