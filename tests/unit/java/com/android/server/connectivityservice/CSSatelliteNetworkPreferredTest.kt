import android.net.*
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.TRANSPORT_SATELLITE
import android.net.netd.aidl.NativeUidRangeConfig
import android.os.Build
import android.os.UserHandle
import android.util.ArraySet
import com.android.net.module.util.CollectionUtils
import com.android.server.*
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import org.junit.Assert
import org.junit.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.mockito.Mockito.inOrder

@IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class CSSatelliteNetworkPreferredTest : CSTest() {
    /**
     * Test createMultiLayerNrisFromSatelliteNetworkPreferredUids returns correct
     * NetworkRequestInfo.
     */
    @Test
    fun testCreateMultiLayerNrisFromSatelliteNetworkPreferredUids() {
        // Verify that empty uid set should not create any NRI for it.
        val nrisNoUid: Set<ConnectivityService.NetworkRequestInfo> =
            service.createMultiLayerNrisFromSatelliteNetworkPreferredUids(
                ArraySet()
            )
        Assert.assertEquals(0, nrisNoUid.size.toLong())
        val uid1 = PRIMARY_USER_HANDLE.getUid(TEST_PACKAGE_UID)
        val uid2 = PRIMARY_USER_HANDLE.getUid(TEST_PACKAGE_UID2)
        val uid3 = SECONDARY_USER_HANDLE.getUid(TEST_PACKAGE_UID)
        assertCreateMultiLayerNrisFromSatelliteNetworkPreferredUids(mutableSetOf(uid1))
        assertCreateMultiLayerNrisFromSatelliteNetworkPreferredUids(mutableSetOf(uid1, uid3))
        assertCreateMultiLayerNrisFromSatelliteNetworkPreferredUids(mutableSetOf(uid1, uid2))
    }

    /**
     * Test that SATELLITE_NETWORK_PREFERENCE_UIDS changes will send correct net id and uid ranges
     * to netd.
     */
    @Test
    @Throws(Exception::class)
    fun testSatelliteNetworkPreferredUidsChanged() {
        val netdInOrder = inOrder(netd)

        val satelliteAgent = createSatelliteAgent("satellite0")
        satelliteAgent.connect()

        val satelliteNetId: Int = satelliteAgent.network.netId
        netdInOrder.verify(netd).networkCreate(
            nativeNetworkConfigPhysical(satelliteNetId, INetd.PERMISSION_NONE))

        val uid1 = PRIMARY_USER_HANDLE.getUid(TEST_PACKAGE_UID)
        val uid2 = PRIMARY_USER_HANDLE.getUid(TEST_PACKAGE_UID2)
        val uid3 = SECONDARY_USER_HANDLE.getUid(TEST_PACKAGE_UID)

        // Initial satellite network preferred uids status.
        setAndUpdateSatelliteNetworkPreferredUids(setOf())
        netdInOrder.verify(netd, Mockito.never())
            .networkAddUidRangesParcel(ArgumentMatchers.any())
        netdInOrder.verify(netd, Mockito.never())
            .networkRemoveUidRangesParcel(ArgumentMatchers.any())

        // Set SATELLITE_NETWORK_PREFERENCE_UIDS setting and verify that net id and uid ranges
        // send to netd
        var uids = mutableSetOf(uid1, uid2, uid3)
        val uidRanges1: Array<UidRangeParcel?> = toUidRangeStableParcels(uidRangesForUids(uids))
        val config1 = NativeUidRangeConfig(
            satelliteNetId, uidRanges1,
            40 /* PREFERENCE_ORDER_SATELLITE_FALLBACK_PREFERRED*/
        )
        setAndUpdateSatelliteNetworkPreferredUids(uids)
        netdInOrder.verify(netd).networkAddUidRangesParcel(config1)
        netdInOrder.verify(netd, Mockito.never())
            .networkRemoveUidRangesParcel(ArgumentMatchers.any())

        // Set SATELLITE_NETWORK_PREFERENCE_UIDS setting again and verify that old rules are removed
        // and new rules are added.
        uids = mutableSetOf(uid1)
        val uidRanges2: Array<UidRangeParcel?> = toUidRangeStableParcels(uidRangesForUids(uids))
        val config2 = NativeUidRangeConfig(
            satelliteNetId, uidRanges2,
            40 /* PREFERENCE_ORDER_SATELLITE_FALLBACK_PREFERRED*/
        )
        setAndUpdateSatelliteNetworkPreferredUids(uids)
        netdInOrder.verify(netd, Mockito.times(1))
            .networkRemoveUidRangesParcel(config1)
        netdInOrder.verify(netd, Mockito.times(1))
            .networkAddUidRangesParcel(config2)
    }

    private fun assertCreateMultiLayerNrisFromSatelliteNetworkPreferredUids(uids: Set<Int>) {
        val nris: Set<ConnectivityService.NetworkRequestInfo> =
            service.createMultiLayerNrisFromSatelliteNetworkPreferredUids(uids)
        val nri = nris.iterator().next()
        // Verify that one NRI is created with multilayer requests. Because one NRI can contain
        // multiple uid ranges, so it only need create one NRI here.
        Assert.assertEquals(1, nris.size.toLong())
        Assert.assertTrue(nri.isMultilayerRequest)
        Assert.assertEquals(nri.uids, uidRangesForUids(uids))
        Assert.assertEquals(
            40 /* PREFERENCE_ORDER_SATELLITE_FALLBACK_PREFERRED*/,
            nri.mPreferenceOrder
        )
    }

    private fun setAndUpdateSatelliteNetworkPreferredUids(uids: Set<Int>) {
        Mockito.doReturn(uids).`when`(satelliteAccessController)
            .getSatelliteNetworkPreferredUids()
        service.updateSatelliteNetworkPreferenceUids()
        waitForIdle()
    }

    private fun nativeNetworkConfigPhysical(netId: Int, permission: Int) =
        NativeNetworkConfig(netId, NativeNetworkType.PHYSICAL, permission,
            false /* secure */, VpnManager.TYPE_VPN_NONE, false /* excludeLocalRoutes */)

    private fun createSatelliteAgent(name: String): CSAgentWrapper {
        return Agent(score = keepScore(), lp = lp(name),
            nc = nc(TRANSPORT_SATELLITE, NET_CAPABILITY_INTERNET)
        )
    }

    private fun toUidRangeStableParcels(ranges: Set<UidRange>): Array<UidRangeParcel?> {
        val stableRanges = arrayOfNulls<UidRangeParcel>(ranges.size)
        for ((index, range) in ranges.withIndex()) {
            stableRanges[index] = UidRangeParcel(range.start, range.stop)
        }
        return stableRanges
    }

    private fun uidRangesForUids(vararg uids: Int): Set<UidRange> {
        val ranges = ArraySet<UidRange>()
        for (uid in uids) {
            ranges.add(UidRange(uid, uid))
        }
        return ranges
    }

    private fun uidRangesForUids(uids: Collection<Int>): Set<UidRange> {
        return uidRangesForUids(*CollectionUtils.toIntArray(uids))
    }

    companion object {
        private const val SECONDARY_USER = 10
        private val SECONDARY_USER_HANDLE = UserHandle(SECONDARY_USER)
        private const val TEST_PACKAGE_UID = 123
        private const val TEST_PACKAGE_UID2 = 321
    }
}
