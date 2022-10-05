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

package com.android.server.connectivity.mdns

import com.android.net.module.util.HexDump
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.util.Arrays
import kotlin.test.assertEquals

@RunWith(DevSdkIgnoreRunner::class)
class MdnsNsecRecordTest {
    @Test
    fun testWrite() {
        val caaType = 257 // Not generally used for MDNS, but testing a type > 256
        val record = MdnsNsecRecord(
                arrayOf("record", "android", "com") /* name */,
                0L /* receiptTimeMillis */,
                true /* cacheFlush */,
                60_000L /* ttlMillis */,
                arrayOf("nextdomain", "android", "com"),
                intArrayOf(MdnsRecord.TYPE_SRV, caaType, MdnsRecord.TYPE_TXT) /* types */)
        val writer = MdnsPacketWriter(1500)
        record.write(writer, 0L /* now */)

        val packet = writer.getPacket(
                InetSocketAddress(MdnsConstants.getMdnsIPv4Address(), MdnsConstants.MDNS_PORT))

        val dataInString = "067265636F726407616E64726F696403636F6D00" + // record.android.com
                "002F8001" + // Type 0x002f (NSEC), cache flush set on class IN (0x8001)
                "0000003C" + // TTL 0x0000ea60 (60 secs)
                "0022" + // Data length
                "0A6E657874646F6D61696E07616E64726F696403636F6D00" + // nextdomain.android.com
                // Type bitmaps: window block 0x00, bitmap length 0x05,
                // bits 16 (TXT) and 33 (SRV) set: 0x0000800040
                "00050000800040" +
                // Window block 0x01, bitmap length 0x01, bit 1 set (256 + 1 = 257): 0x40
                "010140"

        assertEquals(dataInString, HexDump.toHexString(Arrays.copyOf(packet.data, packet.length)))
    }
}