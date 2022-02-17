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
package android.net.cts

import android.Manifest.permission.MANAGE_TEST_NETWORKS
import android.Manifest.permission.NETWORK_SETTINGS
import android.content.Context
import android.net.ConnectivityManager
import android.net.EthernetNetworkManagementException
import android.net.EthernetNetworkUpdateRequest
import android.net.InetAddresses
import android.net.IpConfiguration
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.MacAddress
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.StaticIpConfiguration
import android.net.TestNetworkInterface
import android.net.TestNetworkManager
import android.net.cts.EthernetManagerTest.EthernetStateListener.CallbackEntry.InterfaceStateChanged
import android.os.Handler
import android.os.HandlerExecutor
import android.os.Looper
import android.os.OutcomeReceiver
import android.platform.test.annotations.AppModeFull
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.android.net.module.util.ArrayTrackRecord
import com.android.net.module.util.TrackRecord
import com.android.networkstack.apishim.EthernetManagerShimImpl
import com.android.networkstack.apishim.common.EthernetManagerShim.InterfaceStateListener
import com.android.networkstack.apishim.common.EthernetManagerShim.ROLE_CLIENT
import com.android.networkstack.apishim.common.EthernetManagerShim.ROLE_NONE
import com.android.networkstack.apishim.common.EthernetManagerShim.STATE_ABSENT
import com.android.networkstack.apishim.common.EthernetManagerShim.STATE_LINK_DOWN
import com.android.networkstack.apishim.common.EthernetManagerShim.STATE_LINK_UP
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.RecorderCallback.CallbackEntry.Companion.LOST
import com.android.testutils.RecorderCallback.CallbackEntry.Available
import com.android.testutils.RecorderCallback.CallbackEntry.Lost
import com.android.testutils.RouterAdvertisementResponder
import com.android.testutils.SC_V2
import com.android.testutils.TapPacketReader
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.runAsShell
import com.android.testutils.waitForIdle
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private const val TAG = "EthernetManagerTest"
private const val TIMEOUT_MS = 1000L
private const val CB_TIMEOUT_MS = 30_000L
private const val NO_CALLBACK_TIMEOUT_MS = 200L
private const val LOCAL_IP_ADDRESS = "192.0.2.1/32"

private val DEFAULT_IP_CONFIGURATION = IpConfiguration(IpConfiguration.IpAssignment.DHCP,
        IpConfiguration.ProxySettings.NONE, null, null)
private val TEST_REQUEST = NetworkRequest.Builder()
        .clearCapabilities()
        .addTransportType(NetworkCapabilities.TRANSPORT_TEST)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        .build()
private val LOCAL_LINK_ADDRESS = LinkAddress(LOCAL_IP_ADDRESS)
private val LOCAL_STATIC_IP_CONFIG = StaticIpConfiguration.Builder()
        .setIpAddress(LOCAL_LINK_ADDRESS).build()
private val LOCAL_IP_CONFIG = IpConfiguration.Builder()
        .setStaticIpConfiguration(LOCAL_STATIC_IP_CONFIG).build()

@AppModeFull(reason = "Instant apps can't access EthernetManager")
@RunWith(AndroidJUnit4::class)
class EthernetManagerTest {
    // EthernetManager is not updatable before T, so tests do not need to be backwards compatible
    @get:Rule
    val ignoreRule = DevSdkIgnoreRule(ignoreClassUpTo = SC_V2)

    private val context by lazy { InstrumentationRegistry.getInstrumentation().context }
    private val em by lazy { EthernetManagerShimImpl.newInstance(context) }
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val executor: Executor = context.getMainExecutor()

    private val createdIfaces = ArrayList<EthernetTestInterface>()
    private val addedListeners = ArrayList<InterfaceStateListener>()
    private val addedCallbacks = ArrayList<TestableNetworkCallback>()

    private inner class EthernetTestInterface(
        context: Context,
        private val handler: Handler
    ) {
        private val tapInterface: TestNetworkInterface
        private val packetReader: TapPacketReader
        private val raResponder: RouterAdvertisementResponder
        val interfaceName get() = tapInterface.interfaceName

        init {
            tapInterface = runAsShell(MANAGE_TEST_NETWORKS) {
                val tnm = context.getSystemService(TestNetworkManager::class.java)
                tnm.createTapInterface(false /* bringUp */)
            }
            val mtu = NetworkInterface.getByName(tapInterface.interfaceName).getMTU()
            packetReader = TapPacketReader(handler, tapInterface.fileDescriptor.fileDescriptor, mtu)
            raResponder = RouterAdvertisementResponder(packetReader)
            raResponder.addRouterEntry(MacAddress.fromString("01:23:45:67:89:ab"),
                    InetAddresses.parseNumericAddress("fe80::abcd") as Inet6Address)

            packetReader.startAsyncForTest()
            raResponder.start()
        }

        fun destroy() {
            raResponder.stop()
            handler.post({ packetReader.stop() })
            handler.waitForIdle(TIMEOUT_MS)
        }
    }

