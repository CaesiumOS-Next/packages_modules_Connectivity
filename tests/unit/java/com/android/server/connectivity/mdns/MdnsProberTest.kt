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

import android.os.HandlerThread
import android.os.Looper
import com.android.internal.util.HexDump
import com.android.server.connectivity.mdns.MdnsProber.ProbingInfo
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import java.net.DatagramPacket
import java.net.InetSocketAddress
import java.net.MulticastSocket
import kotlin.test.assertEquals

private val destinationsSupplier = {
    listOf(InetSocketAddress(MdnsConstants.getMdnsIPv6Address(), MdnsConstants.MDNS_PORT)) }

private val TEST_TIMEOUT_MS = 10_000L

@RunWith(DevSdkIgnoreRunner::class)
class MdnsProberTest {
    private val thread = HandlerThread(MdnsAnnouncerTest::class.simpleName)
    private val socket = mock(MulticastSocket::class.java)
    private val repository = mock(MdnsRecordRepository::class.java)
    @Suppress("UNCHECKED_CAST")
    private val cb = mock(MdnsPacketRepeater.PacketRepeaterCallback::class.java)
        as MdnsPacketRepeater.PacketRepeaterCallback<ProbingInfo>
    private val buffer = ByteArray(1500)

    @Before
    fun setUp() {
        thread.start()
    }

    @After
    fun tearDown() {
        thread.quitSafely()
    }

    private class TestProbeInfo(
            probeRecords: List<MdnsRecord>)
        : ProbingInfo(1 /* serviceId */, probeRecords, destinationsSupplier) {
        // Just send the packets quickly. Timing-related tests for MdnsPacketRepeater are already
        // done in MdnsAnnouncerTest.
        override fun getDelayMs(nextIndex: Int) = 1L
    }

    private class TestProber(
        looper: Looper,
        replySender: MdnsReplySender,
        repository: MdnsRecordRepository,
        cb: PacketRepeaterCallback<ProbingInfo>
    ) : MdnsProber("testiface", looper, replySender, repository, cb) {
        override fun getInitialDelay() = 0L
    }

    @Test
    fun testProbe() {
        val replySender = MdnsReplySender("testiface", thread.looper, socket, buffer)
        val prober = TestProber(thread.looper, replySender, repository, cb)
        val probeInfo = TestProbeInfo(
                listOf(MdnsServiceRecord(
                        arrayOf("testservice", "_nmt", "_tcp", "local"),
                        0L /* receiptTimeMillis */,
                        false /* cacheFlush */,
                        120_000L /* ttlMillis */,
                        0 /* servicePriority */,
                        0 /* serviceWeight */,
                        37890 /* servicePort */,
                        arrayOf("myhostname", "local"))))
        prober.startProbing(probeInfo)

        val captor = ArgumentCaptor.forClass(DatagramPacket::class.java)
        verify(socket, timeout(TEST_TIMEOUT_MS).times(probeInfo.numSends)).send(captor.capture())

        val expected = "0000000000010000000100000B7465737473657276696365045F6E6D74045F746370056C" +
                "6F63616C0000FF0001C00C002100010000007800130000000094020A6D79686F73746E616D65C022"

        captor.allValues.forEach {
            assertEquals(expected, HexDump.toHexString(it.data))
        }
    }
}