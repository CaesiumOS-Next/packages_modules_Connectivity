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

import android.app.Instrumentation
import android.Manifest.permission.CONNECTIVITY_INTERNAL
import android.Manifest.permission.MANAGE_TEST_NETWORKS
import android.Manifest.permission.NETWORK_SETTINGS
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.DscpPolicy
import android.net.EthernetManager
import android.net.InetAddresses
import android.net.IpPrefix
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NetworkAgent
import android.net.NetworkAgentConfig
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN
import android.net.NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED
import android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED
import android.net.NetworkCapabilities.TRANSPORT_TEST
import android.net.NetworkCapabilities.TRANSPORT_VPN
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
import android.system.OsConstants.IPPROTO_UDP
import android.system.OsConstants.SOCK_DGRAM
import android.system.OsConstants.SOCK_NONBLOCK
import android.util.Log
import androidx.test.InstrumentationRegistry
//import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
//import com.android.server.NetworkAgentWrapper
import com.android.modules.utils.build.SdkLevel
import com.android.server.ConnectivityService
import com.android.testutils.CompatUtil
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.OffsetFilter
import com.android.testutils.TapPacketReader
import com.android.testutils.TestableNetworkAgent
import com.android.testutils.TestableNetworkAgent.CallbackEntry.OnNetworkCreated
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.runAsShell
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.BeforeClass
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
    private lateinit var reader: TapPacketReader

    @Before
    fun setUp() {
        runAsShell(MANAGE_TEST_NETWORKS) {
            val tnm = realContext.assertHasService(TestNetworkManager::class.java)
            iface = tnm.createTapInterface()

            // packet is not seen on iface with TUN. No examples for setup either
            //iface = tnm.createTunInterface( Array(1){ LinkAddress(LOCAL_IPV4_ADDRESS, 32) } )
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
        //runAsShell(CONNECTIVITY_INTERNAL) { cm.requestNetwork(request, callback) }
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
                setNetworkSpecifier(CompatUtil.makeTestNetworkSpecifier(specifier))
            }
        }
        val lp = initialLp ?: LinkProperties().apply {
            addLinkAddress(LinkAddress(LOCAL_IPV4_ADDRESS, 32))
            addRoute(RouteInfo(IpPrefix("0.0.0.0/0"), null, null))
            setInterfaceName(iface.getInterfaceName())
        }
        val config = initialConfig ?: NetworkAgentConfig.Builder().build()
        return TestableNetworkAgent(context, handlerThread.looper, nc, lp, config).also {
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

    private fun createPacket(srcIp: Inet4Address, dstIp: Inet4Address): ByteBuffer {
        // Is there a util function like buildRsPacket for building a normal packet?
        var buf = ByteBuffer.allocate(1500)

        var destIpArray = dstIp.getAddress()
        var srcIpArray = srcIp.getAddress()
        var ipHeaderOffset = 0
        var ipLengthOffset = 0
        var ipChecksumOffset = 0
        var endIpHeader = 0

        buf.clear()
        buf.order(ByteOrder.BIG_ENDIAN)

        ipHeaderOffset = buf.position()
        buf.put(IP_VERSION_HEADER_LEN) // IP version and IHL
        // Add 3 to DSCP to account for ECN
        buf.put((DSCP_TEST_VALUE + DSCP_OFFSET).toByte())    // tos: DSCP(8-13) + ECN(14-15)
        ipLengthOffset = buf.position()
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

        buf.flip()
        return buf
    }

    private fun getDscpVal(sentPacket: ByteBuffer): Boolean {
        //var packet: ByteArray
        var dscpVal = 1
        var packet = reader.poll(PACKET_TIMEOUT_MS)
        while (packet != null) {
            // pull DSCP value from packet
            Log.d(TAG, "reader Poll")
            packet = reader.poll(PACKET_TIMEOUT_MS)
        }
        // reader.poll(PACKET_TIMEOUT_MS).forEach {
        //     Log.d(TAG, "reader Poll")
        //     if (it.compare(sentPacket) == 0) return true;
        // }
        return false;

        // byte[] p;
        // while ((p = reader.popPacket(PACKET_TIMEOUT_MS)) != null) {
        //     final ByteBuffer buffer = ByteBuffer.wrap(p);

        //     if (buffer.compareTo(packet) == 0) return true;
        // }
        // return false;
    }

    fun ByteArray.toHex(): String = joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }

    @Test
    fun testDscpPolicyUpdatesPacket(): Unit = createConnectedNetworkAgent().let { (agent, callback) ->
        //var policy = DscpPolicy().Builder(1).setDscp(1).setSrcPort(5555).setDstPort(5555).build()
        //DscpPolicy(policyId: Int, dscp: Int, srcAddr: InetAddress!, dstAddr: InetAddress!,
        //  srcPort: Int, protocol: Int, dstPortRangeStart: Int, dstPortRangeEnd: Int)
        var policy = DscpPolicy(DSCP_TEST_VALUE, 1, null, null, 5555, 0, 5555, 5555)
        agent.sendQosAddDscpPolicy(policy);

        var srcIp = InetAddress.getByAddress(ByteArray(4)) as Inet4Address
        var dstIp = InetAddress.getByAddress(ByteArray(4)) as Inet4Address
        val dscpPacket = createPacket(srcIp, dstIp)

        val socket = Os.socket(AF_INET, SOCK_DGRAM or SOCK_NONBLOCK, IPPROTO_UDP)
        SocketUtils.bindSocketToInterface(socket, iface.interfaceName)
        val originalPacket = dscpPacket.readAsArray()
        Os.sendto(socket, originalPacket, 0 /* bytesOffset */, originalPacket.size /* bytesCount */,
                0 /* flags */, TEST_TARGET_IPV4_LOOPBACK_ADDR, 5555 /* port */)

        Log.d(TAG, "set hex array: " + originalPacket.toHex())

        //val sentPacket = reader.poll(PACKET_TIMEOUT_MS) ?: fail("Packet was not sent on the interface")
        //assertEquals(originalPacket, sentPacket)

        // generateSequence { reader.poll(PACKET_TIMEOUT_MS,
        //         OffsetFilter(IP_VERSION_HEADER_LEN + DSCP_OFFSET, DSCP_TEST_VALUE.toByte())) }.forEach { packet ->
        //     // Check DSCP bit
        //     assertEquals(originalPacket, packet)
        // }
        generateSequence { reader.poll(PACKET_TIMEOUT_MS) }.forEach { packet ->
            // Check DSCP bit
            //assertEquals(originalPacket, packet)
            //packet.get()
            Log.d(TAG, "byte array: " + packet.contentToString())
            Log.d(TAG, "hex array: " + packet.toHex())
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