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
import android.net.cts.DscpPolicyTest.TestableNetworkAgent.CallbackEntry.OnAddKeepalivePacketFilter
import android.net.cts.DscpPolicyTest.TestableNetworkAgent.CallbackEntry.OnAutomaticReconnectDisabled
import android.net.cts.DscpPolicyTest.TestableNetworkAgent.CallbackEntry.OnBandwidthUpdateRequested
import android.net.cts.DscpPolicyTest.TestableNetworkAgent.CallbackEntry.OnNetworkCreated
import android.net.cts.DscpPolicyTest.TestableNetworkAgent.CallbackEntry.OnNetworkDestroyed
import android.net.cts.DscpPolicyTest.TestableNetworkAgent.CallbackEntry.OnNetworkUnwanted
import android.net.cts.DscpPolicyTest.TestableNetworkAgent.CallbackEntry.OnDscpPolicyStatusUpdated
import android.net.cts.DscpPolicyTest.TestableNetworkAgent.CallbackEntry.OnRegisterQosCallback
import android.net.cts.DscpPolicyTest.TestableNetworkAgent.CallbackEntry.OnRemoveKeepalivePacketFilter
import android.net.cts.DscpPolicyTest.TestableNetworkAgent.CallbackEntry.OnSaveAcceptUnvalidated
import android.net.cts.DscpPolicyTest.TestableNetworkAgent.CallbackEntry.OnSignalStrengthThresholdsUpdated
import android.net.cts.DscpPolicyTest.TestableNetworkAgent.CallbackEntry.OnStartSocketKeepalive
import android.net.cts.DscpPolicyTest.TestableNetworkAgent.CallbackEntry.OnStopSocketKeepalive
import android.net.cts.DscpPolicyTest.TestableNetworkAgent.CallbackEntry.OnUnregisterQosCallback
import android.net.cts.DscpPolicyTest.TestableNetworkAgent.CallbackEntry.OnValidationStatus
import android.net.EthernetManager
import android.net.InetAddresses
import android.net.IpPrefix
import android.net.KeepalivePacketData
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NetworkAgent
import android.net.NetworkAgentConfig
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN
import android.net.NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED
import android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_TEST
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.net.NetworkRequest
import android.net.NetworkProvider
import android.net.TestNetworkInterface
import android.net.TestNetworkManager
import android.net.QosFilter
import android.net.RouteInfo
import android.net.Uri
import android.os.Build
import android.os.HandlerThread
import android.os.Looper
import android.platform.test.annotations.AppModeFull
import androidx.test.InstrumentationRegistry
//import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
//import com.android.server.NetworkAgentWrapper
import com.android.modules.utils.build.SdkLevel
import com.android.net.module.util.ArrayTrackRecord
import com.android.server.ConnectivityService
import com.android.testutils.CompatUtil
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.TapPacketReader
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.runAsShell
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private const val MAX_PACKET_LENGTH = 1500
private const val TEST_NETWORK_SCORE = 70
private const val DEFAULT_TIMEOUT_MS = 5000L

private const val IP_VERSION_HEADER_LEN = 0x45.toByte()
private const val DSCP_TEST_VALUE = 8
private const val DSCP_OFFSET = 3
private const val IP_FLAGS_OFFSET = 0x4000.toByte()
private const val IP_TTL = 0x40.toByte()
private const val IP_TYPE_UDP = 0x11.toByte()

private val realContext: Context
    get() = InstrumentationRegistry.getContext()

@AppModeFull(reason = "Instant apps cannot create test networks")
@RunWith(AndroidJUnit4::class)
class DscpPolicyTest {
    @JvmField
    @Rule
    val ignoreRule = DevSdkIgnoreRule(ignoreClassUpTo = Build.VERSION_CODES.S)

    private val LOCAL_IPV4_ADDRESS = InetAddresses.parseNumericAddress("192.0.2.1")

    private val mHandlerThread = HandlerThread("${javaClass.simpleName} handler thread")
    private val mCM = realContext.getSystemService(ConnectivityManager::class.java)!!

    private val agentsToCleanUp = mutableListOf<NetworkAgent>()
    private val callbacksToCleanUp = mutableListOf<TestableNetworkCallback>()

    private val context by lazy { InstrumentationRegistry.getInstrumentation().context }
    private val tnm by lazy { context.assertHasService(TestNetworkManager::class.java) }
    private val eth by lazy { context.assertHasService(EthernetManager::class.java) }
    private val cm by lazy { context.assertHasService(ConnectivityManager::class.java) }

    private lateinit var mConnectivityService: ConnectivityService
    //private lateinit var mNa: NetworkAgentWrapper

    private class Provider(context: Context, looper: Looper) :
            NetworkProvider(context, looper, "NetworkAgentTest NetworkProvider")

