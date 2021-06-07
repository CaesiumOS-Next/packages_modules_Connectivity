/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.Manifest.permission.READ_DEVICE_CONFIG
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.EthernetManager
import android.net.InetAddresses
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL
import android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_TEST
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.net.TestNetworkInterface
import android.net.TestNetworkManager
import android.net.Uri
import android.net.ConnectivityManager.NetworkCallback
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_PARTIAL_CONNECTIVITY
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.cts.NetworkValidationTestUtil.clearValidationTestUrlsDeviceConfig
import android.net.cts.NetworkValidationTestUtil.setHttpUrlDeviceConfig
import android.net.cts.NetworkValidationTestUtil.setHttpsUrlDeviceConfig
import android.net.cts.NetworkValidationTestUtil.setUrlExpirationDeviceConfig
import android.net.cts.util.CtsNetUtils
import android.net.dhcp.DhcpDiscoverPacket
import android.net.dhcp.DhcpPacket
import android.net.dhcp.DhcpPacket.DHCP_MESSAGE_TYPE
import android.net.dhcp.DhcpPacket.DHCP_MESSAGE_TYPE_DISCOVER
import android.net.dhcp.DhcpPacket.DHCP_MESSAGE_TYPE_REQUEST
import android.net.dhcp.DhcpRequestPacket
import android.net.util.NetworkStackUtils.TEST_CAPTIVE_PORTAL_HTTPS_URL
import android.net.util.NetworkStackUtils.TEST_CAPTIVE_PORTAL_HTTP_URL
import android.os.Build
import android.os.HandlerThread
import android.platform.test.annotations.AppModeFull
import android.provider.DeviceConfig
import android.text.TextUtils
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.android.compatibility.common.util.SystemUtil
import com.android.net.module.util.Inet4AddressUtils.getBroadcastAddress
import com.android.net.module.util.Inet4AddressUtils.getPrefixMaskAsInet4Address
import com.android.net.module.util.NetworkStackConstants.IPV4_ADDR_ANY
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DhcpClientPacketFilter
import com.android.testutils.DhcpOptionFilter
import com.android.testutils.ExceptionUtils
import com.android.testutils.RecorderCallback.CallbackEntry
import com.android.testutils.TapPacketReader
import com.android.testutils.TestHttpServer
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.assertThrows
import com.android.testutils.runAsShell
import com.android.testutils.TestHttpServer.Request
import fi.iki.elonen.NanoHTTPD.Response.IStatus
import fi.iki.elonen.NanoHTTPD.Response.Status
import junit.framework.AssertionFailedError
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet4Address
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

private const val MAX_PACKET_LENGTH = 1500
private const val TEST_TIMEOUT_MS = 10_000L

private const val TEST_LEASE_TIMEOUT_SECS = 3600 * 12
private const val TEST_PREFIX_LENGTH = 24

private const val TEST_LOGIN_URL = "https://login.capport.android.com"
private const val TEST_VENUE_INFO_URL = "https://venueinfo.capport.android.com"
private const val TEST_DOMAIN_NAME = "lan"
private const val TEST_MTU = 1500.toShort()

private const val TEST_HTTPS_URL_PATH = "/https_path"
private const val TEST_HTTP_URL_PATH = "/http_path"
private const val LOCALHOST_HOSTNAME = "localhost"

// Re-connecting to the AP, obtaining an IP address, revalidating can take a long time
private const val WIFI_CONNECT_TIMEOUT_MS = 60_000L

private fun <T> CompletableFuture<T>.assertGet(timeoutMs: Long, message: String): T {
    try {
        return get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        throw AssertionFailedError(message)
    }
}

@AppModeFull(reason = "Instant apps cannot create test networks")
@RunWith(AndroidJUnit4::class)
class NetworkValidationTest {
    @JvmField
    @Rule
    val ignoreRule = DevSdkIgnoreRule(ignoreClassUpTo = Build.VERSION_CODES.Q)

    private val context by lazy { InstrumentationRegistry.getInstrumentation().context }
    private val tnm by lazy { context.assertHasService(TestNetworkManager::class.java) }
    private val eth by lazy { context.assertHasService(EthernetManager::class.java) }
    private val cm by lazy { context.assertHasService(ConnectivityManager::class.java) }
    private val pm by lazy { context.packageManager }
    private val utils by lazy { CtsNetUtils(context) }

