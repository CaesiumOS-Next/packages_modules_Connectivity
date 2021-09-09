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

import android.net.cts.util.CtsNetUtils.TestNetworkCallback

import android.app.Instrumentation
import android.Manifest.permission.MANAGE_TEST_NETWORKS
import android.content.Context
import android.net.ConnectivityManager
import android.net.cts.util.CtsNetUtils
import android.net.DscpPolicy
import android.net.DscpPolicy.DSCP_POLICY_STATUS_DELETED
import android.net.DscpPolicy.DSCP_POLICY_STATUS_SUCCESS
import android.net.InetAddresses
import android.net.IpPrefix
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NetworkAgent
import android.net.NetworkAgentConfig
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN
import android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED
import android.net.NetworkCapabilities.TRANSPORT_TEST
import android.net.NetworkRequest
import android.net.TestNetworkInterface
import android.net.TestNetworkManager
import android.net.RouteInfo
import android.net.util.SocketUtils
import android.os.Build
import android.os.HandlerThread
import android.os.Looper
import android.platform.test.annotations.AppModeFull
import android.system.Os
import android.system.OsConstants.AF_INET
import android.system.OsConstants.IPPROTO_IP
import android.system.OsConstants.IPPROTO_UDP
import android.system.OsConstants.IP_TOS
import android.system.OsConstants.SOCK_DGRAM
import android.system.OsConstants.SOCK_NONBLOCK
import android.util.Log
import android.util.Range
import androidx.test.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.android.modules.utils.build.SdkLevel
import com.android.testutils.CompatUtil
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.OffsetFilter
import com.android.testutils.TapPacketReader
import com.android.testutils.TestableNetworkAgent
import com.android.testutils.TestableNetworkAgent.CallbackEntry.OnNetworkCreated
import com.android.testutils.TestableNetworkAgent.CallbackEntry.OnDscpPolicyStatusUpdated
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.runAsShell
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

private const val MAX_PACKET_LENGTH = 1500

private const val IP_VERSION_HEADER_LEN = 0x45.toByte()
private const val DSCP_TEST_VALUE = 8
private const val DSCP_OFFSET = 3
private const val IP_FLAGS_OFFSET = 0x4000.toByte()
private const val IP_TTL = 0x40.toByte()
private const val IP_TYPE_UDP = 0x11.toByte()

private val instrumentation: Instrumentation
    get() = InstrumentationRegistry.getInstrumentation()

private const val TAG = "DscpPolicyTest"
private const val PACKET_TIMEOUT_MS = 2_000L

@AppModeFull(reason = "Instant apps cannot create test networks")
@RunWith(AndroidJUnit4::class)
class DscpPolicyTest {
    @JvmField
    @Rule
    val ignoreRule = DevSdkIgnoreRule(ignoreClassUpTo = Build.VERSION_CODES.S)

    private val LOCAL_IPV4_ADDRESS = InetAddresses.parseNumericAddress("192.0.2.1")
    private val TEST_TARGET_IPV4_ADDR = InetAddresses.parseNumericAddress("192.0.2.42") as Inet4Address
    private val TEST_TARGET_IPV4_LOOPBACK_ADDR = InetAddresses.parseNumericAddress("127.0.0.1") as Inet4Address

    private val realContext = InstrumentationRegistry.getContext()
    private val cm = realContext.assertHasService(ConnectivityManager::class.java)!!

    private val agentsToCleanUp = mutableListOf<NetworkAgent>()
    private val callbacksToCleanUp = mutableListOf<TestableNetworkCallback>()

    private val handlerThread = HandlerThread(DscpPolicyTest::class.java.simpleName)

    private lateinit var iface: TestNetworkInterface
    private lateinit var tunNetworkCallback: TestNetworkCallback
    private lateinit var reader: TapPacketReader