    private val handlerThread = HandlerThread(DscpPolicyTest::class.java.simpleName)
    private val ethRequest = NetworkRequest.Builder()
            // ETHERNET|TEST transport networks do not have NET_CAPABILITY_TRUSTED
            .removeCapability(NET_CAPABILITY_TRUSTED)
            .addTransportType(TRANSPORT_ETHERNET)
            .addTransportType(TRANSPORT_TEST).build()
    private val ethRequestCb = TestableNetworkCallback()

    private lateinit var iface: TestNetworkInterface
    private lateinit var reader: TapPacketReader

    private var testSkipped = false

    private open class TestableNetworkAgent(
        context: Context,
        looper: Looper,
        val nc: NetworkCapabilities,
        val lp: LinkProperties,
        conf: NetworkAgentConfig
    ) : NetworkAgent(context, looper, TestableNetworkAgent::class.java.simpleName /* tag */,
            nc, lp, TEST_NETWORK_SCORE, conf, Provider(context, looper)) {
        private val history = ArrayTrackRecord<CallbackEntry>().newReadHead()

        sealed class CallbackEntry {
            object OnBandwidthUpdateRequested : CallbackEntry()
            object OnNetworkUnwanted : CallbackEntry()
            data class OnAddKeepalivePacketFilter(
                val slot: Int,
                val packet: KeepalivePacketData
            ) : CallbackEntry()
            data class OnRemoveKeepalivePacketFilter(val slot: Int) : CallbackEntry()
            data class OnStartSocketKeepalive(
                val slot: Int,
                val interval: Int,
                val packet: KeepalivePacketData
            ) : CallbackEntry()
            data class OnStopSocketKeepalive(val slot: Int) : CallbackEntry()
            data class OnSaveAcceptUnvalidated(val accept: Boolean) : CallbackEntry()
            object OnAutomaticReconnectDisabled : CallbackEntry()
            data class OnValidationStatus(val status: Int, val uri: Uri?) : CallbackEntry()
            data class OnSignalStrengthThresholdsUpdated(val thresholds: IntArray) : CallbackEntry()
            object OnNetworkCreated : CallbackEntry()
            object OnNetworkDestroyed : CallbackEntry()
            data class OnDscpPolicyStatusUpdated(val policyId: Int, val status: Int) : CallbackEntry()
            data class OnRegisterQosCallback(
                val callbackId: Int,
                val filter: QosFilter
            ) : CallbackEntry()
            data class OnUnregisterQosCallback(val callbackId: Int) : CallbackEntry()
        }

        override fun onBandwidthUpdateRequested() {
            history.add(OnBandwidthUpdateRequested)
        }

        override fun onNetworkUnwanted() {
            history.add(OnNetworkUnwanted)
        }

        override fun onAddKeepalivePacketFilter(slot: Int, packet: KeepalivePacketData) {
            history.add(OnAddKeepalivePacketFilter(slot, packet))
        }

        override fun onRemoveKeepalivePacketFilter(slot: Int) {
            history.add(OnRemoveKeepalivePacketFilter(slot))
        }

        override fun onStartSocketKeepalive(
            slot: Int,
            interval: Duration,
            packet: KeepalivePacketData
        ) {
            history.add(OnStartSocketKeepalive(slot, interval.seconds.toInt(), packet))
        }

        override fun onStopSocketKeepalive(slot: Int) {
            history.add(OnStopSocketKeepalive(slot))
        }

        override fun onSaveAcceptUnvalidated(accept: Boolean) {
            history.add(OnSaveAcceptUnvalidated(accept))
        }

        override fun onAutomaticReconnectDisabled() {
            history.add(OnAutomaticReconnectDisabled)
        }

        override fun onSignalStrengthThresholdsUpdated(thresholds: IntArray) {
            history.add(OnSignalStrengthThresholdsUpdated(thresholds))
        }

        fun expectSignalStrengths(thresholds: IntArray? = intArrayOf()) {
            expectCallback<OnSignalStrengthThresholdsUpdated>().let {
                assertArrayEquals(thresholds, it.thresholds)
            }
        }

        override fun onQosCallbackRegistered(qosCallbackId: Int, filter: QosFilter) {
            history.add(OnRegisterQosCallback(qosCallbackId, filter))
        }

        override fun onQosCallbackUnregistered(qosCallbackId: Int) {
            history.add(OnUnregisterQosCallback(qosCallbackId))
        }

        override fun onValidationStatus(status: Int, uri: Uri?) {
            history.add(OnValidationStatus(status, uri))
        }

        override fun onNetworkCreated() {
            history.add(OnNetworkCreated)
        }

        override fun onNetworkDestroyed() {
            history.add(OnNetworkDestroyed)
        }

        override fun onDscpPolicyStatusUpdated(policyId: Int, status: Int) {
            history.add(OnDscpPolicyStatusUpdated(policyId, status))
        }

        // Expects the initial validation event that always occurs immediately after registering
        // a NetworkAgent whose network does not require validation (which test networks do
        // not, since they lack the INTERNET capability). It always contains the default argument
        // for the URI.
        fun expectValidationBypassedStatus() = expectCallback<OnValidationStatus>().let {
            assertEquals(it.status, VALID_NETWORK)
            // The returned Uri is parsed from the empty string, which means it's an
            // instance of the (private) Uri.StringUri. There are no real good ways
            // to check this, the least bad is to just convert it to a string and
            // make sure it's empty.
            assertEquals("", it.uri.toString())
        }

        inline fun <reified T : CallbackEntry> expectCallback(): T {
            val foundCallback = history.poll(DEFAULT_TIMEOUT_MS)
            assertTrue(foundCallback is T, "Expected ${T::class} but found $foundCallback")
            return foundCallback
        }

        inline fun <reified T : CallbackEntry> expectCallback(valid: (T) -> Boolean) {
            val foundCallback = history.poll(DEFAULT_TIMEOUT_MS)
            assertTrue(foundCallback is T, "Expected ${T::class} but found $foundCallback")
            assertTrue(valid(foundCallback), "Unexpected callback : $foundCallback")
        }

        inline fun <reified T : CallbackEntry> eventuallyExpect() =
                history.poll(DEFAULT_TIMEOUT_MS) { it is T }.also {
                    assertNotNull(it, "Callback ${T::class} not received")
        } as T

        fun assertNoCallback() {
            assertTrue(waitForIdle(DEFAULT_TIMEOUT_MS),
                    "Handler didn't became idle after ${DEFAULT_TIMEOUT_MS}ms")
            assertNull(history.peek())
        }
    }

