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
import android.net.nsd.NsdServiceInfo
import android.os.HandlerThread
import android.os.Looper
import com.android.server.connectivity.mdns.MdnsRecordRepository.Dependencies
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val TEST_SERVICE_ID = 42
private const val TEST_PORT = 12345
// TODO: change hostname calculation
private val TEST_HOSTNAME = arrayOf("Android_010203040506", "local")
private val TEST_ADDRESSES = arrayOf(
        parseNumericAddress("192.0.2.111"),
        parseNumericAddress("2001:db8::111"),
        parseNumericAddress("2001:db8::222"))

@RunWith(DevSdkIgnoreRunner::class)
class MdnsRecordRepositoryTest {
    // NetworkInterface is final and cannot be mocked; take any interface, as the dependencies
    // object is used to query it anyway.
    private val testIface = NetworkInterface.getNetworkInterfaces().let {
        assertTrue(it.hasMoreElements(), "No network interface; expected at least loopback")
        it.nextElement()
    }
    private val thread = HandlerThread(MdnsRecordRepositoryTest::class.simpleName)
    private val deps = object : Dependencies() {
        override fun getHostname() = TEST_HOSTNAME
        override fun getInterfaceInetAddresses(iface: NetworkInterface) =
                Collections.enumeration(TEST_ADDRESSES.toList())
    }

    @Before
    fun setUp() {
        thread.start()
    }

    @After
    fun tearDown() {
        thread.quitSafely()
    }

    @Test
    fun testGetReply() {
        val repository = MdnsRecordRepository(testIface, thread.looper, deps)
        val probingInfo = repository.addService(TEST_SERVICE_ID, NsdServiceInfo().apply {
            serviceType = "_testservice._tcp"
            serviceName = "MyTestService"
            port = TEST_PORT
        })
        repository.onProbingSucceeded(probingInfo)

        val questions = listOf(MdnsPointerRecord(arrayOf("_testservice", "_tcp", "local"),
                0L /* receiptTimeMillis */,
                false /* cacheFlush */,
                // TTL and data is empty for a question
                0L /* ttlMillis */,
                null /* pointer */))
        val query = MdnsPacket(0 /* flags */, questions, listOf() /* answers */,
                listOf() /* authorityRecords */, listOf() /* additionalRecords */)
        val src = InetSocketAddress(parseNumericAddress("192.0.2.123"), 5353)
        val reply = repository.getReply(query, src)

        assertNotNull(reply)
        // Source address is IPv4
        // TODO: test for scope if it needs to be set
        assertEquals(MdnsConstants.getMdnsIPv4Address(), reply.destination.address)
        assertEquals(MdnsConstants.MDNS_PORT, reply.destination.port)

        // TTLs as per RFC6762 10.
        val longTtl = 4_500_000L
        val shortTtl = 120_000L
        val serviceName = arrayOf("MyTestService", "_testservice", "_tcp", "local")

        assertEquals(listOf(
                MdnsPointerRecord(
                        arrayOf("_testservice", "_tcp", "local"),
                        0L /* receiptTimeMillis */,
                        false /* cacheFlush */,
                        longTtl,
                        serviceName),
        ), reply.answers)

        assertEquals(listOf(
                MdnsTextRecord(
                        serviceName,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        longTtl,
                        listOf() /* entries */),
                MdnsServiceRecord(
                        serviceName,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        shortTtl,
                        0 /* servicePriority */,
                        0 /* serviceWeight */,
                        TEST_PORT,
                        TEST_HOSTNAME),
                MdnsInetAddressRecord(
                        TEST_HOSTNAME,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        shortTtl,
                        TEST_ADDRESSES[0]),
                MdnsInetAddressRecord(
                        TEST_HOSTNAME,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        shortTtl,
                        TEST_ADDRESSES[1]),
                MdnsInetAddressRecord(
                        TEST_HOSTNAME,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        shortTtl,
                        TEST_ADDRESSES[2]),
                MdnsNsecRecord(
                        serviceName,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        longTtl,
                        serviceName /* nextDomain */,
                        intArrayOf(MdnsRecord.TYPE_TXT, MdnsRecord.TYPE_SRV)),
                MdnsNsecRecord(
                        TEST_HOSTNAME,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        shortTtl,
                        TEST_HOSTNAME /* nextDomain */,
                        intArrayOf(MdnsRecord.TYPE_A, MdnsRecord.TYPE_AAAA)),
        ), reply.additionalAnswers)
    }
}