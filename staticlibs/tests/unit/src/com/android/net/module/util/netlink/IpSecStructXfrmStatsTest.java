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
public class IpSecStructXfrmStatsTest {
    public final String EXPECTED_HEX_STRING = "000000000000000000000000";
    public final byte[] EXPECTED = HexDump.hexStringToByteArray(EXPECTED_HEX_STRING);

    @Test
    public void testEncode() throws Exception {
        IpSecStructXfrmStats struct = new IpSecStructXfrmStats(0L, 0L, 0L);

        ByteBuffer buffer = ByteBuffer.allocate(EXPECTED.length);
        buffer.order(ByteOrder.nativeOrder());
        struct.pack(buffer);

        android.util.Log.i("TEST", "buffer " + HexDump.dumpHexString(buffer.array()));
        assertArrayEquals(EXPECTED, buffer.array());
    }

    @Test
    public void testDecode() throws Exception {
        final ByteBuffer buffer = ByteBuffer.wrap(EXPECTED);
        buffer.order(ByteOrder.nativeOrder());
        IpSecStructXfrmStats struct =
                IpSecStructXfrmStats.parse(IpSecStructXfrmStats.class, buffer);

        assertEquals(0L, struct.replayWindow);
        assertEquals(0L, struct.replay);
        assertEquals(0L, struct.integrityFailed);
    }
}