    @Before
    fun setUp() {
        runAsShell(MANAGE_TEST_NETWORKS) {
            val tnm = realContext.getSystemService(TestNetworkManager::class.java)

            iface = tnm.createTunInterface( Array(1){ LinkAddress(LOCAL_IPV4_ADDRESS, 32) } )
            assertNotNull(iface)
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
        agentsToCleanUp.forEach { it.unregister() }
        callbacksToCleanUp.forEach { cm.unregisterNetworkCallback(it) }
        handlerThread.quitSafely()
    }

    private fun requestNetwork(request: NetworkRequest, callback: TestableNetworkCallback) {
        cm.requestNetwork(request, callback)
        callbacksToCleanUp.add(callback)
    }

    private fun makeTestNetworkRequest(specifier: String? = null): NetworkRequest {
        return NetworkRequest.Builder()
                .clearCapabilities()
                .addCapability(NET_CAPABILITY_NOT_RESTRICTED)
                .addTransportType(TRANSPORT_TEST)
                .also {
                    if (specifier != null) {
                        it.setNetworkSpecifier(CompatUtil.makeTestNetworkSpecifier(specifier))
                    }
                }
                .build()
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

        val nc = NetworkCapabilities().apply {
            addTransportType(TRANSPORT_TEST)
            removeCapability(NET_CAPABILITY_TRUSTED)
            removeCapability(NET_CAPABILITY_INTERNET)
            addCapability(NET_CAPABILITY_NOT_SUSPENDED)
            addCapability(NET_CAPABILITY_NOT_ROAMING)
            addCapability(NET_CAPABILITY_NOT_VPN)
            addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
            if (null != specifier) {
                setNetworkSpecifier(CompatUtil.makeTestNetworkSpecifier(specifier))
            }
        }
        val lp = LinkProperties().apply {
            addLinkAddress(LinkAddress(LOCAL_IPV4_ADDRESS, 32))
            addRoute(RouteInfo(IpPrefix("0.0.0.0/0"), null, null))
            setInterfaceName(iface.getInterfaceName())
        }
        val config = initialConfig ?: NetworkAgentConfig.Builder().build()
        val agent = TestableNetworkAgent(context, handlerThread.looper, nc, lp, config).also {
            agentsToCleanUp.add(it)
        }

        agent.setTeardownDelayMillis(0)
        // Connect the agent and verify initial status callbacks.
        //agent.register()
        runAsShell(MANAGE_TEST_NETWORKS) { agent.register() }
        // is registerForTest() needed instead
        agent.markConnected()
        agent.expectCallback<OnNetworkCreated>()
        agent.expectSignalStrengths(expectedInitSignalStrengthThresholds)
        agent.expectValidationBypassedStatus()
        val network = agent.network ?: fail("Expected a non-null network")
        callback.expectAvailableThenValidatedCallbacks(network)
        return agent to callback
    }

    fun ByteArray.toHex(): String = joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }

    @Test
    fun testDscpPolicyUpdatesPacket(): Unit = createConnectedNetworkAgent().let { (agent, callback) ->
        var policy = DscpPolicy.Builder(1).setDscp(1).setSrcPort(5555).setDstPortRange(Range(5555, 5555)).build()

        agent.sendAddDscpPolicy(policy)
        agent.expectCallback<OnDscpPolicyStatusUpdated>().let {
            assertEquals(1, it.policyId)
            assertEquals(DSCP_POLICY_STATUS_SUCCESS, it.status)
        }

        var testString = "test string"
        val testPacket = ByteBuffer.wrap(testString.toByteArray(Charsets.UTF_8))

        val socket = Os.socket(AF_INET, SOCK_DGRAM or SOCK_NONBLOCK, IPPROTO_UDP)
        Os.setsockoptInt(socket, IPPROTO_IP, IP_TOS, 1)
        SocketUtils.bindSocketToInterface(socket, iface.interfaceName)

        // Binding via network results in packet sending on loopback iface
        //agent.network.bindSocket(socket)

        val originalPacket = testPacket.readAsArray()
        // Send packet to dest port matching policy and another port to confirm TOS is being set
        // only for DSCP policy packets
        Os.sendto(socket, originalPacket, 0 /* bytesOffset */, originalPacket.size /* bytesCount */,
                0 /* flags */, TEST_TARGET_IPV4_LOOPBACK_ADDR, 5555 /* port */)
        Os.sendto(socket, originalPacket, 0 /* bytesOffset */, originalPacket.size /* bytesCount */,
                0 /* flags */, TEST_TARGET_IPV4_LOOPBACK_ADDR, 5554 /* port */)

        generateSequence { reader.poll(PACKET_TIMEOUT_MS) }.forEach { packet ->
            var buffer = ByteBuffer.wrap(packet, 0, packet.size).order(ByteOrder.BIG_ENDIAN)
            var ip_ver = buffer.get()
            var tos = buffer.get()
            var length = buffer.getShort()
            var id = buffer.getShort()
            var offset = buffer.getShort()
            var ttl = buffer.get()
            var ipType = buffer.get()
            var checksum = buffer.getShort()

            var ipAddr = ByteArray(4)
            buffer.get(ipAddr)
            var srcIp = Inet4Address.getByAddress(ipAddr);
            buffer.get(ipAddr)
            var dstIp = Inet4Address.getByAddress(ipAddr);

            if (srcIp == LOCAL_IPV4_ADDRESS && dstIp == TEST_TARGET_IPV4_LOOPBACK_ADDR) {
                Log.d(TAG, "SRC/DST IP match")
                Log.d(TAG, "TOS = " + tos)
                Log.d(TAG, "DSCP = " + (tos.toInt().shr(2)))
            }
        }
    }

