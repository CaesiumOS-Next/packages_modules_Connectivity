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
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock

private const val SERVICE_NAME_1 = "service-instance-1"
private const val SERVICE_NAME_2 = "service-instance-2"
private const val SERVICE_TYPE_1 = "_test1._tcp.local"
private const val SERVICE_TYPE_2 = "_test2._tcp.local"
private const val INTERFACE_INDEX = 999

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
class MdnsServiceCacheTest {
    private val network = mock(Network::class.java)
    private val serviceCache = MdnsServiceCache()

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

    private fun createResponse(serviceInstanceName: String, serviceType: String) = MdnsResponse(
        0, "$serviceInstanceName.$serviceType".split(".").toTypedArray(),
            INTERFACE_INDEX, network)
}
