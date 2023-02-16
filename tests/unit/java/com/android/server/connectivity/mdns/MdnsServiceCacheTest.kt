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
import android.os.SystemClock
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock

private const val SERVICE_NAME = "service-instance"
private const val SERVICE_TYPE = "_test._tcp.local"
private const val PORT = 5353
private const val INTERFACE_INDEX = 999

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
class MdnsServiceCacheTest {
    private val network = mock(Network::class.java)
    private val serviceCache = MdnsServiceCache()

    @Test
    fun testAddAndRemoveService() {
        serviceCache.addService(SERVICE_TYPE, createResponse())
        var response = serviceCache.getCachedService(SERVICE_NAME, SERVICE_TYPE, network)
        assertNotNull(response)
        assertEquals(SERVICE_NAME, response!!.serviceInstanceName)

        serviceCache.removeService(SERVICE_NAME, SERVICE_TYPE, network)
        response = serviceCache.getCachedService(SERVICE_NAME, SERVICE_TYPE, network)
        assertNull(response)
    }

    @Test
    fun testGetCachedServices_multipleServiceTypes() {
        val serviceType1 = "_test1._tcp.local"
        val serviceType2 = "_test2._tcp.local"
        val serviceName1 = "service-instance1"
        val serviceName2 = "service-instance2"
        serviceCache.addService(serviceType1, createResponse(serviceName1))
        serviceCache.addService(serviceType1, createResponse(serviceName2))
        serviceCache.addService(serviceType2, createResponse(serviceName2))

        val responses1 = serviceCache.getCachedServices(serviceType1, network)
        val responses2 = serviceCache.getCachedServices(serviceType2, network)
        assertEquals(2, responses1.size)
        assertEquals(1, responses2.size)

        serviceCache.removeService(serviceName2, serviceType1, network)
        val responses3 = serviceCache.getCachedServices(serviceType1, network)
        val responses4 = serviceCache.getCachedServices(serviceType2, network)
        assertEquals(1, responses3.size)
        assertEquals(1, responses4.size)
        assertEquals(serviceName1, responses3[0].serviceInstanceName)
        assertEquals(serviceName2, responses4[0].serviceInstanceName)
    }

    private fun createResponse(
        serviceInstanceName: String = SERVICE_NAME,
        port: Int = PORT,
        subtype: String = "ABCDE",
        interfaceIndex: Int = INTERFACE_INDEX,
        network: Network = this@MdnsServiceCacheTest.network
    ): MdnsResponse {
        val response = MdnsResponse(0, interfaceIndex, network)

        // Set PTR record
        val pointerRecord = MdnsPointerRecord(
            arrayOf(subtype, MdnsConstants.SUBTYPE_LABEL, "test") /* name */,
            0L /* receiptTimeMillis */,
            false /* cacheFlush */,
            120000L /* ttlMillis */,
            arrayOf(serviceInstanceName))
        response.addPointerRecord(pointerRecord)

        // Set SRV record.
        val serviceRecord = MdnsServiceRecord(
            arrayOf("service") /* name */,
            SystemClock.elapsedRealtime() /* receiptTimeMillis */,
            false /* cacheFlush */,
            1L /* ttlMillis */,
            0 /* servicePriority */,
            0 /* serviceWeight */,
            port,
            arrayOf("hostname"))
        response.serviceRecord = serviceRecord
        return response
    }
}
