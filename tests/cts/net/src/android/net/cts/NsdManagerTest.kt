/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.net.nsd.NsdManager
import android.net.nsd.NsdManager.DiscoveryListener
import android.net.nsd.NsdManager.ResolveListener
import android.net.nsd.NsdServiceInfo
import android.platform.test.annotations.AppModeFull
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.android.net.module.util.ArrayTrackRecord
import com.android.testutils.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.Arrays
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private const val TAG = "NsdManagerTest"
private const val SERVICE_TYPE = "_nmt._tcp"
private const val TIMEOUT_MS = 2000L
private const val DBG = false

@AppModeFull(reason = "Socket cannot bind in instant app mode")
@RunWith(AndroidJUnit4::class)
class NsdManagerTest {
    private val context by lazy { InstrumentationRegistry.getInstrumentation().context }
    private val nsdManager by lazy { context.getSystemService(NsdManager::class.java) }

    private interface NsdEvent
    private open class HistoryListener<T : NsdEvent> {
        protected val history = ArrayTrackRecord<T>()
        val nextEvents = history.newReadHead()

        protected fun add(event: T) {
            history.add(event)
        }

        inline fun <reified V : NsdEvent> waitForCallbackEventually(
            crossinline predicate: (V) -> Boolean = { true }
        ): V = nextEvents.poll(TIMEOUT_MS) { e -> e is V && predicate(e) } as V?
                ?: fail("Callback for ${V::class.java.simpleName} not seen after $TIMEOUT_MS ms")

        inline fun <reified V : NsdEvent> waitForCallback(): V {
            val nextEvent = nextEvents.poll(TIMEOUT_MS)
            assertNotNull(nextEvent, "No callback received after $TIMEOUT_MS ms")
            assertTrue(nextEvent is V, "Expected ${V::class.java.simpleName} but got " +
                    nextEvent.javaClass.simpleName)
            return nextEvent
        }
    }

    private interface RegistrationEvent : NsdEvent
    private data class RegistrationFailed(val serviceInfo: NsdServiceInfo, val errorCode: Int)
        : RegistrationEvent
    private data class UnregistrationFailed(val serviceInfo: NsdServiceInfo, val errorCode: Int)
        : RegistrationEvent
    private data class ServiceRegistered(val serviceInfo: NsdServiceInfo) : RegistrationEvent
    private data class ServiceUnregistered(val serviceInfo: NsdServiceInfo) : RegistrationEvent

    private class TestRegistrationListener
        : NsdManager.RegistrationListener, HistoryListener<RegistrationEvent>() {
        override fun onRegistrationFailed(si: NsdServiceInfo, err: Int) =
                add(RegistrationFailed(si, err))

        override fun onUnregistrationFailed(si: NsdServiceInfo, err: Int) =
                add(UnregistrationFailed(si, err))

        override fun onServiceRegistered(si: NsdServiceInfo) = add(ServiceRegistered(si))
        override fun onServiceUnregistered(si: NsdServiceInfo) = add(ServiceUnregistered(si))
    }

    private interface DiscoveryEvent : NsdEvent
    private data class StartDiscoveryFailed(val serviceType: String, val errorCode: Int)
        : DiscoveryEvent
    private data class StopDiscoveryFailed(val serviceType: String, val errorCode: Int)
        : DiscoveryEvent
    private data class DiscoveryStarted(val serviceType: String) : DiscoveryEvent
    private data class DiscoveryStopped(val serviceType: String) : DiscoveryEvent
    private data class ServiceFound(val serviceInfo: NsdServiceInfo) : DiscoveryEvent
    private data class ServiceLost(val serviceInfo: NsdServiceInfo) : DiscoveryEvent

    private class TestDiscoveryListener : DiscoveryListener, HistoryListener<DiscoveryEvent>() {
        override fun onStartDiscoveryFailed(serviceType: String, err: Int) =
                add(StartDiscoveryFailed(serviceType, err))

        override fun onStopDiscoveryFailed(serviceType: String, err: Int) =
                add(StopDiscoveryFailed(serviceType, err))

        override fun onDiscoveryStarted(serviceType: String) = add(DiscoveryStarted(serviceType))
        override fun onDiscoveryStopped(serviceType: String) = add(DiscoveryStopped(serviceType))
        override fun onServiceFound(si: NsdServiceInfo) = add(ServiceFound(si))
        override fun onServiceLost(si: NsdServiceInfo) = add(ServiceLost(si))
    }

    private interface ResolveEvent : NsdEvent
    private data class ResolveFailed(val serviceInfo: NsdServiceInfo, val errorCode: Int)
        : ResolveEvent
    private data class ServiceResolved(val serviceInfo: NsdServiceInfo) : ResolveEvent

    private class TestResolveListener : ResolveListener, HistoryListener<ResolveEvent>() {
        override fun onResolveFailed(si: NsdServiceInfo, err: Int) = add(ResolveFailed(si, err))
        override fun onServiceResolved(si: NsdServiceInfo) = add(ServiceResolved(si))
    }

    private val serviceName: String

    init {
        val rand = Random()
        serviceName = "NsdTest" + (0..3).map { rand.nextInt(10) }.joinToString()
    }

