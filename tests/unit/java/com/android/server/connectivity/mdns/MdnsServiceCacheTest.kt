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

import android.net.Network
import android.os.Build
import com.android.net.module.util.ArrayTrackRecord
import com.android.server.connectivity.mdns.MdnsServiceCacheTest.ExpireRecord.ExpireEvent.ServiceExpired
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock

private const val SERVICE_NAME_1 = "service-instance-1"
private const val SERVICE_NAME_2 = "service-instance-2"
private const val SERVICE_NAME_3 = "service-instance-3"
private const val SERVICE_TYPE_1 = "_test1._tcp.local"
private const val SERVICE_TYPE_2 = "_test2._tcp.local"
private const val INTERFACE_INDEX = 999
private const val TEST_ELAPSED_REALTIME = 123L
private const val NO_CALLBACK_TIMEOUT_MS = 200L

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
class MdnsServiceCacheTest {
    private val network = mock(Network::class.java)
    private val clock = mock(MdnsResponseDecoder.Clock::class.java)
    private val serviceCache = MdnsServiceCache(clock)

    @Test
    fun testAddAndRemoveService() {
        serviceCache.addService(SERVICE_TYPE_1, createResponse(SERVICE_NAME_1, SERVICE_TYPE_1))
        var response = serviceCache.getCachedService(SERVICE_NAME_1, SERVICE_TYPE_1, network)
        assertNotNull(response)
        assertEquals(SERVICE_NAME_1, response!!.serviceInstanceName)
        serviceCache.removeService(SERVICE_NAME_1, SERVICE_TYPE_1, network)
        response = serviceCache.getCachedService(SERVICE_NAME_1, SERVICE_TYPE_1, network)
        assertNull(response)
    }

    @Test
    fun testGetCachedServices_multipleServiceTypes() {
        serviceCache.addService(SERVICE_TYPE_1, createResponse(SERVICE_NAME_1, SERVICE_TYPE_1))
        serviceCache.addService(SERVICE_TYPE_1, createResponse(SERVICE_NAME_2, SERVICE_TYPE_1))
        serviceCache.addService(SERVICE_TYPE_2, createResponse(SERVICE_NAME_2, SERVICE_TYPE_2))

        val responses1 = serviceCache.getCachedServices(SERVICE_TYPE_1, network)
        val responses2 = serviceCache.getCachedServices(SERVICE_TYPE_2, network)
        assertEquals(2, responses1.size)
        assertEquals(1, responses2.size)

        serviceCache.removeService(SERVICE_NAME_2, SERVICE_TYPE_1, network)
        val responses3 = serviceCache.getCachedServices(SERVICE_TYPE_1, network)
        val responses4 = serviceCache.getCachedServices(SERVICE_TYPE_2, network)
        assertEquals(1, responses3.size)
        assertEquals(1, responses4.size)
        assertTrue(responses3.stream().anyMatch {
                response -> response.serviceInstanceName == SERVICE_NAME_1
        })
        assertTrue(responses4.stream().anyMatch {
                response -> response.serviceInstanceName == SERVICE_NAME_2
        })
    }

    private class ExpireRecord : MdnsServiceCache.ServiceExpireCallback {
        val history = ArrayTrackRecord<ExpireEvent>().newReadHead()

        sealed class ExpireEvent {
            abstract val response: MdnsResponse
            data class ServiceExpired(override val response: MdnsResponse) : ExpireEvent()
        }

        override fun onServiceExpired(response: MdnsResponse) {
            history.add(ServiceExpired(response))
        }

        inline fun expectedServiceExpired(serviceName: String) {
            val event = history.poll(0L /* timeoutMs */)
            assertNotNull(event)
            assertTrue(event is ServiceExpired)
            if (event != null) {
                assertEquals(serviceName, event.response.serviceInstanceName)
            }
        }

        inline fun assertNoCallback(timeoutMs: Long = NO_CALLBACK_TIMEOUT_MS) {
            val cb = history.poll(timeoutMs)
            kotlin.test.assertNull(cb, "Expected no callback but got $cb")
        }
    }

    @Test
    fun testServiceExpire() {
        // Register service expire callbacks
        val callback1 = ExpireRecord()
        val callback2 = ExpireRecord()
        serviceCache.registerServiceExpireCallback(SERVICE_TYPE_1, callback1)
        serviceCache.registerServiceExpireCallback(SERVICE_TYPE_2, callback2)
        // Add multiple services with different ttl time.
        serviceCache.addService(SERVICE_TYPE_1,
            createResponse(SERVICE_NAME_1, SERVICE_TYPE_1, 1L /* ttlTime */))
        serviceCache.addService(SERVICE_TYPE_1,
            createResponse(SERVICE_NAME_2, SERVICE_TYPE_1, 21L /* ttlTime */))
        serviceCache.addService(SERVICE_TYPE_2,
            createResponse(SERVICE_NAME_3, SERVICE_TYPE_2, 11L /* ttlTime */))
        assertEquals(2, serviceCache.getCachedServices(SERVICE_TYPE_1, network).size)
        assertEquals(1, serviceCache.getCachedServices(SERVICE_TYPE_2, network).size)

        // Simulate the case where the response is after TTL then check expired services.
        doReturn(TEST_ELAPSED_REALTIME + 10L).`when`(clock).elapsedRealtime()
        serviceCache.checkAndNotifyTtlExpiredServices()
        // Expect SERVICE_NAME_1 expired.
        callback1.expectedServiceExpired(SERVICE_NAME_1)
        callback2.assertNoCallback()
        assertEquals(1, serviceCache.getCachedServices(SERVICE_TYPE_1, network).size)
        assertEquals(1, serviceCache.getCachedServices(SERVICE_TYPE_2, network).size)

        // Simulate the case where the response is after TTL then check expired services.
        doReturn(TEST_ELAPSED_REALTIME + 20L).`when`(clock).elapsedRealtime()
        serviceCache.checkAndNotifyTtlExpiredServices()
        // Expect SERVICE_NAME_3 expired.
        callback1.assertNoCallback()
        callback2.expectedServiceExpired(SERVICE_NAME_3)
        assertEquals(1, serviceCache.getCachedServices(SERVICE_TYPE_1, network).size)
        assertEquals(0, serviceCache.getCachedServices(SERVICE_TYPE_2, network).size)
    }

    private fun createResponse(
        serviceInstanceName: String,
        serviceType: String,
        ttlTime: Long = 120000L
    ): MdnsResponse {
        val serviceName = "$serviceInstanceName.$serviceType".split(".").toTypedArray()
        val response = MdnsResponse(0, serviceName, INTERFACE_INDEX, network)

        // Set PTR record
        val pointerRecord = MdnsPointerRecord(
            serviceType.split(".").toTypedArray(),
            TEST_ELAPSED_REALTIME,
            false /* cacheFlush */,
            ttlTime /* ttlMillis */,
            serviceName)
        response.addPointerRecord(pointerRecord)

        // Set SRV record.
        val serviceRecord = MdnsServiceRecord(
            serviceName,
            TEST_ELAPSED_REALTIME /* receiptTimeMillis */,
            false /* cacheFlush */,
            ttlTime /* ttlMillis */,
            0 /* servicePriority */,
            0 /* serviceWeight */,
            12345 /* port */,
            arrayOf("hostname"))
        response.serviceRecord = serviceRecord
        return response
    }
}
