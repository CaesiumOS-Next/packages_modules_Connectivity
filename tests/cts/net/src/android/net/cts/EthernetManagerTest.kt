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
import android.net.ConnectivityManager
import android.net.EthernetNetworkManagementException
import android.net.EthernetNetworkUpdateRequest
import android.net.IpConfiguration
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.StaticIpConfiguration
import android.net.TestNetworkInterface
import android.net.TestNetworkManager
import android.platform.test.annotations.AppModeFull
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.android.net.module.util.ArrayTrackRecord
import com.android.net.module.util.TrackRecord
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.SC_V2
import com.android.testutils.runAsShell
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertNull
import kotlin.test.fail
import android.net.cts.EthernetManagerTest.EthernetStateListener.CallbackEntry.InterfaceStateChanged
import android.os.Handler
import android.os.HandlerExecutor
import android.os.Looper
import android.os.OutcomeReceiver
import android.util.Log
import com.android.compatibility.common.util.SystemUtil.callWithShellPermissionIdentity
import com.android.networkstack.apishim.common.EthernetManagerShim.InterfaceStateListener
import com.android.networkstack.apishim.common.EthernetManagerShim.STATE_ABSENT
import com.android.networkstack.apishim.common.EthernetManagerShim.STATE_LINK_DOWN
import com.android.networkstack.apishim.common.EthernetManagerShim.STATE_LINK_UP
import com.android.networkstack.apishim.common.EthernetManagerShim.ROLE_CLIENT
import com.android.networkstack.apishim.common.EthernetManagerShim.ROLE_NONE
import com.android.networkstack.apishim.EthernetManagerShimImpl
import com.android.testutils.RecorderCallback
import com.android.testutils.TestableNetworkCallback
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val TAG = "EthernetManagerTest"
private const val TIMEOUT_MS = 1000L
private const val NO_CALLBACK_TIMEOUT_MS = 200L
private const val LOCAL_IP_ADDRESS = "192.168.26.5/32"

private val DEFAULT_IP_CONFIGURATION = IpConfiguration(IpConfiguration.IpAssignment.DHCP,
        IpConfiguration.ProxySettings.NONE, null, null)
