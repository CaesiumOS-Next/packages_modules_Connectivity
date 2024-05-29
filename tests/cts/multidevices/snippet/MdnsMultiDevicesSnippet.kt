/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.google.snippet.connectivity

import android.net.cts.util.NsdDiscoveryRecord
import android.net.cts.util.NsdDiscoveryRecord.DiscoveryEvent.DiscoveryStopped
import android.net.cts.util.NsdRegistrationRecord
import android.net.cts.util.NsdRegistrationRecord.RegistrationEvent.ServiceRegistered
import android.net.cts.util.NsdRegistrationRecord.RegistrationEvent.ServiceUnregistered
import android.net.cts.util.NsdResolveRecord
import android.net.cts.util.NsdResolveRecord.ResolveEvent.ServiceResolved
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.mobly.snippet.Snippet
import com.google.android.mobly.snippet.rpc.Rpc

private const val SERVICE_NAME = "MultiDevicesTest"
private const val SERVICE_TYPE = "_multi_devices._tcp"
private const val SERVICE_PORT = 12345

class MdnsMultiDevicesSnippet : Snippet {
    private val context = InstrumentationRegistry.getInstrumentation().getTargetContext()
    private val nsdManager = context.getSystemService(NsdManager::class.java)!!
    private val registrationRecord = NsdRegistrationRecord()
    private val discoveryRecord = NsdDiscoveryRecord()
    private val resolveRecord = NsdResolveRecord()

    @Rpc(description = "Register a mDns service")
    fun registerMDnsService() {
        val info = NsdServiceInfo()
        info.setServiceName(SERVICE_NAME)
        info.setServiceType(SERVICE_TYPE)
        info.setPort(SERVICE_PORT)
        info.setAttribute("key", "value")
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationRecord)
        registrationRecord.expectCallback<ServiceRegistered>()
    }

    @Rpc(description = "Unregister a mDns service")
    fun unregisterMDnsService() {
        nsdManager.unregisterService(registrationRecord)
        registrationRecord.expectCallback<ServiceUnregistered>()
    }

    @Rpc(description = "Ensure the discovery and resolution of the mDNS service")
    // Suppress the warning, as the NsdManager#resolveService() method is deprecated.
    @Suppress("DEPRECATION")
    fun ensureMDnsServiceDiscoveryAndResolution() {
        // Discover a mDns service that matches the test service
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryRecord)
        val info = discoveryRecord.waitForServiceDiscovered(SERVICE_NAME, SERVICE_TYPE)
        // Resolve the retrieved mDns service.
        nsdManager.resolveService(info, resolveRecord)
        resolveRecord.expectCallback<ServiceResolved>()
    }

    @Rpc(description = "Stop discovery")
    fun stopMDnsServiceDiscovery() {
        nsdManager.stopServiceDiscovery(discoveryRecord)
        discoveryRecord.expectCallbackEventually<DiscoveryStopped>()
    }
}
