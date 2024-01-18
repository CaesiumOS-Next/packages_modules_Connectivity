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
import com.android.net.module.util.netlink.xfrm.StructXfrmUsersaInfo.StructXfrmUsersaInfoX8632;

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
    private static final String EXPECTED_HEX_STRING_X86_32 =
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
    private static final String EXPECTED_HEX_STRING_EXTRA_PADDINGS = "00000000";

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

    private void checkEncode(byte[] expected, boolean isKernelX8632) throws Exception {
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
                        isKernelX8632);

        final ByteBuffer buffer = ByteBuffer.allocate(expected.length);
        buffer.order(ByteOrder.nativeOrder());
        struct.writeToByteBuffer(buffer);

        assertArrayEquals(expected, buffer.array());
    }

    @Test
    public void checkEncode_x8632Kernel() throws Exception {
        checkEncode(
                HexDump.hexStringToByteArray(EXPECTED_HEX_STRING_X86_32), true /* isKernelX8632 */);
    }

    @Test
    public void checkEncode_notX8632Kernel() throws Exception {
        checkEncode(
                HexDump.hexStringToByteArray(
                        EXPECTED_HEX_STRING_X86_32 + EXPECTED_HEX_STRING_EXTRA_PADDINGS),
                false /* isKernelX8632 */);
    }

    private void checkDecode(byte[] packet, boolean isKernelX8632) throws Exception {
        final ByteBuffer buffer = ByteBuffer.wrap(packet);
        buffer.order(ByteOrder.nativeOrder());

        final StructXfrmUsersaInfo struct = StructXfrmUsersaInfo.parse(buffer, isKernelX8632);
        final StructXfrmUsersaInfoX8632 structx8632 = struct.getXfrmUsersaInfoX8632();

        assertEquals(DEST_ADDRESS, struct.getDestAddress());
        assertEquals(SOURCE_ADDRESS, struct.getSrcAddress());
        assertEquals(SPI, struct.getSpi());
        assertEquals(SEQ, structx8632.seq);
        assertEquals(REQ_ID, structx8632.reqId);
        assertEquals(FAMILY, structx8632.family);
        assertEquals(MODE, structx8632.mode);
        assertEquals(REPLAY_WINDOW_LEGACY, structx8632.replayWindowLegacy);
        assertEquals(FLAGS, structx8632.flags);
    }

    @Test
    public void checkDecode_x8632Kernel() throws Exception {
        checkDecode(
                HexDump.hexStringToByteArray(EXPECTED_HEX_STRING_X86_32), true /* isKernelX8632 */);
    }

    @Test
    public void checkDecode_notX8632Kernel() throws Exception {
        checkDecode(
                HexDump.hexStringToByteArray(
                        EXPECTED_HEX_STRING_X86_32 + EXPECTED_HEX_STRING_EXTRA_PADDINGS),
                false /* isKernelX8632 */);
    }
}
