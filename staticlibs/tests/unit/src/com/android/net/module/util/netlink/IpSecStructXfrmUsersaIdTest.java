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
public class IpSecStructXfrmUsersaIdTest {
    public final String EXPECTED_HEX_STRING = "C00002010000000000000000000000007768440002003200";
    public final byte[] EXPECTED = HexDump.hexStringToByteArray(EXPECTED_HEX_STRING);
    public final InetAddress DEST_ADDRESS = InetAddresses.parseNumericAddress("192.0.2.1");
    public final long SPI = 0x77684400;
    public final int FAMILY = 2;
    public final short PROTO = 50;

    @Test
    public void testEncode() throws Exception {
        IpSecStructXfrmUsersaId struct =
                new IpSecStructXfrmUsersaId(DEST_ADDRESS, SPI, FAMILY, PROTO);

        ByteBuffer buffer = ByteBuffer.allocate(24);
        buffer.order(ByteOrder.nativeOrder());
        struct.pack(buffer);

        android.util.Log.i("TEST", "buffer " + HexDump.dumpHexString(buffer.array()));
        assertArrayEquals(EXPECTED, buffer.array());
    }

    @Test
    public void testDecode() throws Exception {
        final ByteBuffer buffer = ByteBuffer.wrap(EXPECTED);
        buffer.order(ByteOrder.nativeOrder());
        IpSecStructXfrmUsersaId struct =
                IpSecStructXfrmUsersaId.parse(IpSecStructXfrmUsersaId.class, buffer);

        assertEquals(DEST_ADDRESS, struct.getDestAddress());
        assertEquals(SPI, struct.mSpi);
        assertEquals(FAMILY, struct.mFamily);
        assertEquals(PROTO, struct.mProto);
    }
}