    private open class EthernetStateListener private constructor(
        private val history: ArrayTrackRecord<CallbackEntry>
    ) : InterfaceStateListener,
                TrackRecord<EthernetStateListener.CallbackEntry> by history {
        constructor() : this(ArrayTrackRecord())

        val events = history.newReadHead()

        sealed class CallbackEntry {
            data class InterfaceStateChanged(
                val iface: String,
                val state: Int,
                val role: Int,
                val configuration: IpConfiguration?
            ) : CallbackEntry()
        }

        override fun onInterfaceStateChanged(
            iface: String,
            state: Int,
            role: Int,
            cfg: IpConfiguration?
        ) {
            add(InterfaceStateChanged(iface, state, role, cfg))
        }

        fun <T : CallbackEntry> expectCallback(expected: T): T {
            val event = pollForNextCallback()
            assertEquals(expected, event)
            return event as T
        }

        fun expectCallback(iface: EthernetTestInterface, state: Int, role: Int) {
            expectCallback(InterfaceStateChanged(iface.interfaceName, state, role,
                if (state != STATE_ABSENT) DEFAULT_IP_CONFIGURATION else null))
        }

        fun pollForNextCallback(): CallbackEntry {
            return events.poll(TIMEOUT_MS) ?: fail("Did not receive callback after ${TIMEOUT_MS}ms")
        }

        fun assertNoCallback() {
            val cb = events.poll(NO_CALLBACK_TIMEOUT_MS)
            assertNull(cb, "Expected no callback but got $cb")
        }
    }

    @Before
    fun setUp() {
        setIncludeTestInterfaces(true)
    }

    @After
    fun tearDown() {
        setIncludeTestInterfaces(false)
        for (iface in createdIfaces) {
            iface.destroy()
        }
        for (listener in addedListeners) {
            em.removeInterfaceStateListener(listener)
        }
        for (callback in addedCallbacks) {
            cm.unregisterNetworkCallback(callback)
        }
    }

    private fun addInterfaceStateListener(executor: Executor, listener: InterfaceStateListener) {
        em.addInterfaceStateListener(executor, listener)
        addedListeners.add(listener)
    }

    private fun createInterface(): EthernetTestInterface {
        return EthernetTestInterface(context, Handler(Looper.getMainLooper()))
    }

    private fun setIncludeTestInterfaces(value: Boolean) {
        runAsShell(NETWORK_SETTINGS) {
            em.setIncludeTestInterfaces(value)
        }
    }

    private fun removeInterface(iface: EthernetTestInterface) {
        iface.destroy()
        createdIfaces.remove(iface)
    }

    @Test
    public fun testCallbacks() {
        val executor = HandlerExecutor(Handler(Looper.getMainLooper()))

        // If an interface exists when the callback is registered, it is reported on registration.
        val iface = createInterface()
        val listener = EthernetStateListener()
        addInterfaceStateListener(executor, listener)
        listener.expectCallback(iface, STATE_LINK_UP, ROLE_CLIENT)

        // If an interface appears, existing callbacks see it.
        // TODO: fix the up/up/down/up callbacks and only send down/up.
        val iface2 = createInterface()
        listener.expectCallback(iface2, STATE_LINK_UP, ROLE_CLIENT)
        listener.expectCallback(iface2, STATE_LINK_UP, ROLE_CLIENT)
        listener.expectCallback(iface2, STATE_LINK_DOWN, ROLE_CLIENT)
        listener.expectCallback(iface2, STATE_LINK_UP, ROLE_CLIENT)

        // Removing interfaces first sends link down, then STATE_ABSENT/ROLE_NONE.
        removeInterface(iface)
        listener.expectCallback(iface, STATE_LINK_DOWN, ROLE_CLIENT)
        listener.expectCallback(iface, STATE_ABSENT, ROLE_NONE)

        removeInterface(iface2)
        listener.expectCallback(iface2, STATE_LINK_DOWN, ROLE_CLIENT)
        listener.expectCallback(iface2, STATE_ABSENT, ROLE_NONE)
        listener.assertNoCallback()
    }

    @Test
    public fun testGetInterfaceList() {
        setIncludeTestInterfaces(true)

        // Create two test interfaces and check the return list contains the interface names.
        val iface1 = createInterface()
        val iface2 = createInterface()
        var ifaces = em.getInterfaceList()
        assertTrue(ifaces.size > 0)
        assertTrue(ifaces.contains(iface1.interfaceName))
        assertTrue(ifaces.contains(iface2.interfaceName))

        // Remove one existing test interface and check the return list doesn't contain the
        // removed interface name.
        removeInterface(iface1)
        ifaces = em.getInterfaceList()
        assertFalse(ifaces.contains(iface1.interfaceName))
        assertTrue(ifaces.contains(iface2.interfaceName))

        removeInterface(iface2)
    }