    @Test
    fun testRemoveDscpPolicy(): Unit = createConnectedNetworkAgent().let { (agent, callback) ->
        var policy = DscpPolicy.Builder(1).build()
        agent.sendAddDscpPolicy(policy)
        agent.expectCallback<OnDscpPolicyStatusUpdated>().let {
            assertEquals(1, it.policyId)
            assertEquals(DSCP_POLICY_STATUS_SUCCESS, it.status)
        }

        var policy2 = DscpPolicy.Builder(2).build()
        agent.sendAddDscpPolicy(policy2)
        agent.expectCallback<OnDscpPolicyStatusUpdated>().let {
            assertEquals(2, it.policyId)
            assertEquals(DSCP_POLICY_STATUS_SUCCESS, it.status)
        }

        var policy3 = DscpPolicy.Builder(3).build()
        agent.sendAddDscpPolicy(policy3)
        agent.expectCallback<OnDscpPolicyStatusUpdated>().let {
            assertEquals(3, it.policyId)
            assertEquals(DSCP_POLICY_STATUS_SUCCESS, it.status)
        }

        agent.sendRemoveDscpPolicy(1)
        agent.expectCallback<OnDscpPolicyStatusUpdated>().let {
            assertEquals(1, it.policyId)
            assertEquals(DSCP_POLICY_STATUS_DELETED, it.status)
        }
        agent.sendRemoveDscpPolicy(2)
        agent.expectCallback<OnDscpPolicyStatusUpdated>().let {
            assertEquals(2, it.policyId)
            assertEquals(DSCP_POLICY_STATUS_DELETED, it.status)
        }
        agent.sendRemoveDscpPolicy(3)
        agent.expectCallback<OnDscpPolicyStatusUpdated>().let {
            assertEquals(3, it.policyId)
            assertEquals(DSCP_POLICY_STATUS_DELETED, it.status)
        }
    }

    @Test
    fun sendRemoveAllDscpPolicies(): Unit = createConnectedNetworkAgent().let { (agent, callback) ->
        var policy = DscpPolicy.Builder(1).build()
        agent.sendAddDscpPolicy(policy)
        agent.expectCallback<OnDscpPolicyStatusUpdated>().let {
            assertEquals(1, it.policyId)
            assertEquals(DSCP_POLICY_STATUS_SUCCESS, it.status)
        }

        var policy2 = DscpPolicy.Builder(2).build()
        agent.sendAddDscpPolicy(policy2)
        agent.expectCallback<OnDscpPolicyStatusUpdated>().let {
            assertEquals(2, it.policyId)
            assertEquals(DSCP_POLICY_STATUS_SUCCESS, it.status)
        }

        var policy3 = DscpPolicy.Builder(3).build()
        agent.sendAddDscpPolicy(policy3)
        agent.expectCallback<OnDscpPolicyStatusUpdated>().let {
            assertEquals(3, it.policyId)
            assertEquals(DSCP_POLICY_STATUS_SUCCESS, it.status)
        }

        agent.sendRemoveAllDscpPolicies()
        agent.expectCallback<OnDscpPolicyStatusUpdated>().let {
            assertEquals(1, it.policyId)
            assertEquals(DSCP_POLICY_STATUS_DELETED, it.status)
        }
        agent.expectCallback<OnDscpPolicyStatusUpdated>().let {
            assertEquals(2, it.policyId)
            assertEquals(DSCP_POLICY_STATUS_DELETED, it.status)
        }
        agent.expectCallback<OnDscpPolicyStatusUpdated>().let {
            assertEquals(3, it.policyId)
            assertEquals(DSCP_POLICY_STATUS_DELETED, it.status)
        }
    }

    @Test
    fun addDuplicateDscpPolicy(): Unit = createConnectedNetworkAgent().let { (agent, callback) ->
        var policy = DscpPolicy.Builder(1).build()
        agent.sendAddDscpPolicy(policy)
        agent.expectCallback<OnDscpPolicyStatusUpdated>().let {
            assertEquals(1, it.policyId)
            assertEquals(DSCP_POLICY_STATUS_SUCCESS, it.status)
        }

        var policy2 = DscpPolicy.Builder(1).build()
        agent.sendAddDscpPolicy(policy2)
        agent.expectCallback<OnDscpPolicyStatusUpdated>().let {
            assertEquals(2, it.policyId)
            assertEquals(DSCP_POLICY_STATUS_SUCCESS, it.status)
        }
    }
}

private fun ByteBuffer.readAsArray(): ByteArray {
    val out = ByteArray(remaining())
    get(out)
    return out
}

private fun <T> Context.assertHasService(manager: Class<T>): T {
    return getSystemService(manager) ?: fail("Service $manager not found")
}