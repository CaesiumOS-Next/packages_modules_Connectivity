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

package com.android.net.module.util.netlink.xfrm;

import static com.android.net.module.util.netlink.xfrm.XfrmNetlinkMessage.IPPROTO_ESP;
import static com.android.net.module.util.netlink.xfrm.XfrmNetlinkMessage.XFRM_MODE_TRANSPORT;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import android.net.InetAddresses;
import android.system.OsConstants;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.net.module.util.HexDump;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class StructXfrmUsersaInfoTest {
    private static final String EXPECTED_HEX_STRING =
            "00000000000000000000000000000000"
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
                    + "024000000A00000000000000";
    private static final String EXPECTED_HEX_STRING_EXTRA_PADDINGS_KERNEL_64 = "00000000";

    private static final InetAddress DEST_ADDRESS =
            InetAddresses.parseNumericAddress("2001:db8::111");
    private static final InetAddress SOURCE_ADDRESS =
            InetAddresses.parseNumericAddress("2001:db8::222");
    private static final BigInteger ADD_TIME;
    private static final int SELECTOR_FAMILY = OsConstants.AF_INET6;
    private static final int FAMILY = OsConstants.AF_INET6;
    private static final long SPI = 0xaabbccddL;
    private static final long SEQ = 0L;
    private static final long REQ_ID = 16386L;
    private static final short PROTO = IPPROTO_ESP;
    private static final short MODE = XFRM_MODE_TRANSPORT;
    private static final short REPLAY_WINDOW_LEGACY = 0;
    private static final short FLAGS = 0;

    static {
        final Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        cal.set(2023, Calendar.NOVEMBER, 9, 2, 42, 05);
        final long timestampSeconds = TimeUnit.MILLISECONDS.toSeconds(cal.getTimeInMillis());
        ADD_TIME = BigInteger.valueOf(timestampSeconds);
    }

    private void checkEncode(byte[] expected, boolean isKernel64Bit) throws Exception {
        final StructXfrmUsersaInfo struct =
                new StructXfrmUsersaInfo(
                        DEST_ADDRESS,
                        SOURCE_ADDRESS,
                        ADD_TIME,
                        SELECTOR_FAMILY,
                        SPI,
                        SEQ,
                        REQ_ID,
                        PROTO,
                        MODE,
                        REPLAY_WINDOW_LEGACY,
                        FLAGS,
                        isKernel64Bit);

        final ByteBuffer buffer = ByteBuffer.allocate(expected.length);
        buffer.order(ByteOrder.nativeOrder());
        struct.writeToByteBuffer(buffer);

        assertArrayEquals(expected, buffer.array());
    }

    @Test
    public void checkEncodeFor32BitKernel() throws Exception {
        checkEncode(HexDump.hexStringToByteArray(EXPECTED_HEX_STRING), false /* isKernel64Bit */);
    }

    @Test
    public void checkEncodeFor64BitKernel() throws Exception {
        checkEncode(
                HexDump.hexStringToByteArray(
                        EXPECTED_HEX_STRING + EXPECTED_HEX_STRING_EXTRA_PADDINGS_KERNEL_64),
                true /* isKernel64Bit */);
    }

    private void checkDecode(byte[] packet, boolean isKernel64Bit) throws Exception {
        final ByteBuffer buffer = ByteBuffer.wrap(packet);
        buffer.order(ByteOrder.nativeOrder());

        final StructXfrmUsersaInfo struct = StructXfrmUsersaInfo.parse(buffer, isKernel64Bit);
        final StructXfrmUsersaInfo32 struct32 = struct.getXfrmUsersaInfo32();

        assertEquals(DEST_ADDRESS, struct32.getDestAddress());
        assertEquals(SOURCE_ADDRESS, struct32.getSrcAddress());
        assertEquals(SPI, struct32.getSpi());
        assertEquals(SEQ, struct32.seq);
        assertEquals(REQ_ID, struct32.reqId);
        assertEquals(FAMILY, struct32.family);
        assertEquals(MODE, struct32.mode);
        assertEquals(REPLAY_WINDOW_LEGACY, struct32.replayWindowLegacy);
        assertEquals(FLAGS, struct32.flags);
    }

    @Test
    public void checkDecodeFor32BitKernel() throws Exception {
        checkDecode(HexDump.hexStringToByteArray(EXPECTED_HEX_STRING), false /* isKernel64Bit */);
    }

    @Test
    public void checkDecodeFor64BitKernel() throws Exception {
        checkDecode(
                HexDump.hexStringToByteArray(
                        EXPECTED_HEX_STRING + EXPECTED_HEX_STRING_EXTRA_PADDINGS_KERNEL_64),
                true /* isKernel64Bit */);
    }
}
