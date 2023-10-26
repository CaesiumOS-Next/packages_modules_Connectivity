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

package com.android.net.module.util.netlink;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import android.net.InetAddresses;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.net.module.util.HexDump;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class IpSecXfrmNetlinkGetSaMessageTest {
    // 28000000 1200 0103 00000000 00000000 C00002010000000000000000000000007768440002003200
    public final String EXPECTED_HEX_STRING =
            "28000000120001030000000000000000C00002010000000000000000000000007768440002003200";
    public final byte[] EXPECTED = HexDump.hexStringToByteArray(EXPECTED_HEX_STRING);
    public final InetAddress DEST_ADDRESS = InetAddresses.parseNumericAddress("192.0.2.1");
    public final long SPI = 0x77684400;
    public final int FAMILY = 2;
    public final short PROTO = 50;

    @Test
    public void testEncode() throws Exception {
        final byte[] result =
                IpSecXfrmNetlinkGetSaMessage.newIpSecXfrmNetlinkGetSaMessage(
                        0, DEST_ADDRESS, SPI, FAMILY, PROTO);
        android.util.Log.i("TEST", "buffer " + HexDump.dumpHexString(result));
        assertArrayEquals(EXPECTED, result);

        IpSecNetlinkUtils.ipSecDumpSa(0, DEST_ADDRESS, SPI);
    }

    @Test
    public void testDecode() throws Exception {
        final ByteBuffer buffer = ByteBuffer.wrap(EXPECTED);
        buffer.order(ByteOrder.nativeOrder());
        final IpSecXfrmNetlinkGetSaMessage message = IpSecXfrmNetlinkGetSaMessage.parse(buffer);
        IpSecStructXfrmUsersaId struct = message.getIpSecStructXfrmUsersaId();

        assertEquals(DEST_ADDRESS, struct.getDestAddress());
        assertEquals(SPI, struct.mSpi);
        assertEquals(FAMILY, struct.mFamily);
        assertEquals(PROTO, struct.mProto);
    }
}