    private fun requestNetwork(request: NetworkRequest, callback: TestableNetworkCallback) {
        mCM.requestNetwork(request, callback)
        callbacksToCleanUp.add(callback)
    }

    private fun makeTestNetworkRequest(specifier: String? = null): NetworkRequest {
        return NetworkRequest.Builder()
                .clearCapabilities()
                .addTransportType(TRANSPORT_TEST)
                .also {
                    if (specifier != null) {
                        it.setNetworkSpecifier(CompatUtil.makeEthernetNetworkSpecifier(specifier))
                    }
                }
                .build()
    }

    private fun createNetworkAgent(
        context: Context = realContext,
        specifier: String? = null,
        initialNc: NetworkCapabilities? = null,
        initialLp: LinkProperties? = null,
        initialConfig: NetworkAgentConfig? = null
    ): TestableNetworkAgent {
        val nc = initialNc ?: NetworkCapabilities().apply {
            addTransportType(TRANSPORT_TEST)
            removeCapability(NET_CAPABILITY_TRUSTED)
            removeCapability(NET_CAPABILITY_INTERNET)
            addCapability(NET_CAPABILITY_NOT_SUSPENDED)
            addCapability(NET_CAPABILITY_NOT_ROAMING)
            addCapability(NET_CAPABILITY_NOT_VPN)
            if (SdkLevel.isAtLeastS()) {
                addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
            }
            if (null != specifier) {
                setNetworkSpecifier(CompatUtil.makeEthernetNetworkSpecifier(specifier))
            }
        }
        val lp = initialLp ?: LinkProperties().apply {
            addLinkAddress(LinkAddress(LOCAL_IPV4_ADDRESS, 32))
            addRoute(RouteInfo(IpPrefix("0.0.0.0/0"), null, null))
        }
        val config = initialConfig ?: NetworkAgentConfig.Builder().build()
        return TestableNetworkAgent(context, mHandlerThread.looper, nc, lp, config).also {
            agentsToCleanUp.add(it)
        }
    }

    private fun createConnectedNetworkAgent(
        context: Context = realContext,
        specifier: String? = UUID.randomUUID().toString(),
        initialConfig: NetworkAgentConfig? = null,
        expectedInitSignalStrengthThresholds: IntArray? = intArrayOf()
    ): Pair<TestableNetworkAgent, TestableNetworkCallback> {
        val callback = TestableNetworkCallback()
        // Ensure this NetworkAgent is never unneeded by filing a request with its specifier.
        requestNetwork(makeTestNetworkRequest(specifier = specifier), callback)
        val agent = createNetworkAgent(context, specifier, initialConfig = initialConfig)
        agent.setTeardownDelayMillis(0)
        // Connect the agent and verify initial status callbacks.
        agent.register()
        // is registerForTest() needed instead
        agent.markConnected()
        agent.expectCallback<OnNetworkCreated>()
        agent.expectSignalStrengths(expectedInitSignalStrengthThresholds)
        agent.expectValidationBypassedStatus()
        callback.expectAvailableThenValidatedCallbacks(agent.network!!)
        return agent to callback
    }

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
            // mNa = NetworkAgentWrapper(TRANSPORT_ETHERNET, LinkProperties(), null /* ncTemplate */,
            //         context)
            //networkStackClient.verifyNetworkMonitorCreated(mNa.network, TEST_TIMEOUT_MS)
            //mNa.addCapability(NET_CAPABILITY_INTERNET)
            //mNa.connect()