    private class EthernetConfigCallback :
            OutcomeReceiver<String, EthernetNetworkManagementException> {
        private val mDone = CompletableFuture<String>()
        @Throws(Exception::class)
        fun expectComplete(): String {
            return mDone.get(CB_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }

        override fun onResult(result: String) {
            Log.i(TAG, "EthManagementListener resulting network $result")
            mDone.complete(result)
        }

        override fun onError(error: EthernetNetworkManagementException) {
            Log.e(TAG, "EthManagementListener resulting error $error")
            mDone.completeExceptionally(error)
        }
    }

    @Throws(Exception::class)
    private fun enableInterface(iface: String) {
        val connectListener = EthernetConfigCallback()
        runAsShell(MANAGE_TEST_NETWORKS, NETWORK_SETTINGS) {
            em.enableInterface(iface, executor, connectListener)
        }
        assertEquals(iface, connectListener.expectComplete())
    }

    @Throws(Exception::class)
    private fun disableInterface(iface: String) {
        val disconnectListener = EthernetConfigCallback()
        runAsShell(MANAGE_TEST_NETWORKS, NETWORK_SETTINGS) {
            em.disableInterface(iface, executor, disconnectListener)
        }
        assertEquals(iface, disconnectListener.expectComplete())
    }

    @Throws(Exception::class)
    private fun createTestNetwork(): String {
        val testIface = createInterface()

        Log.i(TAG, "Created test interface " + testIface.interfaceName)
        val nc = NetworkCapabilities.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_TEST).build()
        val iface = updateConfiguration(testIface.interfaceName, LOCAL_IP_CONFIG, nc)
        assertEquals(testIface.interfaceName, iface,
                "The test iface does not match the iface updated")
        return testIface.interfaceName
    }

    @Throws(Exception::class)
    private fun updateConfiguration(
        iface: String,
        ipConfig: IpConfiguration,
        nc: NetworkCapabilities
    ): String {
        val listener = EthernetConfigCallback()
        val request = EthernetNetworkUpdateRequest.Builder()
                .setIpConfiguration(ipConfig)
                .setNetworkCapabilities(nc)
                .build()
        runAsShell(MANAGE_TEST_NETWORKS, NETWORK_SETTINGS) {
            em.updateConfiguration(iface, request, executor, listener)
        }
        return listener.expectComplete().also { assertEquals(it, iface) }
    }

    private fun createStaticIpConfig(addr: LinkAddress): IpConfiguration {
        val staticIpConfig = StaticIpConfiguration.Builder().setIpAddress(addr).build()
        return IpConfiguration.Builder().setStaticIpConfiguration(staticIpConfig).build()
    }

    private fun requestNetwork(request: NetworkRequest, cb: TestableNetworkCallback) {
        cm.requestNetwork(request, cb)
        addedCallbacks.add(cb)
    }

    @Test
    fun testEnableDisableInterface() {
        val cb = TestableNetworkCallback()
        requestNetwork(TEST_REQUEST, cb)
        val iface: String = createTestNetwork()
        cb.expectAvailableThenValidatedCallbacks(CB_TIMEOUT_MS)

        disableInterface(iface)
        cb.expectCallback<Lost>(cb.lastAvailableNetwork!!, CB_TIMEOUT_MS)
        enableInterface(iface)
        cb.expectAvailableThenValidatedCallbacks(CB_TIMEOUT_MS)

        // Cleanup, only needed if the above statements are successful.
        disableInterface(iface)
        cb.expectCallback<Lost>(cb.lastAvailableNetwork!!, CB_TIMEOUT_MS)
    }

    @Test
    fun testUpdateConfiguration() {
        val cb = TestableNetworkCallback()
        requestNetwork(TEST_REQUEST, cb)
        val iface: String = createTestNetwork()
        cb.expectAvailableThenValidatedCallbacks(CB_TIMEOUT_MS)
        val n = kotlin.test.assertNotNull(cb.lastAvailableNetwork)

        val testCapability = NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED
        val nc = NetworkCapabilities.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_TEST)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .addCapability(testCapability).build()
        val newAddr = LinkAddress("192.0.2.2/32")
        updateConfiguration(iface, createStaticIpConfig(newAddr), nc)
        // UpdateConfiguration() currently does a restarts on the ethernet interface therefore lost
        // will be expected first before available, as part of the restart.
        cb.expectCallback<Lost>(n)

        cb.expectCallback<Available>()
        cb.expectCapabilitiesThat(cb.lastAvailableNetwork!!, CB_TIMEOUT_MS) {
            netcap: NetworkCapabilities -> netcap.hasCapability(testCapability)
        }
        cb.expectLinkPropertiesThat(cb.lastAvailableNetwork!!, CB_TIMEOUT_MS) {
            lp: LinkProperties ->
            val linkAddr = lp.linkAddresses[0]
            linkAddr.address == newAddr.address && linkAddr.prefixLength == newAddr.prefixLength
        }

        // Cleanup, only needed if the above statements are successful.
        disableInterface(iface)
        cb.eventuallyExpect(LOST, CB_TIMEOUT_MS)
    }
}