private val TEST_REQUEST = NetworkRequest.Builder()
        .clearCapabilities()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        .addTransportType(NetworkCapabilities.TRANSPORT_TEST).build()
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

    private val createdIfaces = ArrayList<TestNetworkInterface>()
    private val addedListeners = ArrayList<InterfaceStateListener>()
    private val addedCallbacks = ArrayList<TestableNetworkCallback>()

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

        fun expectCallback(iface: TestNetworkInterface, state: Int, role: Int) {
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

    @Test
    public fun testCallbacks() {
        val executor = HandlerExecutor(Handler(Looper.getMainLooper()))

        // If an interface exists when the callback is registered, it is reported on registration.
        val iface = runAsShell(MANAGE_TEST_NETWORKS) {
            createDownedInterface()
        }
        val listener = EthernetStateListener()
        addInterfaceStateListener(executor, listener)
        listener.expectCallback(iface, STATE_LINK_UP, ROLE_CLIENT)

        // If an interface appears, existing callbacks see it.
        // TODO: fix the up/up/down/up callbacks and only send down/up.
        val iface2 = runAsShell(MANAGE_TEST_NETWORKS) {
            createDownedInterface()
        }
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

    @Before
    fun setUp() {
        runAsShell(MANAGE_TEST_NETWORKS, NETWORK_SETTINGS) {
            em.setIncludeTestInterfaces(true)
        }
    }

    @After
    fun tearDown() {
        runAsShell(MANAGE_TEST_NETWORKS, NETWORK_SETTINGS) {
            em.setIncludeTestInterfaces(false)
            for (iface in createdIfaces) {
                if (iface.fileDescriptor.fileDescriptor.valid()) iface.fileDescriptor.close()
            }
            for (listener in addedListeners) {
                em.removeInterfaceStateListener(listener)
            }
            for (callback in addedCallbacks) {
                cm.unregisterNetworkCallback(callback)
            }
        }
    }

    private fun addInterfaceStateListener(executor: Executor, listener: InterfaceStateListener) {
        em.addInterfaceStateListener(executor, listener)
        addedListeners.add(listener)
    }

    private fun createDownedInterface(): TestNetworkInterface {
        val tnm = context.getSystemService(TestNetworkManager::class.java)
        return tnm.createTapInterface(false /* bringUp */).also { createdIfaces.add(it) }
    }

    private fun removeInterface(iface: TestNetworkInterface) {
        iface.fileDescriptor.close()
        createdIfaces.remove(iface)
    }

    private fun doTestGetInterfaceList() {
        em.setIncludeTestInterfaces(true)

        // Create two test interfaces and check the return list contains the interface names.
        val iface1 = createDownedInterface()
        val iface2 = createDownedInterface()
        var ifaces = em.getInterfaceList()
        assertTrue(ifaces.size > 0)
        assertTrue(ifaces.contains(iface1.getInterfaceName()))
        assertTrue(ifaces.contains(iface2.getInterfaceName()))

        // Remove one existing test interface and check the return list doesn't contain the
        // removed interface name.
        removeInterface(iface1)
        ifaces = em.getInterfaceList()
        assertFalse(ifaces.contains(iface1.getInterfaceName()))
        assertTrue(ifaces.contains(iface2.getInterfaceName()))

        removeInterface(iface2)
    }

    @Test
    public fun testGetInterfaceList() {
        runAsShell(MANAGE_TEST_NETWORKS, NETWORK_SETTINGS) {
            doTestGetInterfaceList()
        }
    }

    @Throws(Exception::class)
    private fun connectNetwork(iface: String) {
        val connectListener = EthManagementListener()
        runAsShell(MANAGE_TEST_NETWORKS, NETWORK_SETTINGS) {
            em.enableInterface(iface, executor, connectListener)
        }
        connectListener.expectComplete()
    }

    @Throws(Exception::class)
    private fun disconnectNetwork(iface: String) {
        val disconnectListener = EthManagementListener()
        runAsShell(MANAGE_TEST_NETWORKS, NETWORK_SETTINGS) {
            em.disableInterface(iface, executor, disconnectListener)
        }
        disconnectListener.expectComplete()
    }

    @Throws(Exception::class)
    private fun createTestNetwork(): String {
        val testIface = callWithShellPermissionIdentity {
            createDownedInterface()
        }

        Log.i(TAG, "Created test interface " + testIface.getInterfaceName())
        val iface = updateConfigurationWithDefaults(testIface.getInterfaceName())
        assertEquals(testIface.getInterfaceName(), iface,
                "The test iface does not match the iface updated")
        return testIface.getInterfaceName()
    }

    @Throws(Exception::class)
    private fun updateConfigurationWithDefaults(iface: String): String {
        val nc = NetworkCapabilities.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_TEST).build()
        return updateConfiguration(iface, LOCAL_IP_CONFIG, nc)
    }

    @Throws(Exception::class)
    private fun updateConfiguration(
        iface: String,
        ipConfig: IpConfiguration,
        nc: NetworkCapabilities
    ): String {
        val listener = EthManagementListener()
        val request = EthernetNetworkUpdateRequest.Builder()
                .setIpConfiguration(ipConfig)
                .setNetworkCapabilities(nc)
                .build()
        runAsShell(MANAGE_TEST_NETWORKS, NETWORK_SETTINGS) {
            em.updateConfiguration(iface, request, executor, listener)
        }
        val ret: String = listener.expectComplete()
        assertNotNull("Provisioning test interface failed.", ret)
        return ret
    }

    private class EthManagementListener :
            OutcomeReceiver<String, EthernetNetworkManagementException> {
        private val mDone = CompletableFuture<String>()
        @Throws(Exception::class)
        fun expectComplete(): String {
            return mDone[TIMEOUT_MS, TimeUnit.MILLISECONDS]
        }

        override fun onResult(result: String) {
            Log.i(TAG, "EthManagementListener resulting network $result")
            mDone.complete(result)
        }

        override fun onError(error: EthernetNetworkManagementException) {
            Log.e(TAG, "EthManagementListener resulting error $error")
        }
    }

    private fun createIpConfig(addr: LinkAddress): IpConfiguration {
        val staticIpConfig = StaticIpConfiguration.Builder().setIpAddress(addr).build()
        return IpConfiguration.Builder().setStaticIpConfiguration(staticIpConfig).build()
    }

    private fun requestNetwork(request: NetworkRequest, cb: TestableNetworkCallback) {
        runAsShell(MANAGE_TEST_NETWORKS, NETWORK_SETTINGS) {
            cm.requestNetwork(request, cb)
        }
        addedCallbacks.add(cb)
    }

    @Test
    fun testConnectDisconnectNetwork() {
        val testNetworkCb = TestableNetworkCallback()
        requestNetwork(TEST_REQUEST, testNetworkCb)
        val iface: String = createTestNetwork()
        testNetworkCb.expectAvailableThenValidatedCallbacks(TIMEOUT_MS)

        disconnectNetwork(iface)
        testNetworkCb.expectCallback(RecorderCallback.CallbackEntry.LOST,
                testNetworkCb.lastAvailableNetwork, TIMEOUT_MS)
        connectNetwork(iface)
        testNetworkCb.expectAvailableThenValidatedCallbacks(TIMEOUT_MS)

        // Cleanup, only needed if the above statements are successful.
        disconnectNetwork(iface)
        testNetworkCb.expectCallback(RecorderCallback.CallbackEntry.LOST,
                testNetworkCb.lastAvailableNetwork, TIMEOUT_MS)
    }

    @Test
    fun testUpdateConfiguration() {
        val testNetworkCb = TestableNetworkCallback()
        requestNetwork(TEST_REQUEST, testNetworkCb)
        val iface: String = createTestNetwork()

        val testCapability = NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED
        val nc = NetworkCapabilities.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_TEST)
                .addCapability(testCapability).build()
        val ipAddress = "192.168.26.200/32"
        val newAddr = LinkAddress(ipAddress)
        updateConfiguration(iface, createIpConfig(newAddr), nc)

        testNetworkCb.expectCallback<RecorderCallback.CallbackEntry.Available>()
        testNetworkCb.expectCapabilitiesThat(testNetworkCb.lastAvailableNetwork!!, TIMEOUT_MS) {
            netcap: NetworkCapabilities -> netcap.hasCapability(testCapability)
        }
        testNetworkCb.expectLinkPropertiesThat(testNetworkCb.lastAvailableNetwork!!, TIMEOUT_MS) {
            lp: LinkProperties ->
            val linkAddr = lp.linkAddresses[0]
            linkAddr.address == newAddr.address && linkAddr.prefixLength == newAddr.prefixLength
        }

        disconnectNetwork(iface)
        testNetworkCb.eventuallyExpect(RecorderCallback.CallbackEntry.LOST, TIMEOUT_MS)
    }
}
