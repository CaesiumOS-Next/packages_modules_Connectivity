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

package android.net.thread

import android.Manifest.permission.MANAGE_TEST_NETWORKS
import android.content.Context
import android.net.DnsResolver.TYPE_A
import android.net.InetAddresses.parseNumericAddress
import android.net.IpPrefix
import android.net.LinkProperties
import android.net.MacAddress
import android.net.RouteInfo
import android.net.thread.utils.FullThreadDevice
import android.net.thread.utils.InfraNetworkDevice
import android.net.thread.utils.IntegrationTestUtilsKt.startInfraDeviceAndWaitForOnLinkAddr
import android.net.thread.utils.IntegrationTestUtilsKt.setUpInfraNetwork
import android.net.thread.utils.IntegrationTestUtilsKt.tearDownInfraNetwork
import android.net.thread.utils.IntegrationTestUtils.DEFAULT_DATASET
import android.net.thread.utils.IntegrationTestUtils.joinNetworkAndWaitForOmr
import android.net.thread.utils.IntegrationTestUtils.newPacketReader
import android.net.thread.utils.IntegrationTestUtils.waitFor
import android.net.thread.utils.OtDaemonController
import android.net.thread.utils.ThreadFeatureCheckerRule
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresSimulationThreadDevice
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresThreadFeature
import android.net.thread.utils.ThreadNetworkControllerWrapper
import android.os.Handler
import android.os.HandlerThread
import android.system.OsConstants.ICMP_ECHO
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.android.testutils.*
import com.google.common.truth.Truth.assertThat
import java.net.Inet4Address
import java.net.InetAddress
import java.time.Duration
import java.util.function.Predicate
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith


/** Integration test cases for Thread Border Routing feature. */
@RunWith(AndroidJUnit4::class)
@RequiresThreadFeature
@RequiresSimulationThreadDevice
@LargeTest
class InternetAccessTest {

    private val TAG = BorderRoutingTest::class.java.simpleName
    private val NUM_FTD = 2
    private val IPV4_SERVER_ADDR = parseNumericAddress("8.8.8.8") as Inet4Address
    private val NAT64_CIDR = "192.168.255.0/24"
    private val IPV4_DNS_SERVER_ADDR = parseNumericAddress("192.0.2.1") as Inet4Address

    @get:Rule
    val mThreadRule = ThreadFeatureCheckerRule()

    private val mContext: Context = ApplicationProvider.getApplicationContext()
    private val mController = requireNotNull(ThreadNetworkControllerWrapper.newInstance(mContext))
    private lateinit var mOtCtl: OtDaemonController
    private lateinit var mHandlerThread: HandlerThread
    private lateinit var mHandler: Handler
    private lateinit var mInfraNetworkTracker: TestNetworkTracker
    private lateinit var mFtds: ArrayList<FullThreadDevice>
    private lateinit var mInfraNetworkReader: TapPacketReader
    private lateinit var mInfraDevice: InfraNetworkDevice

    @Before
    @Throws(Exception::class)
    fun setUp() {
        // TODO: b/323301831 - This is a workaround to avoid unnecessary delay to re-form a network
        mOtCtl = OtDaemonController()
        mOtCtl.factoryReset()

        mHandlerThread = HandlerThread(javaClass.simpleName)
        mHandlerThread.start()
        mHandler = Handler(mHandlerThread.looper)
        mFtds = ArrayList()

        mInfraNetworkTracker = setUpInfraNetwork(mContext, mController)
        mController.setEnabledAndWait(true)
        mController.joinAndWait(DEFAULT_DATASET)

        // Creates a infra network device.
        mInfraNetworkReader = newPacketReader(mInfraNetworkTracker.testIface, mHandler)
        mInfraDevice = startInfraDeviceAndWaitForOnLinkAddr(mInfraNetworkReader)

        // TODO: enable NAT64 via ThreadNetworkController API instead of ot-ctl
        mOtCtl.setNat64Cidr(NAT64_CIDR)
        mOtCtl.setNat64Enabled(true)
        waitFor({ mOtCtl.hasNat64PrefixInNetdata() }, Duration.ofSeconds(10))

        // Create Ftds
        for (i in 0 until NUM_FTD) {
            mFtds.add(FullThreadDevice(15 + i /* node ID */))
        }
    }

    @After
    @Throws(Exception::class)
    fun tearDown() {
        mController.setTestNetworkAsUpstreamAndWait(null)
        mController.leaveAndWait()
        tearDownInfraNetwork(mInfraNetworkTracker);

        mHandlerThread.quitSafely()
        mHandlerThread.join()

        mFtds.forEach { it.destroy() }
        mFtds.clear()
    }

    @Test
    fun nat64_threadDeviceResolvesIpv4Host_hostIsResolved() {
        val ftd = mFtds[0]
        joinNetworkAndWaitForOmr(ftd, DEFAULT_DATASET)

        assertThat(ftd.resolveHost("google.com", TYPE_A)).isNotEmpty()
    }
}
