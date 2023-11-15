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

package com.android.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.net.InetAddresses;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.net.module.util.HexDump;
import com.android.net.module.util.netlink.xfrm.XfrmNetlinkNewSaMessage;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class IpSecXfrmControllerTest {
    private static final String XFRM_NEW_SA_HEX_STRING =
            "2003000010000000000000003FE1D4B6"
                    + "00000000000000000000000000000000"
                    + "00000000000000000000000000000000"
                    + "00000000000000000A00000000000000"
                    + "000000000000000020010DB800000000"
                    + "0000000000000111AABBCCDD32000000"
                    + "20010DB8000000000000000000000222"
                    + "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"
                    + "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"
                    + "00000000000000000000000000000000"
                    + "00000000000000000000000000000000"
                    + "00000000000000000000000000000000"
                    + "FD464C65000000000000000000000000"
                    + "00000000000000000000000000000000"
                    + "024000000A0000000000000000000000"
                    + "5C000100686D61632873686131290000"
                    + "00000000000000000000000000000000"
                    + "00000000000000000000000000000000"
                    + "00000000000000000000000000000000"
                    + "00000000A000000055F01AC07E15E437"
                    + "115DDE0AEDD18A822BA9F81E60001400"
                    + "686D6163287368613129000000000000"
                    + "00000000000000000000000000000000"
                    + "00000000000000000000000000000000"
                    + "00000000000000000000000000000000"
                    + "A00000006000000055F01AC07E15E437"
                    + "115DDE0AEDD18A822BA9F81E58000200"
                    + "63626328616573290000000000000000"
                    + "00000000000000000000000000000000"
                    + "00000000000000000000000000000000"
                    + "00000000000000000000000000000000"
                    + "800000006AED4975ADF006D65C76F639"
                    + "23A6265B1C0117004000000000000000"
                    + "00000000000000000000000000080000"
                    + "00000000000000000000000000000000"
                    + "00000000000000000000000000000000"
                    + "00000000000000000000000000000000"
                    + "00000000000000000000000000000000"
                    + "00000000000000000000000000000000"
                    + "00000000000000000000000000000000"
                    + "00000000000000000000000000000000"
                    + "00000000000000000000000000000000"
                    + "00000000000000000000000000000000"
                    + "00000000000000000000000000000000"
                    + "00000000000000000000000000000000"
                    + "00000000000000000000000000000000"
                    + "00000000000000000000000000000000"
                    + "00000000000000000000000000000000"
                    + "00000000000000000000000000000000"
                    + "00000000000000000000000000000000"
                    + "00000000000000000000000000000000";
    private static final byte[] XFRM_NEW_SA_HEX =
            HexDump.hexStringToByteArray(XFRM_NEW_SA_HEX_STRING);
    private static final InetAddress DEST_ADDRESS =
            InetAddresses.parseNumericAddress("2001:db8::111");
    private static final long SPI = 0xaabbccddL;

    private IpSecXfrmController mXfrmController;
    private FileDescriptor mDummyNetlinkSocket;

    @Mock private IpSecXfrmController.Dependencies mMockDeps;

    @Captor private ArgumentCaptor<byte[]> mRequestByteArrayCaptor;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mDummyNetlinkSocket = new FileDescriptor();

        when(mMockDeps.newNetlinkSocket()).thenReturn(mDummyNetlinkSocket);
        mXfrmController = new IpSecXfrmController(mMockDeps);
    }

    @Test
    public void testStartStop() throws Exception {
        mXfrmController.startIfNeeded();

        verify(mMockDeps).newNetlinkSocket();
        assertNotNull(mXfrmController.getNetlinkSocket());

        mXfrmController.stopIfNeeded();
        verify(mMockDeps).releaseNetlinkSocket(eq(mDummyNetlinkSocket));
        assertNull(mXfrmController.getNetlinkSocket());
    }

    private static void injectRxMessage(IpSecXfrmController.Dependencies mockDeps, byte[] bytes)
            throws Exception {
        final ByteBuffer buff = ByteBuffer.wrap(bytes);
        buff.order(ByteOrder.nativeOrder());

        when(mockDeps.recvMessage(any(FileDescriptor.class))).thenReturn(buff);
    }

    @Test
    public void testIpSecGetSa() throws Exception {
        final int expectedReqLen = 40;
        injectRxMessage(mMockDeps, XFRM_NEW_SA_HEX);

        mXfrmController.startIfNeeded();
        final XfrmNetlinkNewSaMessage message = mXfrmController.ipSecGetSa(DEST_ADDRESS, SPI);

        // Verification
        assertEquals(SPI, message.getXfrmUsersaInfo().getSpi());
        assertEquals(DEST_ADDRESS, message.getXfrmUsersaInfo().getDestAddress());

        verify(mMockDeps).sendMessage(eq(mDummyNetlinkSocket), mRequestByteArrayCaptor.capture());
        final byte[] request = mRequestByteArrayCaptor.getValue();
        assertEquals(expectedReqLen, request.length);

        verify(mMockDeps).recvMessage(eq(mDummyNetlinkSocket));
    }

    @Test(expected = IOException.class)
    public void testIpSecGetSa_XfrmCtrlNotStarted() throws Exception {
        mXfrmController.ipSecGetSa(DEST_ADDRESS, SPI);
    }

    @Test
    public void testIpSecGetSa_NlErrorMsg() throws Exception {
        // TODO:
    }
}