    @Test
    fun testNsdManager() {
        if (DBG) Log.d(TAG, "Starting test ...")
        val si = NsdServiceInfo()
        si.serviceType = SERVICE_TYPE
        si.serviceName = serviceName
        val testByteArray = byteArrayOf(-128, 127, 2, 1, 0, 1, 2)
        val string256 = "1_________2_________3_________4_________5_________6_________" +
                "7_________8_________9_________10________11________12________13________" +
                "14________15________16________17________18________19________20________" +
                "21________22________23________24________25________123456"

        // Illegal attributes
        assertThrows("Could set null key", IllegalArgumentException::class.java) {
            si.setAttribute(null, null as String?)
        }
        assertThrows("Could set empty key", IllegalArgumentException::class.java) {
            si.setAttribute("", null as String?)
        }
        assertThrows("Could set key with 255 characters", IllegalArgumentException::class.java) {
            si.setAttribute(string256, null as String?)
        }
        assertThrows("Could set key+value combination with more than 255 characters",
                IllegalArgumentException::class.java) {
            si.setAttribute("key", string256.substring(3))
        }
        assertThrows("Could set key+value combination with 255 characters",
                IllegalArgumentException::class.java) {
            si.setAttribute("key", string256.substring(4))
        }
        assertThrows("Could set key with invalid character", IllegalArgumentException::class.java) {
            si.setAttribute(String(byteArrayOf(0x19)), null as String?)
        }
        assertThrows("Could set key with invalid character", IllegalArgumentException::class.java) {
            si.setAttribute("=", null as String?)
        }
        assertThrows("Could set key with invalid character", IllegalArgumentException::class.java) {
            si.setAttribute(String(byteArrayOf(0x7F)), null as String?)
        }

        // Allowed attributes
        si.setAttribute("booleanAttr", null as String?)
        si.setAttribute("keyValueAttr", "value")
        si.setAttribute("keyEqualsAttr", "=")
        si.setAttribute(" whiteSpaceKeyValueAttr ", " value ")
        si.setAttribute("binaryDataAttr", testByteArray)
        si.setAttribute("nullBinaryDataAttr", null as ByteArray?)
        si.setAttribute("emptyBinaryDataAttr", byteArrayOf())
        si.setAttribute("longkey", string256.substring(9))
        val socket = ServerSocket(0)
        val localPort = socket.localPort
        si.port = localPort
        if (DBG) Log.d(TAG, "Port = $localPort")

        val registrationListener = TestRegistrationListener()
        val registeredName = registerService(registrationListener, si)

        val discoveryListener = TestDiscoveryListener()
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

        // Expect discovery started
        discoveryListener.waitForCallback<DiscoveryStarted>()

        // Expect a service record to be discovered
        val foundInfo = waitForServiceDiscovered(discoveryListener, registeredName)

        // Resolve the service
        val resolvedService = resolveService(foundInfo)

        // Check Txt attributes
        assertEquals(8, resolvedService.attributes.size)
        assertTrue(resolvedService.attributes.containsKey("booleanAttr"))
        assertNull(resolvedService.attributes["booleanAttr"])
        assertEquals("value", String(resolvedService.attributes["keyValueAttr"]!!))
        assertEquals("=", String(resolvedService.attributes["keyEqualsAttr"]!!))
        assertEquals(" value ", String(resolvedService.attributes[" whiteSpaceKeyValueAttr "]!!))
        assertEquals(string256.substring(9), String(resolvedService.attributes["longkey"]!!))
        assertTrue(Arrays.equals(testByteArray,
                resolvedService.attributes["binaryDataAttr"]))
        assertTrue(resolvedService.attributes.containsKey("nullBinaryDataAttr"))
        assertNull(resolvedService.attributes["nullBinaryDataAttr"])
        assertTrue(resolvedService.attributes.containsKey("emptyBinaryDataAttr"))
        assertNull(resolvedService.attributes["emptyBinaryDataAttr"])
        assertEquals(localPort, resolvedService.port)

        // Unregister the service
        nsdManager.unregisterService(registrationListener)
        registrationListener.waitForCallback<ServiceUnregistered>()

        // Expect a callback for service lost
        discoveryListener.waitForCallbackEventually<ServiceLost> {
            it.serviceInfo.serviceName == registeredName
        }

        // Register service again to see if we discover it
        val si2 = NsdServiceInfo()
        val registrationListener2 = TestRegistrationListener()
        si2.serviceType = SERVICE_TYPE
        si2.serviceName = serviceName
        si2.port = localPort
        val registeredName2 = registerService(registrationListener2, si2)

        // Expect a service record to be discovered (and filter the ones
        // that are unrelated to this test)
        val foundInfo2 = waitForServiceDiscovered(discoveryListener, registeredName2)

        // Resolve the service
        val resolvedService2 = resolveService(foundInfo2)

        // Check that we don't have any TXT records
        assertEquals(0, resolvedService2.attributes.size)
        nsdManager.stopServiceDiscovery(discoveryListener)
        discoveryListener.waitForCallbackEventually<DiscoveryStopped>()

        nsdManager.unregisterService(registrationListener2)
        registrationListener2.waitForCallback<ServiceUnregistered>()
    }

    /**
     * Register a service and return its registered name.
     */
    private fun registerService(listener: TestRegistrationListener, si: NsdServiceInfo): String {
        nsdManager.registerService(si, NsdManager.PROTOCOL_DNS_SD, listener)
        val cb = listener.waitForCallback<ServiceRegistered>()
        return cb.serviceInfo.serviceName
    }

    private fun waitForServiceDiscovered(
        listener: TestDiscoveryListener,
        serviceName: String
    ): NsdServiceInfo {
        return listener.waitForCallbackEventually<ServiceFound> {
            it.serviceInfo.serviceName == serviceName
        }.serviceInfo
    }

    private fun resolveService(discoveredInfo: NsdServiceInfo): NsdServiceInfo {
        val resolveListener = TestResolveListener()
        nsdManager.resolveService(discoveredInfo, resolveListener)
        val resolvedCb = resolveListener.waitForCallback<ServiceResolved>()
        assertEquals(discoveredInfo.serviceName, resolvedCb.serviceInfo.serviceName)

        return resolvedCb.serviceInfo
    }
}