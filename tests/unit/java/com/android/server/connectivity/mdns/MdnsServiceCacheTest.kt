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
import android.os.Handler
import android.os.HandlerThread
import com.android.net.module.util.ArrayTrackRecord
import com.android.server.connectivity.mdns.MdnsServiceCacheTest.ExpiredRecord.ExpiredEvent.ServiceRecordExpired
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.waitForIdle
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.After
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
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
private const val DEFAULT_TIMEOUT_MS = 2000L
private const val NO_CALLBACK_TIMEOUT_MS = 200L
private const val TEST_ELAPSED_REALTIME_MS = 123L

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
class MdnsServiceCacheTest {
    private val network = mock(Network::class.java)
    private val thread = HandlerThread(MdnsServiceCacheTest::class.simpleName)
    private val clock = mock(MdnsResponseDecoder.Clock::class.java)
    private val handler by lazy {
        Handler(thread.looper)
    }
    private val serviceCache by lazy {
        MdnsServiceCache(thread.looper, clock)
    }

    private class ExpiredRecord : MdnsServiceCache.ServiceExpiredCallback {
        val history = ArrayTrackRecord<ExpiredEvent>().newReadHead()

        sealed class ExpiredEvent {
            abstract val previousResponse: MdnsResponse
            abstract val newResponse: MdnsResponse?
            data class ServiceRecordExpired(
                    override val previousResponse: MdnsResponse,
                    override val newResponse: MdnsResponse?
            ) : ExpiredEvent()
        }

        override fun onServiceRecordExpired(
                previousResponse: MdnsResponse,
                newResponse: MdnsResponse?
        ) {
            history.add(ServiceRecordExpired(previousResponse, newResponse))
        }

        fun expectedServiceRecordExpired(
                serviceName: String,
                timeoutMs: Long = DEFAULT_TIMEOUT_MS
        ) {
            val event = history.poll(timeoutMs)
            assertNotNull(event)
            assertTrue(event is ServiceRecordExpired)
            assertEquals(serviceName, event.previousResponse.serviceInstanceName)
        }

        fun assertNoCallback() {
            val cb = history.poll(NO_CALLBACK_TIMEOUT_MS)
            assertNull(cb, "Expected no callback but got $cb")
        }
    }

