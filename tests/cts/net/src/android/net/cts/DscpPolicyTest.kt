/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.net.cts

import android.Manifest.permission.MANAGE_TEST_NETWORKS
import android.Manifest.permission.NETWORK_SETTINGS
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.EthernetManager
import android.net.InetAddresses
import android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_TEST
import android.net.NetworkRequest
import android.net.TestNetworkInterface
import android.net.TestNetworkManager
import android.os.Build
import android.os.HandlerThread
import android.platform.test.annotations.AppModeFull
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.TapPacketReader
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.runAsShell
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet4Address
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

@AppModeFull(reason = "Instant apps cannot create test networks")
@RunWith(AndroidJUnit4::class)
class NetworkValidationTest {
    @JvmField
    @Rule
    val ignoreRule = DevSdkIgnoreRule(ignoreClassUpTo = Build.VERSION_CODES.S)

    private val context by lazy { InstrumentationRegistry.getInstrumentation().context }
    private val tnm by lazy { context.assertHasService(TestNetworkManager::class.java) }
    private val eth by lazy { context.assertHasService(EthernetManager::class.java) }
    private val cm by lazy { context.assertHasService(ConnectivityManager::class.java) }

    private val handlerThread = HandlerThread(NetworkValidationTest::class.java.simpleName)
    private val serverIpAddr = InetAddresses.parseNumericAddress("192.0.2.222") as Inet4Address
    private val clientIpAddr = InetAddresses.parseNumericAddress("192.0.2.111") as Inet4Address
    private val ethRequest = NetworkRequest.Builder()
            // ETHERNET|TEST transport networks do not have NET_CAPABILITY_TRUSTED
            .removeCapability(NET_CAPABILITY_TRUSTED)
            .addTransportType(TRANSPORT_ETHERNET)
            .addTransportType(TRANSPORT_TEST).build()
    private val ethRequestCb = TestableNetworkCallback()

    private lateinit var iface: TestNetworkInterface
    private lateinit var reader: TapPacketReader

    private var testSkipped = false

    @Before
    fun setUp() {
        // This test requires using a tap interface as an ethernet interface.
        val pm = context.getPackageManager()
        testSkipped = !pm.hasSystemFeature(PackageManager.FEATURE_ETHERNET) &&
                context.getSystemService(EthernetManager::class.java) == null
        assumeFalse(testSkipped)

        // Register a request so the network does not get torn down
        cm.requestNetwork(ethRequest, ethRequestCb)
        runAsShell(NETWORK_SETTINGS, MANAGE_TEST_NETWORKS) {
            eth.setIncludeTestInterfaces(true)
            // Keeping a reference to the test interface also makes sure the ParcelFileDescriptor
            // does not go out of scope, which would cause it to close the underlying FileDescriptor
            // in its finalizer.
            iface = tnm.createTapInterface()
        }

        handlerThread.start()
        reader = TapPacketReader(
                handlerThread.threadHandler,
                iface.fileDescriptor.fileDescriptor,
                MAX_PACKET_LENGTH)
        reader.startAsyncForTest()
    }

    @After
    fun tearDown() {
        if (testSkipped) return
        cm.unregisterNetworkCallback(ethRequestCb)

        runAsShell(NETWORK_SETTINGS) { eth.setIncludeTestInterfaces(false) }
    }

    @Test
    fun testDscpPolicyUpdatesPacket() {
        // Add DSCP Policy

        // Send packet matching policy

        // Check that packet on TAP iface has DSCP bit correctly added
    }
}