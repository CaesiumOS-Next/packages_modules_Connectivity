package android.net.cts

import android.net.EthernetTetheringTestBase
import android.net.TestNetworkInterface
import android.net.TetheringManager
import android.net.TetheringManager.TetheringRequest
import android.os.Build
import android.util.Log
import androidx.test.filters.SmallTest
import com.android.testutils.ConnectivityModuleTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.TapPacketReader
import java.net.NetworkInterface
import org.junit.Assert
import org.junit.Assume
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

    @Test
    fun testMdnsDiscoveryWorkOnDownstreamTetheringInterface()  {
        // This test requires shims supporting T+ APIs (NsdServiceInfo.network)
        assumeTrue(TestUtils.shouldTestTApis())
        Assume.assumeFalse(isInterfaceForTetheringAvailable)
        setIncludeTestInterfaces(true)

        var downstreamIface: TestNetworkInterface? = null
        var tetheringEventCallback: MyTetheringEventCallback? = null
        var downstreamReader: TapPacketReader? = null

        try {
            downstreamIface = createTestInterface()
            val iface = tetheredInterface
            Assert.assertEquals(
                "TetheredInterfaceCallback for unexpected interface",
                downstreamIface.getInterfaceName(), iface
            )
            val request = TetheringRequest.Builder(TetheringManager.TETHERING_ETHERNET)
                .setConnectivityScope(TetheringManager.CONNECTIVITY_SCOPE_LOCAL).build()
            tetheringEventCallback = enableEthernetTethering(
                iface, request,
                null /* any upstream */
            )
            tetheringEventCallback.awaitInterfaceLocalOnly()

            downstreamReader = makePacketReader(downstreamIface)
            waitForRouterAdvertisement(downstreamReader, iface, WAIT_RA_TIMEOUT_MS)
            val itface = NetworkInterface.getByName(iface)
            val interfaceAddresses = itface.interfaceAddresses
            Log.i(TAG, "ifaceName: $iface, idx:${itface.index}, interfaces: $interfaceAddresses")
        } finally {
            maybeStopTapPacketReader(downstreamReader)
            maybeCloseTestInterface(downstreamIface)
            maybeUnregisterTetheringEventCallback(tetheringEventCallback)
        }
    }

}