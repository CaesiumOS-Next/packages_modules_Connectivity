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

package com.android.server

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.L2capNetworkSpecifier
import android.net.L2capNetworkSpecifier.HEADER_COMPRESSION_6LOWPAN
import android.net.L2capNetworkSpecifier.ROLE_SERVER
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED
import android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED
import android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH
import android.net.NetworkCapabilities.TRANSPORT_TEST
import android.net.NetworkProvider
import android.net.NetworkProvider.NetworkOfferCallback
import android.net.NetworkRequest
import android.net.NetworkScore
import android.os.Build
import android.os.Looper
import com.android.server.L2capNetworkProvider
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.RecorderCallback.CallbackEntry.Reserved
import com.android.testutils.TestableNetworkCallback
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.MockitoAnnotations

private const val PSM = 0x85
private val REMOTE_MAC = byteArrayOf(1, 2, 3, 4, 5, 6)

@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.R)
class CSL2capProviderTest : CSTest() {
    @Mock private lateinit var btAdapter: BluetoothAdapter
    @Mock private lateinit var btServerSocket: BluetoothServerSocket
    @Mock private lateinit var btSocket: BluetoothSocket
    @Mock private lateinit var providerDeps: L2capNetworkProvider.Dependencies

    private lateinit var provider: L2capNetworkProvider

    /** Provider proxy that adds TRANSPORT_TEST to all network offers */
    class TestProxyNetworkProvider(
        context: Context,
        looper: Looper,
        name: String
    ) : NetworkProvider(context, looper, name) {
        override fun registerNetworkOffer(score: NetworkScore, caps: NetworkCapabilities,
                executor: Executor, callback: NetworkOfferCallback) {
            val updatedCaps = NetworkCapabilities.Builder(caps)
                    .addTransportType(TRANSPORT_TEST)
                    .build()
            super.registerNetworkOffer(score, updatedCaps, executor, callback)
        }
    }

    @Before
    fun innerSetUp() {
        MockitoAnnotations.initMocks(this)

        doReturn(btAdapter).`when`(bluetoothManager).getAdapter()
        doReturn(btServerSocket).`when`(btAdapter).listenUsingInsecureL2capChannel()
        doReturn(PSM).`when`(btServerSocket).getPsm();

        // Add TRANSPORT_TEST to all offered networks, so the test can run against the local
        // instance.
        doAnswer { i ->
            val args = i.arguments
            TestProxyNetworkProvider(args[0] as Context, args[1] as Looper, "L2capNetworkProvider")
        }.`when`(providerDeps).getNetworkProvider(any(), any())
        // TODO: inject a handler thread that this test can wait on.
        `when`(providerDeps.getHandlerThread()).thenCallRealMethod()

        provider = L2capNetworkProvider(providerDeps, context)
        provider.start()
    }

    private fun reserveNetwork(nr: NetworkRequest) = TestableNetworkCallback().also {
        cm.reserveNetwork(nr, csHandler, it)
    }

    private fun requestNetwork(nr: NetworkRequest) = TestableNetworkCallback().also {
        cm.requestNetwork(nr, it, csHandler)
    }

    @Test
    fun testReservation() {
        val l2capServerSpecifier = L2capNetworkSpecifier.Builder()
                .setRole(ROLE_SERVER)
                .setHeaderCompression(HEADER_COMPRESSION_6LOWPAN)
                .build()
        val l2capReservation = NetworkRequest.Builder()
                .addTransportType(TRANSPORT_BLUETOOTH)
                .addTransportType(TRANSPORT_TEST)
                .removeCapability(NET_CAPABILITY_TRUSTED)
                .removeCapability(NET_CAPABILITY_NOT_RESTRICTED)
                .setNetworkSpecifier(l2capServerSpecifier)
                .build()
        val reservationCb = reserveNetwork(l2capReservation)

        val reservedCaps = reservationCb.expect<Reserved>().caps
        val reservedSpec = reservedCaps.networkSpecifier as L2capNetworkSpecifier

        assertEquals(PSM, reservedSpec.getPsm())
        assertEquals(HEADER_COMPRESSION_6LOWPAN, reservedSpec.headerCompression)
        assertNull(reservedSpec.remoteAddress)

        reservationCb.assertNoCallback()
    }
}
