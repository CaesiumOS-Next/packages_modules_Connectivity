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

import static com.android.server.connectivity.mdns.MdnsSocketProvider.SocketCallback;
import static com.android.server.connectivity.mdns.MulticastPacketReader.PacketHandler;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.net.InetAddresses;
import android.net.Network;
import android.net.wifi.WifiManager.MulticastLock;
import android.os.Build;

import com.android.net.module.util.HexDump;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.SocketException;
import java.util.List;

@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
public class MdnsSocketManagerTest {
    private static final Network NETWORK = mock(Network.class);
    private static final byte[] BUFFER = new byte[10];
    @Mock private MulticastLock mLock;
    @Mock private MdnsSocketProvider mProvider;
    @Mock private MdnsInterfaceSocket mSocket;
    @Mock private MdnsServiceBrowserListener mListener;
    @Mock private MdnsSocketController.Callback mCallback;
    private MdnsSocketManager mSocketManager;

    @Before
    public void setUp() throws SocketException {
        MockitoAnnotations.initMocks(this);
        mSocketManager = new MdnsSocketManager(mProvider, mLock);
        mSocketManager.setCallback(mCallback);
    }

    private SocketCallback expectSocketCallback() {
        final ArgumentCaptor<SocketCallback> callbackCaptor =
                ArgumentCaptor.forClass(SocketCallback.class);
        mSocketManager.startDiscovery(mListener, NETWORK);
        verify(mProvider).requestSocket(eq(NETWORK), callbackCaptor.capture());
        return callbackCaptor.getValue();
    }

    @Test
    public void testSendPacket() throws IOException {
        final SocketCallback callback = expectSocketCallback();
        final DatagramPacket ipv4Packet = new DatagramPacket(BUFFER, 0 /* offset */, BUFFER.length,
                InetAddresses.parseNumericAddress("192.0.2.1"), 0 /* port */);
        final DatagramPacket ipv6Packet = new DatagramPacket(BUFFER, 0 /* offset */, BUFFER.length,
                InetAddresses.parseNumericAddress("2001:db8::"), 0 /* port */);
        doReturn(true).when(mSocket).hasJoinedIpv4();
        doReturn(true).when(mSocket).hasJoinedIpv6();
        // Notify socket created
        callback.onSocketCreated(NETWORK, mSocket, List.of());

        // Send packet and verify sending has been called.
        mSocketManager.sendMulticastPacket(ipv4Packet);
        verify(mSocket).send(ipv4Packet);

        mSocketManager.sendMulticastPacket(ipv6Packet);
        verify(mSocket).send(ipv6Packet);
    }

    @Test
    public void testReceivePacket() {
        final SocketCallback callback = expectSocketCallback();
        final byte[] data6 = HexDump.hexStringToByteArray(
                "0000840000000001000000030B5F676F6F676C656361737404"
                        + "5F746370056C6F63616C00000C000100000078003330476F6F676C"
                        + "652D486F6D652D4D61782D61363836666331323961366638636265"
                        + "31643636353139343065336164353766C00CC02E00108001000011"
                        + "9400C02369643D6136383666633132396136663863626531643636"
                        + "3531393430653361643537662363643D4133304233303032363546"
                        + "36384341313233353532434639344141353742314613726D3D4335"
                        + "35393134383530383841313638330576653D3035126D643D476F6F"
                        + "676C6520486F6D65204D61781269633D2F73657475702F69636F6E"
                        + "2E706E6710666E3D417474696320737065616B65720863613D3130"
                        + "3234340473743D320F62733D464138464341363734453537046E66"
                        + "3D320372733DC02E0021800100000078002D000000001F49246136"
                        + "3836666331322D396136662D386362652D316436362D3531393430"
                        + "65336164353766C01DC13F001C8001000000780010200033330000"
                        + "0000DA6C63FFFE7C74830109018001000000780004C0A801026C6F"
                        + "63616C0000018001000000780004C0A8010A000001800100000078"
                        + "0004C0A8010A00000000000000");

        doReturn(99).when(mSocket).getInterfaceIndex();
        // Notify socket created
        callback.onSocketCreated(NETWORK, mSocket, List.of());

        final ArgumentCaptor<PacketHandler> handlerCaptor =
                ArgumentCaptor.forClass(PacketHandler.class);
        verify(mSocket).setPacketHandler(handlerCaptor.capture());

        // Send received IPv6 packet and verify the onResponseReceived callback has been called.
        final PacketHandler handler = handlerCaptor.getValue();
        handler.handlePacket(data6, data6.length, null /* src */);
        verify(mCallback).onResponseReceived(any(MdnsResponse.class));
    }
}
