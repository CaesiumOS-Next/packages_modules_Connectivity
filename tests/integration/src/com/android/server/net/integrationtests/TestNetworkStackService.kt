/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.net.integrationtests

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.DnsResolver
import android.net.DnsResolver.TYPE_A
import android.net.DnsResolver.TYPE_AAAA
import android.net.INetworkMonitorCallbacks
import android.net.InetAddresses
import android.net.Network
import android.net.metrics.IpConnectivityLog
import android.net.util.SharedLog
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import com.android.networkstack.netlink.TcpSocketTracker
import com.android.server.NetworkStackService
import com.android.server.NetworkStackService.NetworkMonitorConnector
import com.android.server.NetworkStackService.NetworkStackConnector
import com.android.server.connectivity.NetworkMonitor
import com.android.server.net.integrationtests.NetworkStackInstrumentationService.InstrumentationConnector
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executor

private const val TEST_NETID = 42

/**
 * Android service that can return an [android.net.INetworkStackConnector] which can be instrumented
 * through [NetworkStackInstrumentationService].
 * Useful in tests to create test instrumented NetworkStack components that can receive
 * instrumentation commands through [NetworkStackInstrumentationService].
 */
class TestNetworkStackService : Service() {
    override fun onBind(intent: Intent): IBinder = TestNetworkStackConnector(makeTestContext())

    private fun makeTestContext() = spy(applicationContext).also {
        doReturn(mock(IBinder::class.java)).`when`(it).getSystemService(Context.NETD_SERVICE)
    }

    private class TestPermissionChecker : NetworkStackService.PermissionChecker() {
        override fun enforceNetworkStackCallingPermission() = Unit
    }

    private class NetworkMonitorDeps(private val privateDnsBypassNetwork: Network) :
            NetworkMonitor.Dependencies() {
        override fun getPrivateDnsBypassNetwork(network: Network?) = privateDnsBypassNetwork

        private val handlerThread = HandlerThread("TestDnsResolver").apply { start() }
        private val handler = Handler(handlerThread.getLooper())

        private fun dnsAnswer(
            qtype: Int,
            executor: Executor,
            callback: DnsResolver.Callback<List<InetAddress>>
        ) {
            val answers = when (qtype) {
                TYPE_AAAA -> listOf("2001:db8::1")
                TYPE_A -> listOf("192.0.2.1")
                else -> listOf("2001:db8::1", "192.0.2.1")
            }.map { InetAddresses.parseNumericAddress(it) }
            handler.post { executor.execute { callback.onAnswer(answers, 0) } }
        }

        override fun getDnsResolver(): DnsResolver {
            val TYPE_ANY = 255
            val resolver = mock(DnsResolver::class.java)
            // A or AAAA lookups
            doAnswer { inv ->
                val qtype = inv.getArgument<Int>(2)
                val executor = inv.getArgument<Executor>(4)
                val callback = inv.getArgument<DnsResolver.Callback<List<InetAddress>>>(6)
                dnsAnswer(qtype, executor, callback)
                null
            }.`when`(resolver).query(any(), anyString(), anyInt(), anyInt(), any(), any(), any())
            // "ADDRCONFIG" lookups
            doAnswer { inv ->
                val executor = inv.getArgument<Executor>(3)
                val callback = inv.getArgument<DnsResolver.Callback<List<InetAddress>>>(5)
                dnsAnswer(TYPE_ANY, executor, callback)
                null
            }.`when`(resolver).query(any(), anyString(), anyInt(), any(), any(), any())
            return resolver
        }
    }

    /**
     * Mock [HttpURLConnection] to simulate reply from a server.
     */
    private class MockConnection(
        url: URL,
        private val response: HttpResponse
    ) : HttpURLConnection(url) {
        private val responseBytes = response.content.toByteArray(StandardCharsets.UTF_8)
        override fun getResponseCode() = response.responseCode
        override fun getContentLengthLong() = responseBytes.size.toLong()
        override fun getHeaderField(field: String): String? {
            return when (field) {
                "location" -> response.redirectUrl
                else -> null
            }
        }
        override fun getInputStream() = ByteArrayInputStream(responseBytes)
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }

    private inner class TestNetworkStackConnector(context: Context) : NetworkStackConnector(
            context, TestPermissionChecker(), NetworkStackService.Dependencies()) {

        private val network = TestNetwork(TEST_NETID)
        private val privateDnsBypassNetwork = TestNetwork(TEST_NETID)

        private inner class TestNetwork(netId: Int) : Network(netId) {
            override fun openConnection(url: URL) = MockConnection(
                    url, InstrumentationConnector.processRequest(url))
            override fun getAllByName(host: String): Array<InetAddress> {
                return listOf("2001:db8::1", "192.0.2.1").map {
                    InetAddresses.parseNumericAddress(it) }.toTypedArray()
            }
        }

        override fun makeNetworkMonitor(
            network: Network,
            name: String?,
            cb: INetworkMonitorCallbacks
        ) {
            val nm = NetworkMonitor(this@TestNetworkStackService, cb,
                    this.network,
                    mock(IpConnectivityLog::class.java), mock(SharedLog::class.java),
                    mock(NetworkStackService.NetworkStackServiceManager::class.java),
                    NetworkMonitorDeps(privateDnsBypassNetwork),
                    mock(TcpSocketTracker::class.java))
            cb.onNetworkMonitorCreated(NetworkMonitorConnector(nm, TestPermissionChecker()))
        }
    }
}
