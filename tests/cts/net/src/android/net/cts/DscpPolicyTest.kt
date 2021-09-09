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
import android.net.LinkProperties
import android.net.NetworkAgent
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_TEST
import android.net.NetworkRequest
import android.net.TestNetworkInterface
import android.net.TestNetworkManager
import android.os.Build
import android.os.HandlerThread
import android.os.Looper
import android.platform.test.annotations.AppModeFull
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
//import com.android.server.NetworkAgentWrapper
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
class DscpPolicyTest {
    @JvmField
    @Rule
    val ignoreRule = DevSdkIgnoreRule(ignoreClassUpTo = Build.VERSION_CODES.S)

    private val context by lazy { InstrumentationRegistry.getInstrumentation().context }
    private val tnm by lazy { context.assertHasService(TestNetworkManager::class.java) }
    private val eth by lazy { context.assertHasService(EthernetManager::class.java) }
    private val cm by lazy { context.assertHasService(ConnectivityManager::class.java) }

    private lateinit var mConnectivityService: ConnectivityService
    //private lateinit var mNa: NetworkAgentWrapper

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

            val (agent, callback) = createConnectedNetworkAgent(initialConfig = config)

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

    private fun createPacket(srcIp: String): ByteBuffer {
        // final MacAddress dstMac = MacAddress.fromString("33:33:03:04:05:06")
        // final MacAddress srcMac = mTetheredParams.macAddr
        // final ByteBuffer slla = LlaOption.build((byte) ICMPV6_ND_OPTION_SLLA, srcMac)

        // Is there a util function like buildRsPacket for building a normal packet?
        return packet
    }

    private fun getDscpVal(testRa: TestRaPacket, multicast: boolean): Int {
        var packet: ByteArray
        var dscpVal
        while ((packet = mTetheredPacketReader.poll(PACKET_TIMEOUT_MS)) != null) {
            // pull DSCP value from packet
        }
        return dscpVal;
    }

    @Test
    fun testDscpPolicyUpdatesPacket() {
        // Add DSCP Policy
        // call to NetworkAgent or NetworkAgentWrapper

        // Send packet matching policy
        packet = createPacket(src)
        reader.sendResponse(packet)

        // Check that packet on TAP iface has DSCP bit correctly added
        assertTrue(getDscpVal(), dscpVal)
    }
}

private fun <T> Context.assertHasService(manager: Class<T>): T {
    return getSystemService(manager) ?: fail("Service $manager not found")
}