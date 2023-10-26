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

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.net.module.util.HexDump;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class IpSecStructXfrmSelectorTest {
    public final String EXPECTED_HEX_STRING =
            "00000000000000000000000000000000"
                    + "00000000000000000000000000000000"
                    + "00000000000000000200000000000000"
                    + "0000000000000000";
    public final byte[] EXPECTED = HexDump.hexStringToByteArray(EXPECTED_HEX_STRING);
    public final byte[] ADDRESS_ANY_BYTES = new byte[16];
    public final int FAMILY = 2;
    public final int STRUCT_SIZE = 56;

    @Test
    public void testEncode() throws Exception {
        IpSecStructXfrmSelector struct =
                new IpSecStructXfrmSelector(
                        ADDRESS_ANY_BYTES,
                        ADDRESS_ANY_BYTES,
                        0,
                        0,
                        0,
                        0,
                        FAMILY,
                        (short) 0,
                        (short) 0,
                        (short) 0,
                        0,
                        0);

        ByteBuffer buffer = ByteBuffer.allocate(STRUCT_SIZE);
        buffer.order(ByteOrder.nativeOrder());
        struct.pack(buffer);

        android.util.Log.i("TEST", "buffer " + HexDump.dumpHexString(buffer.array()));
        assertArrayEquals(EXPECTED, buffer.array());
    }

    @Test
    public void testDecode() throws Exception {
        final ByteBuffer buffer = ByteBuffer.wrap(EXPECTED);
        buffer.order(ByteOrder.nativeOrder());
        IpSecStructXfrmSelector struct =
                IpSecStructXfrmSelector.parse(IpSecStructXfrmSelector.class, buffer);

        assertArrayEquals(ADDRESS_ANY_BYTES, struct.dAddress);
        assertArrayEquals(ADDRESS_ANY_BYTES, struct.sAddress);
        assertEquals(0, struct.dPort);
        assertEquals(0, struct.dPortMask);
        assertEquals(0, struct.sPort);
        assertEquals(0, struct.sPortMask);
        assertEquals(FAMILY, struct.family);
        assertEquals(0, struct.prefixlenD);
        assertEquals(0, struct.prefixlenS);
        assertEquals(0, struct.proto);
        assertEquals(0, struct.ifIndex);
        assertEquals(0, struct.user);
    }
}
