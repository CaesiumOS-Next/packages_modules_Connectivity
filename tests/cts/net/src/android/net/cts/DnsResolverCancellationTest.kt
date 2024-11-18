package android.net.cts

import android.Manifest.permission.MANAGE_TEST_NETWORKS
import android.net.DnsResolver
import android.net.InetAddresses.parseNumericAddress
import android.net.IpPrefix
import android.net.LinkAddress
import android.net.MacAddress
import android.net.RouteInfo
import android.os.CancellationSignal
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.DeviceConfig.NAMESPACE_NETD_NATIVE
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.net.module.util.NetworkStackConstants.ETHER_HEADER_LEN
import com.android.net.module.util.NetworkStackConstants.IPV6_HEADER_LEN
import com.android.net.module.util.NetworkStackConstants.UDP_HEADER_LEN
import com.android.testutils.AutoReleaseNetworkCallbackRule
import com.android.testutils.DeviceConfigRule
import com.android.testutils.DnsResolverModuleTest
import com.android.testutils.IPv6UdpFilter
import com.android.testutils.RecorderCallback.CallbackEntry.LinkPropertiesChanged
import com.android.testutils.RouterAdvertisementResponder
import com.android.testutils.TapPacketReaderRule
import com.android.testutils.TestableNetworkAgent
import com.android.testutils.TestDnsPacket
import com.android.testutils.runAsShell
import com.android.testutils.tryTest
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private val TEST_GLOBAL_LINKADDR = LinkAddress(parseNumericAddress("2001:db8::123"), 64)
private val TEST_ROUTER_MAC = MacAddress.fromString("00:11:22:33:44:55")
private val TEST_ROUTER_ADDR = parseNumericAddress("fe80::124") as Inet6Address
private val TAG = DnsResolverCancellationTest::class.java.simpleName
private const val TEST_TIMEOUT_MS = 10_000L

@DnsResolverModuleTest
@RunWith(AndroidJUnit4::class)
class DnsResolverCancellationTest {
    val context by lazy { InstrumentationRegistry.getInstrumentation().context }
    val handlerThread = HandlerThread(TAG)

    @get:Rule
    val packetReaderRule = TapPacketReaderRule()

    @get:Rule
    val cbRule = AutoReleaseNetworkCallbackRule()

    @get:Rule
    val deviceConfigRule = DeviceConfigRule()

    @Before
    fun setUp() {
        handlerThread.start()
    }

    @After
    fun tearDown() {
        handlerThread.quitSafely()
        handlerThread.join()
    }

    private class DnsCallback : DnsResolver.Callback<List<InetAddress>> {
        val resultFuture = CompletableFuture<List<InetAddress>>()
        override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
            android.util.Log.e(TAG, "Got answer with rcode $rcode")
            resultFuture.complete(answer)
        }

        override fun onError(error: DnsResolver.DnsException) {
            android.util.Log.e(TAG, "Got error: $error")
            resultFuture.completeExceptionally(error)
        }
    }

    /**
     * Run a cancellation test.
     *
     * @param domain Domain name to query
     * @param waitTimeForNoRetryAfterCancellationMs If positive, cancel the query and wait for that
     *                                              delay to check no retry is sent.
     * @return The duration it took to receive all expected replies.
     */
    fun doCancellationTest(domain: String, waitTimeForNoRetryAfterCancellationMs: Long): Long {
        val iface = packetReaderRule.iface
        val reader = packetReaderRule.reader
        val cb = cbRule.requestNetwork(TestableNetworkAgent.makeNetworkRequestForInterface(
            iface.interfaceName))
        val agent = runAsShell(MANAGE_TEST_NETWORKS) {
            TestableNetworkAgent.createOnInterface(context, handlerThread.looper,
                iface.interfaceName, TEST_TIMEOUT_MS)
        }
        val ndResponder = RouterAdvertisementResponder(reader)
        ndResponder.addRouterEntry(TEST_ROUTER_MAC, TEST_ROUTER_ADDR)
        ndResponder.start()
        val dnsServerAddr = parseNumericAddress("fe80::124%${iface.interfaceName}")
        agent.lp.apply {
            addDnsServer(dnsServerAddr)
            addLinkAddress(TEST_GLOBAL_LINKADDR)
            addRoute(RouteInfo(IpPrefix("::/0"), null, null))
        }
        agent.sendLinkProperties(agent.lp)
        cb.eventuallyExpect<LinkPropertiesChanged> { it.lp.dnsServers.isNotEmpty() }
        return tryTest {
            val cancellationSignal = CancellationSignal()
            val dnsCb = DnsCallback()
            val queryStart = SystemClock.elapsedRealtime()
            DnsResolver.getInstance().query(
                agent.network, domain, 0 /* flags */,
                Runnable::run /* executor */, cancellationSignal, dnsCb
            )

            if (waitTimeForNoRetryAfterCancellationMs > 0) {
                cancellationSignal.cancel()
            }
            val filter = IPv6UdpFilter(dstPort = 53).and {
                TestDnsPacket(
                    it.copyOfRange(ETHER_HEADER_LEN + IPV6_HEADER_LEN + UDP_HEADER_LEN, it.size),
                    dstAddr = dnsServerAddr
                ).isQueryFor(domain, DnsResolver.TYPE_AAAA)
            }

            assertNotNull(reader.poll(TEST_TIMEOUT_MS, filter), "Original query not found")
            if (waitTimeForNoRetryAfterCancellationMs > 0) {
                assertNull(reader.poll(waitTimeForNoRetryAfterCancellationMs, filter),
                    "Expected no retry query")
            } else {
                assertNotNull(reader.poll(TEST_TIMEOUT_MS, filter), "Retry query not found")
            }
            return@tryTest SystemClock.elapsedRealtime() - queryStart
        } cleanupStep {
            ndResponder.stop()
        } cleanup {
            agent.unregister()
        }
    }

    @Test
    fun testCancellation() {
        deviceConfigRule.setConfig(NAMESPACE_NETD_NATIVE, "no_retry_after_cancelled", "1")
        val timeWithRetryWhenNotCancelled = doCancellationTest("test1.example.com",
            waitTimeForNoRetryAfterCancellationMs = 0L)
        doCancellationTest("test2.example.com",
            waitTimeForNoRetryAfterCancellationMs = timeWithRetryWhenNotCancelled + 50L)
    }
}