    @Before
    fun setUp() {
        thread.start()
        doReturn(TEST_ELAPSED_REALTIME_MS).`when`(clock).elapsedRealtime()
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

    private fun addOrUpdateService(serviceType: String, network: Network, service: MdnsResponse):
            Unit = runningOnHandlerAndReturn {
        serviceCache.addOrUpdateService(serviceType, network, service) }

    private fun removeService(serviceName: String, serviceType: String, network: Network):
            Unit = runningOnHandlerAndReturn {
        serviceCache.removeService(serviceName, serviceType, network) }

    private fun getService(serviceName: String, serviceType: String, network: Network):
            MdnsResponse? = runningOnHandlerAndReturn {
        serviceCache.getCachedService(serviceName, serviceType, network) }

    private fun getServices(serviceType: String, network: Network): List<MdnsResponse> =
        runningOnHandlerAndReturn { serviceCache.getCachedServices(serviceType, network) }

    @Test
    fun testAddAndRemoveService() {
        addOrUpdateService(SERVICE_TYPE_1, network, createResponse(SERVICE_NAME_1, SERVICE_TYPE_1))
        var response = getService(SERVICE_NAME_1, SERVICE_TYPE_1, network)
        assertNotNull(response)
        assertEquals(SERVICE_NAME_1, response.serviceInstanceName)
        removeService(SERVICE_NAME_1, SERVICE_TYPE_1, network)
        response = getService(SERVICE_NAME_1, SERVICE_TYPE_1, network)
        assertNull(response)
    }

    @Test
    fun testGetCachedServices_multipleServiceTypes() {
        addOrUpdateService(SERVICE_TYPE_1, network, createResponse(SERVICE_NAME_1, SERVICE_TYPE_1))
        addOrUpdateService(SERVICE_TYPE_1, network, createResponse(SERVICE_NAME_2, SERVICE_TYPE_1))
        addOrUpdateService(SERVICE_TYPE_2, network, createResponse(SERVICE_NAME_2, SERVICE_TYPE_2))

        val responses1 = getServices(SERVICE_TYPE_1, network)
        assertEquals(2, responses1.size)
        assertTrue(responses1.stream().anyMatch { response ->
            response.serviceInstanceName == SERVICE_NAME_1
        })
        assertTrue(responses1.any { response ->
            response.serviceInstanceName == SERVICE_NAME_2
        })
        val responses2 = getServices(SERVICE_TYPE_2, network)
        assertEquals(1, responses2.size)
        assertTrue(responses2.any { response ->
            response.serviceInstanceName == SERVICE_NAME_2
        })

        removeService(SERVICE_NAME_2, SERVICE_TYPE_1, network)
        val responses3 = getServices(SERVICE_TYPE_1, network)
        assertEquals(1, responses3.size)
        assertTrue(responses3.any { response ->
            response.serviceInstanceName == SERVICE_NAME_1
        })
        val responses4 = getServices(SERVICE_TYPE_2, network)
        assertEquals(1, responses4.size)
        assertTrue(responses4.any { response ->
            response.serviceInstanceName == SERVICE_NAME_2
        })
    }

    @Test
    fun testServiceExpired() {
        // Register service expired callbacks
        val callback1 = ExpiredRecord()
        val callback2 = ExpiredRecord()
        runningOnHandlerAndReturn {
            serviceCache.registerServiceExpiredCallback(SERVICE_TYPE_1, network, callback1)
        }
        runningOnHandlerAndReturn {
            serviceCache.registerServiceExpiredCallback(SERVICE_TYPE_2, network, callback2)
        }
        // Add multiple services with different ttl time.
        addOrUpdateService(SERVICE_TYPE_1, network,
            createResponse(SERVICE_NAME_1, SERVICE_TYPE_1, 120001L /* ttlTime */))
        addOrUpdateService(SERVICE_TYPE_1, network,
            createResponse(SERVICE_NAME_2, SERVICE_TYPE_1, 120201L /* ttlTime */))
        addOrUpdateService(SERVICE_TYPE_2, network,
            createResponse(SERVICE_NAME_3, SERVICE_TYPE_2, 120101L /* ttlTime */))
        assertEquals(2, getServices(SERVICE_TYPE_1, network).size)
        assertEquals(1, getServices(SERVICE_TYPE_2, network).size)

        // Simulate the case where the first service is expired then trigger the service check.
        doReturn(TEST_ELAPSED_REALTIME_MS + 120100L).`when`(clock).elapsedRealtime()
        runningOnHandlerAndReturn { serviceCache.checkAndNotifyTtlExpiredServices() }

        // Expect SERVICE_NAME_1 expired.
        callback1.expectedServiceRecordExpired(SERVICE_NAME_1)
        callback2.assertNoCallback()
        assertEquals(1, getServices(SERVICE_TYPE_1, network).size)
        assertEquals(1, getServices(SERVICE_TYPE_2, network).size)

        // Simulate the case where the second service is expired then wait for the scheduling
        // service check.
        doReturn(TEST_ELAPSED_REALTIME_MS + 120200L).`when`(clock).elapsedRealtime()
        handler.waitForDelayedMessage(what = null, timeoutMs = 200L)

        // Expect SERVICE_NAME_3 expired.
        callback1.assertNoCallback()
        callback2.expectedServiceRecordExpired(SERVICE_NAME_3)
        assertEquals(1, getServices(SERVICE_TYPE_1, network).size)
        assertEquals(0, getServices(SERVICE_TYPE_2, network).size)
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
            TEST_ELAPSED_REALTIME_MS /* receiptTimeMillis */,
            false /* cacheFlush */,
            ttlTime /* ttlMillis */,
            serviceName)
        response.addPointerRecord(pointerRecord)

        // Set SRV record.
        val serviceRecord = MdnsServiceRecord(
            serviceName,
            TEST_ELAPSED_REALTIME_MS /* receiptTimeMillis */,
            false /* cacheFlush */,
            ttlTime /* ttlMillis */,
            0 /* servicePriority */,
            0 /* serviceWeight */,
            12345 /* port */,
            arrayOf("hostname"))
        response.serviceRecord = serviceRecord
        return response
    }

    private fun Handler.waitForDelayedMessage(what: Int?, timeoutMs: Long) {
        fun hasMatchingMessages() =
                if (what == null) hasMessagesOrCallbacks() else hasMessages(what)
        val expiry = System.currentTimeMillis() + timeoutMs
        var delay = 5L
        while (System.currentTimeMillis() < expiry && hasMatchingMessages()) {
            // None of Handler, Looper, Message and MessageQueue expose any way to retrieve
            // the time when the next (let alone the last) message will be processed, so
            // short of examining the internals with reflection sleep() is the only solution.
            Thread.sleep(delay)
            delay = (delay * 2)
                    .coerceAtMost(expiry - System.currentTimeMillis())
                    .coerceAtMost(200L)
        }

        val timeout = expiry - System.currentTimeMillis()
        if (timeout <= 0) Assert.fail("Delayed message did not process yet after ${timeoutMs}ms")
        waitForIdle(timeout)
    }
}
