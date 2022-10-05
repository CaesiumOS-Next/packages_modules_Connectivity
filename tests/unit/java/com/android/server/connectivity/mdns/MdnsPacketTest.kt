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

import android.net.InetAddresses
import com.android.net.module.util.HexDump
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(DevSdkIgnoreRunner::class)
class MdnsPacketTest {
    @Test
    fun testParse() {
        // Packet observed as sent by mdnsresponder, which have 4 identical questions for
        // Android.local (!), and 4 additionalRecords with 4 addresses for Android.local
        val packetHex = "00000000000400000004000007416E64726F6964056C6F63616C0000FF000107416E64" +
                "726F6964056C6F63616C0000FF000107416E64726F6964056C6F63616C0000FF000107416E6472" +
                "6F6964056C6F63616C0000FF000107416E64726F6964056C6F63616C0000010001000000780004" +
                "C000027B07416E64726F6964056C6F63616C00001C000100000078001020010DB8000000000000" +
                "00000000012307416E64726F6964056C6F63616C00001C000100000078001020010DB800000000" +
                "000000000000045607416E64726F6964056C6F63616C00001C000100000078001020010DB80000" +
                "00000000000000000789"

        val bytes = HexDump.hexStringToByteArray(packetHex)
        val reader = MdnsPacketReader(bytes, bytes.size)
        val packet = MdnsPacket.parse(reader)

        assertEquals(4, packet.questions.size)
        assertEquals(0, packet.answers.size)
        assertEquals(4, packet.authorityRecords.size)
        assertEquals(0, packet.additionalRecords.size)

        val hostname = arrayOf("Android", "local")
        assertEquals(4, packet.questions.size)
        packet.questions.forEach {
            assertTrue(it is MdnsAnyRecord)
            assertContentEquals(hostname, it.name)
        }

        assertEquals(4, packet.authorityRecords.size)
        packet.authorityRecords.forEach {
            assertTrue(it is MdnsInetAddressRecord)
            assertContentEquals(hostname, it.name)
            assertEquals(120000, it.ttl)
        }

        assertEquals(InetAddresses.parseNumericAddress("192.0.2.123"),
                (packet.authorityRecords[0] as MdnsInetAddressRecord).inet4Address)
        assertEquals(InetAddresses.parseNumericAddress("2001:db8::123"),
                (packet.authorityRecords[1] as MdnsInetAddressRecord).inet6Address)
        assertEquals(InetAddresses.parseNumericAddress("2001:db8::456"),
                (packet.authorityRecords[2] as MdnsInetAddressRecord).inet6Address)
        assertEquals(InetAddresses.parseNumericAddress("2001:db8::789"),
                (packet.authorityRecords[3] as MdnsInetAddressRecord).inet6Address)
    }
}