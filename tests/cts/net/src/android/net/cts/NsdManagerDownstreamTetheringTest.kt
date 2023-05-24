/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.net.EthernetTetheringTestBase
import android.net.LinkAddress
import android.net.Network
import android.net.TestNetworkInterface
import android.net.TetheringManager.CONNECTIVITY_SCOPE_LOCAL
import android.net.TetheringManager.TETHERING_ETHERNET
import android.net.TetheringManager.TetheringRequest
import android.net.nsd.NsdManager
import android.os.Build
import androidx.test.filters.SmallTest
import com.android.testutils.ConnectivityModuleTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.TapPacketReader
import com.android.testutils.tryTest
import com.android.testutils.waitForIdle
import java.util.Random
import java.util.concurrent.Executor
import kotlin.test.assertNotNull
import org.junit.Assert
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "NsdManagerDownstreamTetheringTest"
private const val TIMEOUT_MS = 2000L

@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@ConnectivityModuleTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
class NsdManagerDownstreamTetheringTest : EthernetTetheringTestBase() {
    // Rule used to filter CtsNetTestCasesMaxTargetSdkXX
    @get:Rule
    val ignoreRule = DevSdkIgnoreRule()

    private val nsdManager by lazy { mContext.getSystemService(NsdManager::class.java) }
    private val serviceType = "_nmt%09d._tcp".format(Random().nextInt(1_000_000_000))

    @Test
    fun testMdnsDiscoveryWorkOnLocalOnlyDownstreamTetheringInterface() {
        // This test requires shims supporting T+ APIs (NsdServiceInfo.network)
        assumeTrue(TestUtils.shouldTestTApis())
        assumeFalse(isInterfaceForTetheringAvailable)
        setIncludeTestInterfaces(true)

        var downstreamIface: TestNetworkInterface? = null
        var tetheringEventCallback: MyTetheringEventCallback? = null
        var downstreamReader: TapPacketReader? = null

        val discoveryRecord = NsdDiscoveryRecord()
        mHandlerThread.waitForIdle(TIMEOUT_MS)

        tryTest {
            downstreamIface = createTestInterface()
            val iface = tetheredInterface
            Assert.assertEquals(
                "TetheredInterfaceCallback for unexpected interface",
                downstreamIface!!.interfaceName, iface
            )
            val request = TetheringRequest.Builder(TETHERING_ETHERNET)
                .setConnectivityScope(CONNECTIVITY_SCOPE_LOCAL).build()
            tetheringEventCallback = enableEthernetTethering(
                iface, request,
                null /* any upstream */
            )
            tetheringEventCallback!!.awaitInterfaceLocalOnly()

            downstreamReader = makePacketReader(downstreamIface)
            waitForRouterAdvertisement(downstreamReader, iface, WAIT_RA_TIMEOUT_MS)

            val targetNetwork: Network? = null
            nsdShim.discoverServices(nsdManager, serviceType, NsdManager.PROTOCOL_DNS_SD,
                targetNetwork, Executor { it.run() }, discoveryRecord)
            discoveryRecord.expectCallback<NsdDiscoveryRecord.DiscoveryEvent.DiscoveryStarted>()
            assertNotNull(downstreamReader!!.pollForQuery("$serviceType.local", 12 /* type PTR */))
        } cleanupStep {
            nsdManager.stopServiceDiscovery(discoveryRecord)
            discoveryRecord.expectCallback<NsdDiscoveryRecord.DiscoveryEvent.DiscoveryStopped>()
        } cleanup {
                maybeStopTapPacketReader(downstreamReader)
                maybeCloseTestInterface(downstreamIface)
                maybeUnregisterTetheringEventCallback(tetheringEventCallback)
        }
    }

    @Test
    fun testMdnsDiscoveryWorkOnTetheringInterface() {
        // This test requires shims supporting T+ APIs (NsdServiceInfo.network)
        assumeTrue(TestUtils.shouldTestTApis())
        assumeFalse(isInterfaceForTetheringAvailable)
        setIncludeTestInterfaces(true)

        var downstreamIface: TestNetworkInterface? = null
        var tetheringEventCallback: MyTetheringEventCallback? = null
        var downstreamReader: TapPacketReader? = null

        val discoveryRecord = NsdDiscoveryRecord()
        mHandlerThread.waitForIdle(TIMEOUT_MS)

        tryTest {
            downstreamIface = createTestInterface()
            val iface = tetheredInterface
            Assert.assertEquals(
                "TetheredInterfaceCallback for unexpected interface",
                downstreamIface!!.interfaceName, iface
            )

            val localAddr = LinkAddress("192.0.2.3/28")
            val clientAddr = LinkAddress("192.0.2.2/28")
            val request = TetheringRequest.Builder(TETHERING_ETHERNET)
                .setStaticIpv4Addresses(localAddr, clientAddr)
                .setShouldShowEntitlementUi(false).build()
            tetheringEventCallback = enableEthernetTethering(
                iface, request,
                null /* any upstream */
            )
            tetheringEventCallback!!.awaitInterfaceTethered()

            val fd = downstreamIface!!.fileDescriptor.fileDescriptor
            downstreamReader = makePacketReader(fd, getMTU(downstreamIface))

            nsdShim.discoverServices(nsdManager, serviceType, NsdManager.PROTOCOL_DNS_SD,
                null as Network?, Executor { it.run() }, discoveryRecord)
            discoveryRecord.expectCallback<NsdDiscoveryRecord.DiscoveryEvent.DiscoveryStarted>()
            assertNotNull(downstreamReader!!.pollForQuery("$serviceType.local", 12 /* type PTR */))
        } cleanupStep {
            nsdManager.stopServiceDiscovery(discoveryRecord)
            discoveryRecord.expectCallback<NsdDiscoveryRecord.DiscoveryEvent.DiscoveryStopped>()
        } cleanup {
            maybeStopTapPacketReader(downstreamReader)
            maybeCloseTestInterface(downstreamIface)
            maybeUnregisterTetheringEventCallback(tetheringEventCallback)
        }
    }
}
