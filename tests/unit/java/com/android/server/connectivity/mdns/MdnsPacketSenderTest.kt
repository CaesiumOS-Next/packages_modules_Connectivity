/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.android.net.module.util.SharedLog
import com.android.server.connectivity.mdns.MdnsSender.PACKET_NOT_SENT
import com.android.server.connectivity.mdns.MdnsSender.PACKET_SENT
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.any
import org.mockito.Mockito.argThat
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

private const val DEFAULT_TIMEOUT_MS = 2000L

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
class MdnsPacketSenderTest {
    private val thread = HandlerThread(MdnsPacketSenderTest::class.simpleName)
    private val socket = mock(MdnsInterfaceSocket::class.java)
    private val sharedLog = mock(SharedLog::class.java)
    private val handler by lazy {
        Handler(thread.looper)
    }
    private val packetSender by lazy {
        MdnsPacketSender(thread.looper, socket, ByteArray(1500), sharedLog)
    }

    @Before
    fun setUp() {
        thread.start()
        doReturn(false).`when`(socket).hasJoinedIpv4()
        doReturn(false).`when`(socket).hasJoinedIpv6()
    }

    @After
    fun tearDown() {
        thread.quitSafely()
    }

    private fun <T> runningOnHandlerAndReturn(functor: (() -> T)): T {
        val future = CompletableFuture<T>()
        handler.post {
            future.complete(functor())
        }
        return future.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun sendNow(packet: MdnsPacket, address: InetSocketAddress):
            Int = runningOnHandlerAndReturn { packetSender.sendNow(packet, address) }

    @Test
    fun testSendNow() {
        val questions = listOf(MdnsAnyRecord(
                arrayOf("MyTestService", "_testservice", "_tcp", "local"), false /* unicast */))
        val packet = MdnsPacket(0 /* flags */, questions,
                listOf() /* answers */, listOf() /* authorityRecords */,
                listOf() /* additionalRecords */)
        val src = InetSocketAddress(InetAddresses.parseNumericAddress("192.0.2.123"), 5353)
        assertEquals(PACKET_NOT_SENT, sendNow(packet, src))
        verify(socket, never()).send(any())

        doReturn(true).`when`(socket).hasJoinedIpv4()
        assertEquals(PACKET_SENT, sendNow(packet, src))
        verify(socket).send(argThat { dPacket -> dPacket.address.equals(src.address) })

        doReturn(true).`when`(socket).hasJoinedIpv6()
        val srcV6 = InetSocketAddress(InetAddresses.parseNumericAddress("2001:db8::111"), 5353)
        assertEquals(PACKET_SENT, sendNow(packet, srcV6))
        verify(socket).send(argThat { dPacket -> dPacket.address.equals(srcV6.address) })
    }
}