    private val handlerThread = HandlerThread(NetworkValidationTest::class.java.simpleName)
    private val serverIpAddr = InetAddresses.parseNumericAddress("192.0.2.222") as Inet4Address
    private val clientIpAddr = InetAddresses.parseNumericAddress("192.0.2.111") as Inet4Address
    private val httpServer = TestHttpServer(LOCALHOST_HOSTNAME)
    private val ethRequest = NetworkRequest.Builder()
            // ETHERNET|TEST transport networks do not have NET_CAPABILITY_TRUSTED
            .removeCapability(NET_CAPABILITY_TRUSTED)
            .addTransportType(TRANSPORT_ETHERNET)
            .addTransportType(TRANSPORT_TEST).build()
    private val ethRequestCb = TestableNetworkCallback()

    private lateinit var iface: TestNetworkInterface
    private lateinit var reader: TapPacketReader
    private lateinit var capportUrl: Uri

    private var testSkipped = false

    @Before
    fun setUp() {
        // This test requires using a tap interface as an ethernet interface.
        val pm = context.getPackageManager()
        testSkipped = !pm.hasSystemFeature(PackageManager.FEATURE_ETHERNET) &&
                context.getSystemService(EthernetManager::class.java) == null
        assumeFalse(testSkipped)

        // Register a request so the network does not get torn down
        cm.requestNetwork(ethRequest, ethRequestCb)
        runAsShell(NETWORK_SETTINGS, MANAGE_TEST_NETWORKS, READ_DEVICE_CONFIG) {
            eth.setIncludeTestInterfaces(true)
            // Keeping a reference to the test interface also makes sure the ParcelFileDescriptor
            // does not go out of scope, which would cause it to close the underlying FileDescriptor
            // in its finalizer.
            iface = tnm.createTapInterface()

            // Verify that the test URLs are not normally set on the device, but do not fail if the
            // test URLs are set to what this test uses (URLs on localhost), in case the test was
            // interrupted manually and rerun.
            assertEmptyOrLocalhostUrl(TEST_CAPTIVE_PORTAL_HTTPS_URL)
            assertEmptyOrLocalhostUrl(TEST_CAPTIVE_PORTAL_HTTP_URL)
        }

        clearValidationTestUrlsDeviceConfig()

        handlerThread.start()
        reader = TapPacketReader(
                handlerThread.threadHandler,
                iface.fileDescriptor.fileDescriptor,
                MAX_PACKET_LENGTH)
        reader.startAsyncForTest()
        httpServer.start()

        // Pad the listening port to make sure it is always of length 5. This ensures the URL has
        // always the same length so the test can use constant IP and UDP header lengths.
        // The maximum port number is 65535 so a length of 5 is always enough.
        capportUrl = Uri.parse("http://localhost:${httpServer.listeningPort}/testapi.html?par=val")
    }

    @After
    fun tearDown() {
        if (testSkipped) return
        cm.unregisterNetworkCallback(ethRequestCb)

        clearValidationTestUrlsDeviceConfig()

        runAsShell(NETWORK_SETTINGS) { eth.setIncludeTestInterfaces(false) }

        httpServer.stop()
        handlerThread.threadHandler.post { reader.stop() }
        handlerThread.quitSafely()

        iface.fileDescriptor.close()
    }

    private fun assertEmptyOrLocalhostUrl(urlKey: String) {
        val url = DeviceConfig.getProperty(DeviceConfig.NAMESPACE_CONNECTIVITY, urlKey)
        assertTrue(TextUtils.isEmpty(url) || LOCALHOST_HOSTNAME == Uri.parse(url).host,
                "$urlKey must not be set in production scenarios (current value: $url)")
    }

    private fun makeUrl(path: String) = "http://localhost:${httpServer.listeningPort}$path"

    private fun reconnectWifi() {
        utils.ensureWifiDisconnected(null /* wifiNetworkToCheck */)
        utils.ensureWifiConnected()
    }

    private fun configTestServer(
        httpsStatusCode: IStatus = Status.NO_CONTENT,
        httpStatusCode: IStatus = Status.NO_CONTENT
    ) {
        httpServer.addResponse(Request(TEST_HTTPS_URL_PATH), httpsStatusCode)
        httpServer.addResponse(Request(TEST_HTTP_URL_PATH), httpStatusCode)
        setHttpsUrlDeviceConfig(makeUrl(TEST_HTTPS_URL_PATH))
        setHttpUrlDeviceConfig(makeUrl(TEST_HTTP_URL_PATH))
        // URL expiration needs to be in the next 10 minutes
        assertTrue(WIFI_CONNECT_TIMEOUT_MS < TimeUnit.MINUTES.toMillis(10))
        setUrlExpirationDeviceConfig(System.currentTimeMillis() + WIFI_CONNECT_TIMEOUT_MS)
    }