            // Keeping a reference to the test interface also makes sure the ParcelFileDescriptor
            // does not go out of scope, which would cause it to close the underlying FileDescriptor
            // in its finalizer.
            iface = tnm.createTapInterface()
            // TODO: how to pass this into config?

            //val (agent, callback) = createConnectedNetworkAgent(initialConfig = config)
            val (agent, callback) = createConnectedNetworkAgent()
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

    private fun createPacket(srcIp: Inet4Address, dstIp: Inet4Address): ByteBuffer {
        // final MacAddress dstMac = MacAddress.fromString("33:33:03:04:05:06")
        // final MacAddress srcMac = mTetheredParams.macAddr
        // final ByteBuffer slla = LlaOption.build((byte) ICMPV6_ND_OPTION_SLLA, srcMac)

        // Is there a util function like buildRsPacket for building a normal packet?
        var buf = ByteBuffer.allocate(1500)

        var destIpArray = dstIp.getAddress()
        var srcIpArray = srcIp.getAddress()
        var ipHeaderOffset = 0
        var ipLengthOffset = 0
        var ipChecksumOffset = 0
        var endIpHeader = 0
        // var udpHeaderOffset = 0
        // var udpLengthOffset = 0
        // var udpChecksumOffset = 0

        buf.clear()
        buf.order(ByteOrder.BIG_ENDIAN)

        ipHeaderOffset = buf.position()
        buf.put(IP_VERSION_HEADER_LEN) // IP version and IHL
        // Add 3 to DSCP to account for ECN
        buf.put((DSCP_TEST_VALUE + DSCP_OFFSET).toByte())    // tos: DSCP(8-13) + ECN(14-15)
        ipLengthOffset = buf.position()
        // TODO: in kotlin do these need to be typecast to SHORT?
        buf.putShort(0.toShort())  // length
        buf.putShort(0.toShort())  // id
        buf.putShort(IP_FLAGS_OFFSET.toShort()) // ip offset: don't fragment
        buf.put(IP_TTL)    // TTL: use default 64 from RFC1340
        buf.put(IP_TYPE_UDP)
        ipChecksumOffset = buf.position()
        buf.putShort(0.toShort()) // checksum

        buf.put(srcIpArray)
        buf.put(destIpArray)
        endIpHeader = buf.position()

        return buf
    }

    // private fun getDscpVal(testRa: TestRaPacket, multicast: boolean): Int {
    //     var packet: ByteArray
    //     var dscpVal
    //     while ((packet = mTetheredPacketReader.poll(PACKET_TIMEOUT_MS)) != null) {
    //         // pull DSCP value from packet
    //     }
    //     return dscpVal;
    // }

    @Test
    fun testDscpPolicyUpdatesPacket() {
        // Add DSCP Policy
        // call to NetworkAgent or NetworkAgentWrapper

        // Inet4Address
        // TODO: in kotlin should these be typecasted?
        var srcIp = InetAddress.getByAddress(byteArrayOf(
                    0x0.toByte(), 0x0.toByte(), 0x0.toByte(), 0x0.toByte())) as Inet4Address
        var dstIp = InetAddress.getByAddress(byteArrayOf(
                    0x0.toByte(), 0x0.toByte(), 0x0.toByte(), 0x0.toByte())) as Inet4Address
        //InetAddress.getByAddress(bytes) //Inet6Address

        // Send packet matching policy
        val packet = createPacket(srcIp, dstIp)
        reader.sendResponse(packet)

        // Check that packet on TAP iface has DSCP bit correctly added
        //assertTrue(getDscpVal(), dscpVal)
    }

    @Test
    fun testSendUpdates(): Unit = createConnectedNetworkAgent().let { (agent, callback) ->
        val ifaceName = "adhocIface"
        val lp = LinkProperties(agent.lp)
        lp.setInterfaceName(ifaceName)
        agent.sendLinkProperties(lp)
        callback.expectLinkPropertiesThat(agent.network!!) {
            it.getInterfaceName() == ifaceName
        }
        val nc = NetworkCapabilities(agent.nc)
        nc.addCapability(NET_CAPABILITY_NOT_METERED)
        agent.sendNetworkCapabilities(nc)
        callback.expectCapabilitiesThat(agent.network!!) {
            it.hasCapability(NET_CAPABILITY_NOT_METERED)
        }
    }
}

private fun <T> Context.assertHasService(manager: Class<T>): T {
    return getSystemService(manager) ?: fail("Service $manager not found")
}