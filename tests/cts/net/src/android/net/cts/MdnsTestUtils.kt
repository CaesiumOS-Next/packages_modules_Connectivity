package android.net.cts

import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Process
import com.android.net.module.util.ArrayTrackRecord
import com.android.net.module.util.DnsPacket
import com.android.net.module.util.NetworkStackConstants.ETHER_HEADER_LEN
import com.android.net.module.util.NetworkStackConstants.IPV6_HEADER_LEN
import com.android.net.module.util.NetworkStackConstants.UDP_HEADER_LEN
import com.android.net.module.util.TrackRecord
import com.android.networkstack.apishim.NsdShimImpl
import com.android.testutils.IPv6UdpFilter
import com.android.testutils.TapPacketReader
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private const val MDNS_REGISTRATION_TIMEOUT_MS = 10_000L
const val MDNS_CALLBACK_TIMEOUT = 2000L
const val MDNS_NO_CALLBACK_TIMEOUT_MS = 200L

val nsdShim = NsdShimImpl.newInstance()
fun TapPacketReader.pollForMdnsPacket(
    timeoutMs: Long = MDNS_REGISTRATION_TIMEOUT_MS,
    predicate: (TestDnsPacket) -> Boolean
): ByteArray? {
    val mdnsProbeFilter = IPv6UdpFilter(srcPort = 5353, dstPort = 5353).and {
        val mdnsPayload = it.copyOfRange(
            ETHER_HEADER_LEN + IPV6_HEADER_LEN + UDP_HEADER_LEN, it.size)
        try {
            predicate(TestDnsPacket(mdnsPayload))
        } catch (e: DnsPacket.ParseException) {
            false
        }
    }
    return poll(timeoutMs, mdnsProbeFilter)
}

fun TapPacketReader.pollForProbe(
    serviceName: String,
    serviceType: String,
    timeoutMs: Long = MDNS_REGISTRATION_TIMEOUT_MS
): ByteArray? = pollForMdnsPacket(timeoutMs) { it.isProbeFor("$serviceName.$serviceType.local") }

fun TapPacketReader.pollForReply(
    serviceName: String,
    serviceType: String,
    timeoutMs: Long = MDNS_REGISTRATION_TIMEOUT_MS
): ByteArray? = pollForMdnsPacket(timeoutMs) { it.isReplyFor("$serviceName.$serviceType.local") }

class TestDnsPacket(data: ByteArray) : DnsPacket(data) {
    fun isProbeFor(name: String): Boolean = mRecords[QDSECTION].any {
        it.dName == name && it.nsType == 0xff /* ANY */
    }

    fun isReplyFor(name: String): Boolean = mRecords[ANSECTION].any {
        it.dName == name && it.nsType == 0x21 /* SRV */
    }
}

interface NsdEvent
open class NsdRecord<T : NsdEvent> private constructor(
    private val history: ArrayTrackRecord<T>,
    private val expectedThreadId: Int? = null
) : TrackRecord<T> by history {
    constructor(expectedThreadId: Int? = null) : this(ArrayTrackRecord(), expectedThreadId)

    val nextEvents = history.newReadHead()

    override fun add(e: T): Boolean {
        if (expectedThreadId != null) {
            assertEquals(expectedThreadId, Process.myTid(), "Callback is running on the wrong thread")
        }
        return history.add(e)
    }

    inline fun <reified V : NsdEvent> expectCallbackEventually(
        timeoutMs: Long = MDNS_CALLBACK_TIMEOUT,
        crossinline predicate: (V) -> Boolean = { true }
    ): V = nextEvents.poll(timeoutMs) { e -> e is V && predicate(e) } as V?
        ?: fail("Callback for ${V::class.java.simpleName} not seen after $timeoutMs ms")

    inline fun <reified V : NsdEvent> expectCallback(timeoutMs: Long = MDNS_CALLBACK_TIMEOUT): V {
        val nextEvent = nextEvents.poll(timeoutMs)
        assertNotNull(nextEvent, "No callback received after $timeoutMs ms, expected " +
                "${V::class.java.simpleName}")
        assertTrue(nextEvent is V, "Expected ${V::class.java.simpleName} but got " +
                nextEvent.javaClass.simpleName)
        return nextEvent
    }

    inline fun assertNoCallback(timeoutMs: Long = MDNS_NO_CALLBACK_TIMEOUT_MS) {
        val cb = nextEvents.poll(timeoutMs)
        assertNull(cb, "Expected no callback but got $cb")
    }
}
class NsdDiscoveryRecord(expectedThreadId: Int? = null) :
    NsdManager.DiscoveryListener, NsdRecord<NsdDiscoveryRecord.DiscoveryEvent>(expectedThreadId) {
    sealed class DiscoveryEvent : NsdEvent {
        data class StartDiscoveryFailed(val serviceType: String, val errorCode: Int) :
            DiscoveryEvent()

        data class StopDiscoveryFailed(val serviceType: String, val errorCode: Int) :
            DiscoveryEvent()

        data class DiscoveryStarted(val serviceType: String) : DiscoveryEvent()
        data class DiscoveryStopped(val serviceType: String) : DiscoveryEvent()
        data class ServiceFound(val serviceInfo: NsdServiceInfo) : DiscoveryEvent()
        data class ServiceLost(val serviceInfo: NsdServiceInfo) : DiscoveryEvent()
    }

    override fun onStartDiscoveryFailed(serviceType: String, err: Int) {
        add(DiscoveryEvent.StartDiscoveryFailed(serviceType, err))
    }

    override fun onStopDiscoveryFailed(serviceType: String, err: Int) {
        add(DiscoveryEvent.StopDiscoveryFailed(serviceType, err))
    }

    override fun onDiscoveryStarted(serviceType: String) {
        add(DiscoveryEvent.DiscoveryStarted(serviceType))
    }

    override fun onDiscoveryStopped(serviceType: String) {
        add(DiscoveryEvent.DiscoveryStopped(serviceType))
    }

    override fun onServiceFound(si: NsdServiceInfo) {
        add(DiscoveryEvent.ServiceFound(si))
    }

    override fun onServiceLost(si: NsdServiceInfo) {
        add(DiscoveryEvent.ServiceLost(si))
    }

    fun waitForServiceDiscovered(
        serviceName: String,
        serviceType: String,
        expectedNetwork: Network? = null
    ): NsdServiceInfo {
        val serviceFound = expectCallbackEventually<DiscoveryEvent.ServiceFound> {
            it.serviceInfo.serviceName == serviceName &&
                    (expectedNetwork == null ||
                            expectedNetwork == nsdShim.getNetwork(it.serviceInfo))
        }.serviceInfo
        // Discovered service types have a dot at the end
        assertEquals("$serviceType.", serviceFound.serviceType)
        return serviceFound
    }
}
