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
import android.net.*
import android.net.EthernetManager.*
import android.platform.test.annotations.AppModeFull
import android.system.Os
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
import android.net.cts.EthernetManagerTest.EthernetCallbackRecord.CallbackEntry.InterfaceStateChanged
import kotlin.test.assertEquals

private const val TAG = "EthernetManagerTest"
private const val TIMEOUT_MS = 200L
private val DEFAULT_IP_CONFIGURATION = IpConfiguration(IpConfiguration.IpAssignment.DHCP,
    IpConfiguration.ProxySettings.NONE, null, null)


@AppModeFull(reason = "Instant apps can't access EthernetManager")
@RunWith(AndroidJUnit4::class)
class EthernetManagerTest {
    // EthernetManager is not updatable before S, so tests do not need to be backwards compatible
    @get:Rule
    val ignoreRule = DevSdkIgnoreRule(ignoreClassUpTo = SC_V2)

    private val context by lazy { InstrumentationRegistry.getInstrumentation().context }
    private val em by lazy { context.getSystemService(EthernetManager::class.java) }

    private val createdIfaces = ArrayList<TestNetworkInterface>()
    private val addedListeners = ArrayList<EthernetManager.Listener>()

    private open class EthernetCallbackRecord private constructor(
        private val history: ArrayTrackRecord<CallbackEntry>) : EthernetManager.Listener,
                TrackRecord<EthernetCallbackRecord.CallbackEntry> by history {
        constructor() : this(ArrayTrackRecord())

        val events = history.newReadHead()

        sealed class CallbackEntry {
            data class InterfaceStateChanged(
                val iface: String,
                val state: Int,
                val role: Int,
                val configuration: IpConfiguration?
            ) : CallbackEntry()
            data class AvailabilityChanged(
                val iface: String,
                val available: Boolean,
            ) : CallbackEntry()
        }

        override fun onAvailabilityChanged(iface: String?, isAvailable: Boolean) {
            add(CallbackEntry.AvailabilityChanged(iface!!, isAvailable));
        }

        override fun onInterfaceStateChanged(iface: String, state: Int, role: Int,
                                             cfg: IpConfiguration?) {
            add(InterfaceStateChanged(iface, state, role, cfg));
        }

        fun <T: CallbackEntry> expectCallback(expected: T) {
            val event = pollForNextCallback()
            assertEquals(expected, event)
        }

        fun expectCallback(iface : TestNetworkInterface, state: Int, role: Int) {
            expectCallback(InterfaceStateChanged(iface.interfaceName, state, role,
                if (state != STATE_ABSENT) DEFAULT_IP_CONFIGURATION else null))
        }

        fun pollForNextCallback(): CallbackEntry {
            return events.poll(TIMEOUT_MS) ?: fail("Did not receive callback after ${TIMEOUT_MS}ms")
        }

        fun assertNoCallback() {
            val cb = events.poll(TIMEOUT_MS)
            assertNull(cb, "Expected no callback but got $cb")
        }
    }

    @Test
    public fun testCallbacks() {
        // If an interface exists when the callback is registered, it is reported on registration.
        val iface = runAsShell(MANAGE_TEST_NETWORKS) {
            createInterface()
        }
        val callback = EthernetCallbackRecord()
        addListener(callback)
        callback.expectCallback(iface, STATE_LINK_UP, ROLE_CLIENT)

        // If an interface appears, existing callbacks see it.
        // TODO: fix the up/up/down/up callbacks and only send down/up.
        val iface2 = runAsShell(MANAGE_TEST_NETWORKS) {
            createInterface()
        }
        callback.expectCallback(iface2, STATE_LINK_UP, ROLE_CLIENT)
        callback.expectCallback(iface2, STATE_LINK_UP, ROLE_CLIENT)
        callback.expectCallback(iface2, STATE_LINK_DOWN, ROLE_CLIENT)
        callback.expectCallback(iface2, STATE_LINK_UP, ROLE_CLIENT)

        // Removing interfaces first sends link down, then STATE_ABSENT/ROLE_NONE.
        removeInterface(iface)
        callback.expectCallback(iface, STATE_LINK_DOWN, ROLE_CLIENT)
        callback.expectCallback(iface, STATE_ABSENT, ROLE_NONE)

        removeInterface(iface2)
        callback.expectCallback(iface2, STATE_LINK_DOWN, ROLE_CLIENT)
        callback.expectCallback(iface2, STATE_ABSENT, ROLE_NONE)
        callback.assertNoCallback()
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
                val fd = iface.fileDescriptor.fileDescriptor
                if (fd.valid()) Os.close(fd)
            }
            for (listener in addedListeners) {
                em.removeListener(listener)
            }
        }
    }

    private fun addListener(listener: EthernetManager.Listener) {
        em.addListener(listener)
        addedListeners.add(listener)
    }

    private fun createInterface(): TestNetworkInterface {
        val tnm = context.getSystemService(TestNetworkManager::class.java)
        val iface = tnm.createTapInterface()
        createdIfaces.add(iface)
        return iface
    }

    private fun removeInterface(iface: TestNetworkInterface) {
        Os.close(iface.fileDescriptor.fileDescriptor)
        createdIfaces.remove(iface)
    }
}