    private fun makeWifiRequest() =
            NetworkRequest.Builder().addTransportType(TRANSPORT_WIFI).build()

    @Test
    fun testSetAcceptPartialConnectivity_NoPermission_GetException() {
        assumeTrue(TestUtils.shouldTestSApis())
        val network = cm.getActiveNetwork()
        network?.let {
            assertThrows(SecurityException::class.java, ExceptionUtils.ThrowingRunnable {
                cm.setAcceptPartialConnectivity(network, false /* accept */, false /* always */)
            })
        } ?: run {
            fail("CTS requires a working Internet connection")
        }
    }

    @Test
    fun testAcceptPartialConnectivity_validatedNetwork() {
        assumeTrue(TestUtils.shouldTestSApis())
        assumeTrue(pm.hasSystemFeature(PackageManager.FEATURE_WIFI))
        // Configure response code for partial connectivity
        configTestServer(httpsStatusCode = Status.INTERNAL_ERROR,
                httpStatusCode = Status.NO_CONTENT)

        val wifiNetworkFuture = CompletableFuture<Network>()
        val wifiValidatedFuture = CompletableFuture<Network>()
        val wifiCb = object : NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                nc: NetworkCapabilities
            ) {
                if (nc.hasCapability(NET_CAPABILITY_PARTIAL_CONNECTIVITY)) {
                    wifiNetworkFuture.complete(network)
                }

                if (nc.hasCapability(NET_CAPABILITY_VALIDATED)) {
                    wifiValidatedFuture.complete(network)
                }
            }
        }

        try {
            // Disconnect wifi first then start wifi network with configuration.
            utils.ensureWifiDisconnected(null /* wifiNetworkToCheck */)

            cm.requestNetwork(makeWifiRequest(), wifiCb)
            utils.ensureWifiConnected()
            // Wait for partial connectivity to be detected on the network
            val network = wifiNetworkFuture.assertGet(WIFI_CONNECT_TIMEOUT_MS,
                    "Partial connectivity is not detected after ${WIFI_CONNECT_TIMEOUT_MS}ms")

            runAsShell(NETWORK_SETTINGS) {
                // The always bit is verified in NetworkAgentTest
                cm.setAcceptPartialConnectivity(network, true /* accept */, false /* always */)
            }

            // Accept partial connectivity network should result in a validated network
            val validatedNetwork = wifiValidatedFuture.assertGet(WIFI_CONNECT_TIMEOUT_MS,
                    "Validated network is not detected after ${WIFI_CONNECT_TIMEOUT_MS}ms")
            assertEquals(network, validatedNetwork)
        } finally {
            cm.unregisterNetworkCallback(wifiCb)
            reconnectWifi()
        }
    }

    @Test
    fun testRejectPartialConnectivity_TearDownNetwork() {
        assumeTrue(TestUtils.shouldTestSApis())
        assumeTrue(pm.hasSystemFeature(PackageManager.FEATURE_WIFI))
        // Configure response code for partial connectivity
        configTestServer(httpsStatusCode = Status.INTERNAL_ERROR,
                httpStatusCode = Status.NO_CONTENT)

        val wifiNetworkFuture = CompletableFuture<Network>()
        val wifiLostFuture = CompletableFuture<Network>()
        val wifiCb = object : NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                nc: NetworkCapabilities
            ) {
                if (nc.hasCapability(NET_CAPABILITY_PARTIAL_CONNECTIVITY)) {
                    wifiNetworkFuture.complete(network)
                }
            }

            override fun onLost(network: Network) {
                wifiLostFuture.complete(network)
            }
        }

        try {
            // Disconnect wifi first then start wifi network with configuration.
            utils.ensureWifiDisconnected(null /* wifiNetworkToCheck */)

            cm.requestNetwork(makeWifiRequest(), wifiCb)

            utils.ensureWifiConnected()
            // Wait for partial connectivity to be detected on the network
            val network = wifiNetworkFuture.assertGet(WIFI_CONNECT_TIMEOUT_MS,
                    "Partial connectivity is not detected after ${WIFI_CONNECT_TIMEOUT_MS}ms")

            runAsShell(NETWORK_SETTINGS) {
                // The always bit is verified in NetworkAgentTest
                cm.setAcceptPartialConnectivity(network, false /* accept */, false /* always */)
            }
            // Reject partial connectivity network should cause the network being torn down
            val lostNetwork = wifiLostFuture.assertGet(WIFI_CONNECT_TIMEOUT_MS,
                    "Wifi is not disconnected after ${WIFI_CONNECT_TIMEOUT_MS}ms")
            assertEquals(network, lostNetwork)
        } finally {
            cm.unregisterNetworkCallback(wifiCb)
            // Wifi will not automatically reconnect to the network. Turn off wifi first and restart
            // to restore
            SystemUtil.runShellCommand("svc wifi disable")
            utils.ensureWifiConnected()
        }
    }

    @Test
    fun testSetUnvalidated_NoPermission_GetException() {
        assumeTrue(TestUtils.shouldTestSApis())
        val network = cm.getActiveNetwork()
        network?.let {
            assertThrows(SecurityException::class.java, ExceptionUtils.ThrowingRunnable {
                cm.setAcceptUnvalidated(network, false /* accept */, false /* always */)
                })
        } ?: run {
            fail("CTS requires a working Internet connection")
        }
    }

    @Test
    fun testRejectUnvalidated_TearDownNetwork() {
        assumeTrue(TestUtils.shouldTestSApis())
        assumeTrue(pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY))
        assumeTrue(pm.hasSystemFeature(PackageManager.FEATURE_WIFI))
        val cellNetwork = utils.connectToCell()

        // Verify cell network is validated
        val cellReq = NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR)
                .addCapability(NET_CAPABILITY_INTERNET)
                .build()
        val cellCb = TestableNetworkCallback(timeoutMs = TEST_TIMEOUT_MS)
        cm.registerNetworkCallback(cellReq, cellCb)
        val cb = cellCb.eventuallyExpectOrNull<CallbackEntry.CapabilitiesChanged> {
            it.network == cellNetwork && it.caps.hasCapability(NET_CAPABILITY_VALIDATED)
        }
        assertNotNull(cb, "Mobile network $cellNetwork has no access to the internet. " +
                "Check the mobile data connection.")

        // Configure response code for unvalidated network
        configTestServer(httpsStatusCode = Status.INTERNAL_ERROR,
                httpStatusCode = Status.INTERNAL_ERROR)

        val wifiNetworkFuture = CompletableFuture<Network>()
        val wifiLostFuture = CompletableFuture<Network>()
        val wifiCb = object : NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                nc: NetworkCapabilities
            ) {
                if (!nc.hasCapability(NET_CAPABILITY_VALIDATED)) {
                    wifiNetworkFuture.complete(network)
                }
            }

            override fun onLost(network: Network) {
                wifiLostFuture.complete(network)
            }
        }

        try {
            // Disconnect wifi first then start wifi network with configuration.
            utils.ensureWifiDisconnected(null /* wifiNetworkToCheck */)

            cm.requestNetwork(makeWifiRequest(), wifiCb)

            utils.ensureWifiConnected()
            // Wait for unvalidated network to be detected on the network
            val network = wifiNetworkFuture.assertGet(WIFI_CONNECT_TIMEOUT_MS,
                    "Unvalidated network is not detected after ${WIFI_CONNECT_TIMEOUT_MS}ms")
            // Default network should not be wifi.
            assertNotEquals(network, cm.getActiveNetwork())
            runAsShell(NETWORK_SETTINGS) {
                cm.setAcceptUnvalidated(network, false /* accept */, false /* always */)
            }
            // Reject unvalidated network should cause the network being torn down
            val lostNetwork = wifiLostFuture.assertGet(WIFI_CONNECT_TIMEOUT_MS,
                    "Wifi is not disconnected after ${WIFI_CONNECT_TIMEOUT_MS}ms")
            assertEquals(network, lostNetwork)
        } finally {
            cm.unregisterNetworkCallback(wifiCb)
            clearValidationTestUrlsDeviceConfig()
            // Wifi will not automatically reconnect to the network. Turn off wifi first and restart
            // to restore
            SystemUtil.runShellCommand("svc wifi disable")
            utils.ensureWifiConnected()
        }
    }

    @Test
    fun testCapportApiCallbacks() {
        httpServer.addResponse(capportUrl, Status.OK, content = """
                |{
                |  "captive": true,
                |  "user-portal-url": "$TEST_LOGIN_URL",
                |  "venue-info-url": "$TEST_VENUE_INFO_URL"
                |}
            """.trimMargin())

        // Handle the DHCP handshake that includes the capport API URL
        val discover = reader.assertDhcpPacketReceived(
                DhcpDiscoverPacket::class.java, TEST_TIMEOUT_MS, DHCP_MESSAGE_TYPE_DISCOVER)
        reader.sendResponse(makeOfferPacket(discover.clientMac, discover.transactionId))

        val request = reader.assertDhcpPacketReceived(
                DhcpRequestPacket::class.java, TEST_TIMEOUT_MS, DHCP_MESSAGE_TYPE_REQUEST)
        assertEquals(discover.transactionId, request.transactionId)
        assertEquals(clientIpAddr, request.mRequestedIp)
        reader.sendResponse(makeAckPacket(request.clientMac, request.transactionId))

        // The first request received by the server should be for the portal API
        assertTrue(httpServer.requestsRecord.poll(TEST_TIMEOUT_MS, 0)?.matches(capportUrl) ?: false,
                "The device did not fetch captive portal API data within timeout")

        // Expect network callbacks with capport info
        val testCb = TestableNetworkCallback(TEST_TIMEOUT_MS)
        // LinkProperties do not contain captive portal info if the callback is registered without
        // NETWORK_SETTINGS permissions.
        val lp = runAsShell(NETWORK_SETTINGS) {
            cm.registerNetworkCallback(ethRequest, testCb)

            try {
                val ncCb = testCb.eventuallyExpect<CallbackEntry.CapabilitiesChanged> {
                    it.caps.hasCapability(NET_CAPABILITY_CAPTIVE_PORTAL)
                }
                testCb.eventuallyExpect<CallbackEntry.LinkPropertiesChanged> {
                    it.network == ncCb.network && it.lp.captivePortalData != null
                }.lp
            } finally {
                cm.unregisterNetworkCallback(testCb)
            }
        }

        assertEquals(capportUrl, lp.captivePortalApiUrl)
        with(lp.captivePortalData) {
            assertNotNull(this)
            assertTrue(isCaptive)
            assertEquals(Uri.parse(TEST_LOGIN_URL), userPortalUrl)
            assertEquals(Uri.parse(TEST_VENUE_INFO_URL), venueInfoUrl)
        }
    }

    private fun makeOfferPacket(clientMac: ByteArray, transactionId: Int) =
            DhcpPacket.buildOfferPacket(DhcpPacket.ENCAP_L2, transactionId,
                    false /* broadcast */, serverIpAddr, IPV4_ADDR_ANY /* relayIp */, clientIpAddr,
                    clientMac, TEST_LEASE_TIMEOUT_SECS,
                    getPrefixMaskAsInet4Address(TEST_PREFIX_LENGTH),
                    getBroadcastAddress(clientIpAddr, TEST_PREFIX_LENGTH),
                    listOf(serverIpAddr) /* gateways */, listOf(serverIpAddr) /* dnsServers */,
                    serverIpAddr, TEST_DOMAIN_NAME, null /* hostname */, true /* metered */,
                    TEST_MTU, capportUrl.toString())

    private fun makeAckPacket(clientMac: ByteArray, transactionId: Int) =
            DhcpPacket.buildAckPacket(DhcpPacket.ENCAP_L2, transactionId,
                    false /* broadcast */, serverIpAddr, IPV4_ADDR_ANY /* relayIp */, clientIpAddr,
                    clientIpAddr /* requestClientIp */, clientMac, TEST_LEASE_TIMEOUT_SECS,
                    getPrefixMaskAsInet4Address(TEST_PREFIX_LENGTH),
                    getBroadcastAddress(clientIpAddr, TEST_PREFIX_LENGTH),
                    listOf(serverIpAddr) /* gateways */, listOf(serverIpAddr) /* dnsServers */,
                    serverIpAddr, TEST_DOMAIN_NAME, null /* hostname */, true /* metered */,
                    TEST_MTU, false /* rapidCommit */, capportUrl.toString())
}

private fun <T : DhcpPacket> TapPacketReader.assertDhcpPacketReceived(
    packetType: Class<T>,
    timeoutMs: Long,
    type: Byte
): T {
    val packetBytes = poll(timeoutMs, DhcpClientPacketFilter()
            .and(DhcpOptionFilter(DHCP_MESSAGE_TYPE, type)))
            ?: fail("${packetType.simpleName} not received within timeout")
    val packet = DhcpPacket.decodeFullPacket(packetBytes, packetBytes.size, DhcpPacket.ENCAP_L2)
    assertTrue(packetType.isInstance(packet),
            "Expected ${packetType.simpleName} but got ${packet.javaClass.simpleName}")
    return packetType.cast(packet)
}

private fun <T> Context.assertHasService(manager: Class<T>): T {
    return getSystemService(manager) ?: fail("Service $manager not found")
}
