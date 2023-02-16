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
import android.os.HandlerThread
import android.os.SystemClock
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private const val SERVICE_NAME_1 = "service-instance-1"
private const val SERVICE_NAME_2 = "service-instance-2"
private const val SERVICE_NAME_3 = "service-instance-3"
private const val SERVICE_TYPE_1 = "_test1._tcp.local"
private const val SERVICE_TYPE_2 = "_test2._tcp.local"
private const val PORT = 5353
private const val INTERFACE_INDEX = 999

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
class MdnsServiceCacheTest {
    private val network = mock(Network::class.java)
    private val thread = HandlerThread(MdnsServiceCacheTest::class.simpleName)
    private val serviceCache by lazy {
        MdnsServiceCache(thread.looper)
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
    fun testAddAndRemoveService() {
        serviceCache.addService(SERVICE_TYPE_1, createResponse())
        var response = serviceCache.getCachedService(SERVICE_NAME_1, SERVICE_TYPE_1, network)
        assertNotNull(response)
        assertEquals(SERVICE_NAME_1, response!!.serviceInstanceName)

        serviceCache.removeService(SERVICE_NAME_1, SERVICE_TYPE_1, network)
        response = serviceCache.getCachedService(SERVICE_NAME_1, SERVICE_TYPE_1, network)
        assertNull(response)
    }

    @Test
    fun testGetCachedServices_multipleServiceTypes() {
        serviceCache.addService(SERVICE_TYPE_1, createResponse(SERVICE_NAME_1))
        serviceCache.addService(SERVICE_TYPE_1, createResponse(SERVICE_NAME_2))
        serviceCache.addService(SERVICE_TYPE_2, createResponse(SERVICE_NAME_2))

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

    class TestExpireCallback : MdnsServiceCache.ServiceExpireCallback {
        val future = CompletableFuture<MdnsResponse>()
        override fun onServiceExpired(response: MdnsResponse) {
            future.complete(response)
        }

        fun getExpiredService(): MdnsResponse = future.get(200L, TimeUnit.MILLISECONDS)
    }

    @Test
    fun testServiceExpire() {
        // Register a service expire callback for SERVICE_TYPE_1
        val callback1 = TestExpireCallback()
        serviceCache.registerServiceExpireCallback(SERVICE_TYPE_1, callback1)
        // Add multiple services with different ttl time.
        serviceCache.addService(SERVICE_TYPE_1,
            createResponse(serviceInstanceName = SERVICE_NAME_1, ttlTime = 20L))
        serviceCache.addService(SERVICE_TYPE_1,
            createResponse(serviceInstanceName = SERVICE_NAME_2, ttlTime = 120L))
        serviceCache.addService(SERVICE_TYPE_2,
            createResponse(serviceInstanceName = SERVICE_NAME_3, ttlTime = 50L))
        // Expect SERVICE_NAME_1 expired.
        val expiredService1 = callback1.getExpiredService()
        assertEquals(SERVICE_NAME_1, expiredService1.serviceInstanceName)

        // Register another service expire callback for SERVICE_TYPE_2
        val callback2 = TestExpireCallback()
        serviceCache.registerServiceExpireCallback(SERVICE_TYPE_2, callback2)
        // Expect SERVICE_NAME_3 expired.
        val expiredService2 = callback2.getExpiredService()
        assertEquals(SERVICE_NAME_3, expiredService2.serviceInstanceName)

        // Check remaining services
        val responses1 = serviceCache.getCachedServices(SERVICE_TYPE_1, network)
        val responses2 = serviceCache.getCachedServices(SERVICE_TYPE_2, network)
        assertEquals(1, responses1.size)
        assertEquals(0, responses2.size)
    }

    private fun createResponse(
        serviceInstanceName: String = SERVICE_NAME_1,
        port: Int = PORT,
        subtype: String = "ABCDE",
        ttlTime: Long = 120000L,
        interfaceIndex: Int = INTERFACE_INDEX,
        network: Network = this@MdnsServiceCacheTest.network
    ): MdnsResponse {
        val response = MdnsResponse(0, interfaceIndex, network)

        // Set PTR record
        val pointerRecord = MdnsPointerRecord(
            arrayOf(subtype, MdnsConstants.SUBTYPE_LABEL, "test") /* name */,
            0L /* receiptTimeMillis */,
            false /* cacheFlush */,
            ttlTime /* ttlMillis */,
            arrayOf(serviceInstanceName))
        response.addPointerRecord(pointerRecord)

        // Set SRV record.
        val serviceRecord = MdnsServiceRecord(
            arrayOf("service") /* name */,
            SystemClock.elapsedRealtime() /* receiptTimeMillis */,
            false /* cacheFlush */,
            ttlTime /* ttlMillis */,
            0 /* servicePriority */,
            0 /* serviceWeight */,
            port,
            arrayOf("hostname"))
        response.serviceRecord = serviceRecord
        return response
    }
}
