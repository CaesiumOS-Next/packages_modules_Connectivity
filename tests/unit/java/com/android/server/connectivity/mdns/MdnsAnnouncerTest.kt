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

import android.net.InetAddresses.parseNumericAddress
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.android.internal.util.HexDump
import com.android.server.connectivity.mdns.MdnsAnnouncer.AnnouncementInfo
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import java.net.DatagramPacket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val FIRST_ANNOUNCES_DELAY = 100L
private const val FIRST_ANNOUNCES_COUNT = 2
private const val NEXT_ANNOUNCES_DELAY = 1L
private const val TEST_TIMEOUT_MS = 1000L

@RunWith(DevSdkIgnoreRunner::class)
class MdnsAnnouncerTest {

    private val thread = HandlerThread(MdnsAnnouncerTest::class.simpleName)
    private val socket = mock(MulticastSocket::class.java)
    private val buffer = ByteArray(1500)

    @Before
    fun setUp() {
        thread.start()
    }

    @After
    fun tearDown() {
        thread.quitSafely()
    }

    private class TestAnnouncer(looper: Looper, replySender: MdnsReplySender)
        : MdnsAnnouncer("testiface", looper, replySender) {
        override fun getDelayMs(nextIndex: Int): Long {
            // Have some delay for the first few announces, but don't wait for that long every time
            return if (nextIndex < FIRST_ANNOUNCES_COUNT) FIRST_ANNOUNCES_DELAY
            else NEXT_ANNOUNCES_DELAY
        }
    }

    @Test
    fun testAnnounce() {
        val replySender = MdnsReplySender(thread.looper, socket, buffer)
        val announcer = TestAnnouncer(thread.looper, replySender)
        val expected = "00008400000000050000000403313233013001320331393207696E2D6164647204617270" +
                "6100000C800100000078000F07416E64726F6964056C6F63616C000133013201310130013001300" +
                "1300130013001300130013001300130013001300130013001300130013001300130013001380142" +
                "01440130013101300130013203697036C020000C8001000000780002C0300136013501340130013" +
                "0013001300130013001300130013001300130013001300130013001300130013001300130013001" +
                "38014201440130013101300130013203697036C020000C8001000000780002C030C030000180010" +
                "00000780004C000027BC030001C800100000078001020010DB8000000000000000000000123C00C" +
                "002F800100000078001E03313233013001320331393207696E2D616464720461727061000002000" +
                "8013301320131013001300130013001300130013001300130013001300130013001300130013001" +
                "3001300130013001300138014201440130013101300130013203697036C020002F8001000000780" +
                "04E0133013201310130013001300130013001300130013001300130013001300130013001300130" +
                "0130013001300130013001380142014401300131013001300132036970360461727061000002000" +
                "8013601350134013001300130013001300130013001300130013001300130013001300130013001" +
                "3001300130013001300138014201440130013101300130013203697036C020002F8001000000780" +
                "04E0136013501340130013001300130013001300130013001300130013001300130013001300130" +
                "0130013001300130013001380142014401300131013001300132036970360461727061000002000" +
                "8C030002F800100000078001507416E64726F6964056C6F63616C00000440000008"

        val hostname = arrayOf("Android", "local")
        val v4Addr = parseNumericAddress("192.0.2.123")
        val v6Addr1 = parseNumericAddress("2001:db8::123")
        val v6Addr2 = parseNumericAddress("2001:db8::456")
        val v4AddrRev = arrayOf("123", "0", "2", "192", "in-addr", "arpa")
        val v6AddrRev1 = getReverseV6AddressName(v6Addr1)
        val v6AddrRev2 = getReverseV6AddressName(v6Addr2)
        val announcedRecords = listOf(
                MdnsPointerRecord(v4AddrRev,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        120000 /* ttlMillis */,
                        hostname),
                MdnsPointerRecord(v6AddrRev1,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        120000 /* ttlMillis */,
                        hostname),
                MdnsPointerRecord(v6AddrRev2,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        120000 /* ttlMillis */,
                        hostname),
                MdnsInetAddressRecord(hostname,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        120000 /* ttlMillis */,
                        v4Addr),
                MdnsInetAddressRecord(hostname,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        120000 /* ttlMillis */,
                        v6Addr1))
        val additionalRecords = listOf(
                MdnsNsecRecord(v4AddrRev,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        120000 /* ttlMillis */,
                        v4AddrRev,
                        intArrayOf(MdnsRecord.TYPE_PTR)),
                MdnsNsecRecord(v6AddrRev1,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        120000 /* ttlMillis */,
                        v6AddrRev1,
                        intArrayOf(MdnsRecord.TYPE_PTR)),
                MdnsNsecRecord(v6AddrRev2,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        120000 /* ttlMillis */,
                        v6AddrRev2,
                        intArrayOf(MdnsRecord.TYPE_PTR)),
                MdnsNsecRecord(hostname,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        120000 /* ttlMillis */,
                        hostname,
                        intArrayOf(MdnsRecord.TYPE_A, MdnsRecord.TYPE_AAAA)))
        val destinationSupplier = { listOf(
                InetSocketAddress(MdnsConstants.getMdnsIPv6Address(), MdnsConstants.MDNS_PORT)) }
        val request = AnnouncementInfo(announcedRecords, additionalRecords, destinationSupplier)

        val timeStart = SystemClock.elapsedRealtime()
        val startDelay = 50L
        val sendId = 1
        announcer.startSending(sendId, request, startDelay)

        val captor = ArgumentCaptor.forClass(DatagramPacket::class.java)
        repeat(FIRST_ANNOUNCES_COUNT) { i ->
            verify(socket, timeout(TEST_TIMEOUT_MS).atLeast(i + 1)).send(
                    if (i == 0) captor.capture() else any())
            val now = SystemClock.elapsedRealtime()
            assertTrue(now > timeStart + startDelay + i * FIRST_ANNOUNCES_DELAY)
            assertTrue(now < timeStart + startDelay + (i + 1) * FIRST_ANNOUNCES_DELAY)
        }

        // Subsequent announces should happen quickly (NEXT_ANNOUNCES_DELAY)
        verify(socket, timeout(TEST_TIMEOUT_MS).times(MdnsAnnouncer.ANNOUNCEMENT_COUNT))
                .send(any())

        assertEquals(expected, HexDump.toHexString(captor.allValues[0].data))
    }
}

/**
 * Compute 2001:db8::1 --> 1.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.8.B.D.1.0.0.2.ip6.arpa
 */
private fun getReverseV6AddressName(addr: InetAddress): Array<String> {
    assertTrue(addr is Inet6Address)
    return addr.address.flatMapTo(mutableListOf("arpa", "ip6")) {
        HexDump.toHexString(it).toCharArray().map(Char::toString)
    }.reversed().toTypedArray()
}