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
package com.android.server.connectivity

import android.content.Context
import android.content.res.Resources
import android.net.ConnectivityManager
import android.net.ISocketKeepaliveCallback
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NattSocketKeepalive
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.system.OsConstants.RT_SCOPE_UNIVERSE
import androidx.test.filters.SmallTest
import com.android.connectivity.resources.R
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import java.net.InetAddress
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock

@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
class KeepaliveTrackerTest {
    val mCtx = mock(Context::class.java)
    val mNai = mock(NetworkAgentInfo::class.java)

    val mCallback = mock(ISocketKeepaliveCallback::class.java)
    val mDependencies = mock(KeepaliveTracker.Dependencies::class.java)
    val mConnectivityResources = mock(ConnectivityResources::class.java)
    val mResources: Resources = mock(Resources::class.java)

    private val mKeepaliveTracker by lazy {
        KeepaliveTracker(
                mCtx,
                mock(Handler::class.java),
                mock(TcpKeepaliveController::class.java),
                mDependencies
        )
    }
    private val mNetwork = Network(TEST_NETID)
    private val mSocket = Socket()
    private val mFd by lazy {
        mSocket.`getFileDescriptor$`()
    }

    @Before
    fun setup() {
        setupTestNetwork()
        mSocket.bind(null)
        doReturn(intArrayOf(1, 3, 0, 0, 0, 0, 0, 0, 0, 0)).`when`(mDependencies)
                .getSupportedKeepalives(mCtx)
        doReturn(mConnectivityResources).`when`(mDependencies).createConnectivityResources(mCtx)
        doReturn(mResources).`when`(mConnectivityResources).get()
        doReturn(2).`when`(mResources)
                .getInteger(R.integer.config_reservedPrivilegedKeepaliveSlots)
        doReturn(2).`when`(mResources)
                .getInteger(R.integer.config_allowedUnprivilegedKeepalivePerUid)
        val binder: IBinder = Binder()
        doReturn(binder).`when`(mCallback).asBinder()
    }

    private fun setupTestNetwork() {
        mNai.networkCapabilities = NetworkCapabilities.Builder().addTransportType(
                NetworkCapabilities.TRANSPORT_CELLULAR
        ).build()
        mNai.networkInfo =
                NetworkInfo(ConnectivityManager.TYPE_MOBILE, 0 /* subtype */, "LTE", "LTE")
        mNai.networkInfo.setDetailedState(
                NetworkInfo.DetailedState.CONNECTED, "test reason", "test extra info"
        )
        mNai.linkProperties = LinkProperties()
        mNai.linkProperties.addLinkAddress(LinkAddress(InetAddress.getByName("192.0.0.4"), 24))
        mNai.linkProperties.addLinkAddress(LinkAddress(
            InetAddress.getByName("2001:db8::1"), 64, 0, RT_SCOPE_UNIVERSE))

        doReturn(mNetwork).`when`(mNai).network()
    }

    @Test
    fun testMakeNattKeepaliveInfo() {
        // Start clat
        val clatd = mock(Nat464Xlat::class.java)
        doReturn(mNetwork).`when`(mNai).network()
        doReturn(clatd).`when`(mNai).clatd()
        doReturn(false).`when`(clatd).isStarted

        val infoV4 = mKeepaliveTracker.makeNattKeepaliveInfo(
                mNai,
                mFd,
                TEST_INTERVAL_SEC,
                mCallback!!,
                "192.0.0.4",
                12345,
                "1.2.3.4",
                NattSocketKeepalive.NATT_PORT
        )
        assertNotNull(infoV4)
        assertEquals(InetAddress.getByName("192.0.0.4"), infoV4.mPacket.getSrcAddress())
        assertEquals(InetAddress.getByName("1.2.3.4"), infoV4.mPacket.getDstAddress())

        // Sending v6 keepalive is not allowed when clat is not started.
        val infoV6 = mKeepaliveTracker.makeNattKeepaliveInfo(
                mNai,
                mFd,
                TEST_INTERVAL_SEC,
                mCallback!!,
                "2001:db8::1",
                12345,
                "2001:4860:4860::64",
                NattSocketKeepalive.NATT_PORT
        )
        assertNull(infoV6)

        // Start clat
        doReturn(true).`when`(clatd).isStarted
        // v4 will be translate to v6 addresses.
        val infoV4_translated = mKeepaliveTracker.makeNattKeepaliveInfo(
                mNai,
                mFd,
                TEST_INTERVAL_SEC,
                mCallback!!,
                "192.0.0.4",
                12345,
                "1.2.3.4",
                NattSocketKeepalive.NATT_PORT
        )
        assertNotNull(infoV4_translated)
        // Check hostname because InetAddress.getByName("::ffff:1.2.3.4") will be translated back
        // into a v4 address
        assertEquals("2001:db8::1", infoV4_translated.mPacket.getSrcAddress().getHostName())
        assertEquals("::ffff:1.2.3.4", infoV4_translated.mPacket.getDstAddress().getHostName())
    }

    companion object {
        private const val TEST_NETID = 12345
        private const val TEST_INTERVAL_SEC = 10
    }
}
