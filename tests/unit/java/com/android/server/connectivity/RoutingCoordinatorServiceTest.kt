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

import android.net.INetd
import android.net.MulticastRoutingConfig
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.test.TestLooper
import android.system.Os
import android.system.OsConstants.AF_INET6
import android.system.OsConstants.IPPROTO_UDP
import android.system.OsConstants.SOCK_DGRAM
import android.util.Log
import androidx.test.filters.LargeTest
import com.android.net.module.util.structs.StructMrt6Msg
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.tryTest
import com.google.common.truth.Truth.assertThat
import java.io.FileDescriptor
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.timeout
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val TIMEOUT_MS = 2_000L

@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
class RoutingCoordinatorServiceTest {

    val mNetd = mock(INetd::class.java)
    val mDeps = mock(RoutingCoordinatorService.Dependencies::class.java)
    val mSock = DatagramSocket()
    val mPfd = ParcelFileDescriptor.fromDatagramSocket(mSock)
    val mFd = mPfd.getFileDescriptor()
    val mIfName1 = "interface1"
    val mIfName2 = "interface2"
    val mIfName3 = "interface3"
    val mIfPhysicalIndex1 = 10
    val mIfPhysicalIndex2 = 11
    val mIfPhysicalIndex3 = 12
    val mSourceAddress = Inet6Address.getByName("2000::8888") as Inet6Address
    val mGroupAddressScope5 = Inet6Address.getByName("ff05::1234") as Inet6Address
    val mGroupAddressScope4 = Inet6Address.getByName("ff04::1234") as Inet6Address
    val mGroupAddressScope3 = Inet6Address.getByName("ff03::1234") as Inet6Address
    val mEmptyOifs = setOf<Int>()
    val mClock = FakeClock()
    // RoutingCoordinatorService needs to be initialized after the dependencies
    // are mocked.
    lateinit var mService: RoutingCoordinatorService
    lateinit var mLooper: TestLooper

    class FakeClock() : Clock() {
        private var offsetMs = 0L

        fun fastForward(ms: Long) {
            offsetMs += ms
        }

        override fun instant(): Instant {
            return Instant.now().plusMillis(offsetMs)
        }

        override fun getZone(): ZoneId {
            throw RuntimeException("Not implemented");
        }

        override fun withZone(zone: ZoneId): Clock {
            throw RuntimeException("Not implemented");
        }

    }

    // Functions under @Before and @Test run in different threads,
    // (i.e. androidx.test.runner.AndroidJUnitRunner vs Time-limited test)
    // RoutingCoordinatorService requires the jobs are run on the thread looper,
    // so TestLooper needs to be created inside each test case to install the
    // correct looper.
    fun setUp() {
        mLooper = TestLooper()
        val handler = Handler(mLooper.getLooper())

        doReturn(mFd).`when`(mDeps).createMulticastRoutingSocket()
        doReturn(mClock).`when`(mDeps).getClock()
        doReturn(handler).`when`(mDeps).getHandler()
        doReturn(mNetd).`when`(mDeps).getNetd()
        doReturn(mIfPhysicalIndex1).`when`(mDeps).getInterfaceIndex(mIfName1)
        doReturn(mIfPhysicalIndex2).`when`(mDeps).getInterfaceIndex(mIfName2)
        doReturn(mIfPhysicalIndex3).`when`(mDeps).getInterfaceIndex(mIfName3)

        mService = RoutingCoordinatorService(mDeps)
    }

    fun setUpMulticastRoutingNotSupported() {
        mLooper = TestLooper()
        val handler = Handler(mLooper.getLooper())

        doReturn(null).`when`(mDeps).createMulticastRoutingSocket()
        doReturn(mClock).`when`(mDeps).getClock()
        doReturn(handler).`when`(mDeps).getHandler()
        doReturn(mNetd).`when`(mDeps).getNetd()
        doReturn(mIfPhysicalIndex1).`when`(mDeps).getInterfaceIndex(mIfName1)
        doReturn(mIfPhysicalIndex2).`when`(mDeps).getInterfaceIndex(mIfName2)
        doReturn(mIfPhysicalIndex3).`when`(mDeps).getInterfaceIndex(mIfName3)

        mService = RoutingCoordinatorService(mDeps)
    }

    @After
    fun tearDown() {
        mService.removeInterfaceFromMulticastRouting(mIfName1)
        mService.removeInterfaceFromMulticastRouting(mIfName2)
        mService.removeInterfaceFromMulticastRouting(mIfName3)
        mSock.close()
    }

    // Send a MRT6MSG_NOCACHE packet to sock, to indicate a packet has arrived without matching MulticastRoutingCache
    private fun sendMrt6msgNocachePacket(interfaceVirtualIndex: Int,
            source: Inet6Address, destination: Inet6Address) {
        mLooper.dispatchAll() // let routingCoordinatorService handle all msgs first to apply any
                              // possible multicast routing config changes
        val mrt6Msg = StructMrt6Msg(0 /* mbz must be 0 */, StructMrt6Msg.MRT6MSG_NOCACHE,
                interfaceVirtualIndex, source, destination)
        mLooper.getNewExecutor().execute({ mService.handleMulticastNocacheUpcall(mrt6Msg) })
        mLooper.dispatchAll()
    }

    private fun applyMulticastForwardNone(fromIf: String, toIf: String) {
        val configNone = MulticastRoutingConfig.CONFIG_FORWARD_NONE

        mService.applyMulticastRoutingConfig(fromIf, toIf, configNone)
    }

    private fun applyMulticastForwardMinimumScope(fromIf: String, toIf: String, minScope: Int) {
        val configMinimumScope = MulticastRoutingConfig.Builder(
            MulticastRoutingConfig.FORWARD_WITH_MIN_SCOPE, minScope).build()

        mService.applyMulticastRoutingConfig(fromIf, toIf, configMinimumScope)
    }

    private fun applyMulticastForwardSelected(fromIf: String, toIf: String) {
        val configSelected = MulticastRoutingConfig.Builder(
            MulticastRoutingConfig.FORWARD_SELECTED)
            .addListeningAddress(mGroupAddressScope5).build()

        mService.applyMulticastRoutingConfig(fromIf, toIf, configSelected)
    }

    @Test
    fun testInterfaceForward() {
        setUp()
        val inOrder = inOrder(mNetd)

        mService.addInterfaceForward("from1", "to1")
        inOrder.verify(mNetd).ipfwdEnableForwarding(any())
        inOrder.verify(mNetd).tetherAddForward("from1", "to1")
        inOrder.verify(mNetd).ipfwdAddInterfaceForward("from1", "to1")

        mService.addInterfaceForward("from2", "to1")
        inOrder.verify(mNetd).tetherAddForward("from2", "to1")
        inOrder.verify(mNetd).ipfwdAddInterfaceForward("from2", "to1")

        val hasFailed = AtomicBoolean(false)
        val prevHandler = Log.setWtfHandler { tag, what, system ->
            hasFailed.set(true)
        }
        tryTest {
            mService.addInterfaceForward("from2", "to1")
            assertTrue(hasFailed.get())
        } cleanup {
            Log.setWtfHandler(prevHandler)
        }

        mService.removeInterfaceForward("from1", "to1")
        inOrder.verify(mNetd).ipfwdRemoveInterfaceForward("from1", "to1")
        inOrder.verify(mNetd).tetherRemoveForward("from1", "to1")

        mService.removeInterfaceForward("from2", "to1")
        inOrder.verify(mNetd).ipfwdRemoveInterfaceForward("from2", "to1")
        inOrder.verify(mNetd).tetherRemoveForward("from2", "to1")

        inOrder.verify(mNetd).ipfwdDisableForwarding(any())
    }

    @Test
    fun testConstructor_multicastRoutingSocketIsCreated() {
        setUp()
        verify(mDeps).createMulticastRoutingSocket()
    }

    @Test
    fun testMulticastRouting_NotSupported_noConfigApplied() {
        setUpMulticastRoutingNotSupported()

        applyMulticastForwardMinimumScope(mIfName1, mIfName2, 4 /* minScope */)
        mLooper.dispatchAll()

        assertEquals(MulticastRoutingConfig.FORWARD_NONE,
                mService.getMulticastRoutingConfig(mIfName1, mIfName2).getForwardingMode())
    }

    @Test
    fun testMulticastRouting_applyForwardNone() {
        setUp()

        applyMulticastForwardNone(mIfName1, mIfName2)
        mLooper.dispatchAll()

        // Both interfaces are not added as multicast routing interfaces
        verify(mDeps, never()).addMulticastInterface(eq(mFd), anyInt(), anyInt())
        // No MFC should be added for FORWARD_NONE
        verify(mDeps, never()).addMulticastForwardingCache(eq(mFd), anyInt(), any(), any(), any())
        assertEquals(MulticastRoutingConfig.CONFIG_FORWARD_NONE,
                mService.getMulticastRoutingConfig(mIfName1, mIfName2));
    }

    @Test
    fun testMulticastRouting_applyForwardMinimumScope() {
        setUp()

        applyMulticastForwardMinimumScope(mIfName1, mIfName2, 4 /* minScope */)
        mLooper.dispatchAll()

        // No MFC is added for FORWARD_WITH_MIN_SCOPE
        verify(mDeps, never()).addMulticastForwardingCache(eq(mFd), anyInt(), any(), any(), any())
        assertEquals(MulticastRoutingConfig.FORWARD_WITH_MIN_SCOPE,
                mService.getMulticastRoutingConfig(mIfName1, mIfName2).getForwardingMode())
        assertEquals(4, mService.getMulticastRoutingConfig(mIfName1, mIfName2).getMinimumScope())
    }

    @Test
    fun testMulticastRouting_addressScopelargerThanMinScope_allowMfcIsAdded() {
        setUp()
        applyMulticastForwardMinimumScope(mIfName1, mIfName2, 4 /* minScope */)
        mLooper.dispatchAll()
        val oifs = setOf(mService.getVirtualInterfaceIndex(mIfName2))

        // simulate a MRT6MSG_NOCACHE upcall for a packet sent to group address of scope 5
        sendMrt6msgNocachePacket(0, mSourceAddress, mGroupAddressScope5)

        // an MFC is added for the packet
        verify(mDeps).addMulticastForwardingCache(eq(mFd), anyInt(), eq(oifs), any(), any())
    }

    @Test
    fun testMulticastRouting_addressScopeSmallerThanMinScope_blockingMfcIsAdded() {
        setUp()
        applyMulticastForwardMinimumScope(mIfName1, mIfName2, 4)

        // simulate a MRT6MSG_NOCACHE upcall when a packet should not be forwarded
        sendMrt6msgNocachePacket(0, mSourceAddress, mGroupAddressScope3)

        // a blocking MFC is added
        verify(mDeps).addMulticastForwardingCache(eq(mFd), anyInt(), eq(mEmptyOifs), any(), any())
    }

    @Test
    fun testMulticastRouting_applyForwardSelected_joinsGroup() {
        setUp()

        applyMulticastForwardSelected(mIfName1, mIfName2)
        mLooper.dispatchAll()

        verify(mDeps).joinGroup(eq(mIfPhysicalIndex1), any())
        assertEquals(MulticastRoutingConfig.FORWARD_SELECTED,
                mService.getMulticastRoutingConfig(mIfName1, mIfName2).getForwardingMode())
    }

    @Test
    fun testMulticastRouting_addListeningAddressInForwardSelected_joinsGroup() {
        setUp()

        val configSelectedNoAddress = MulticastRoutingConfig.Builder(
            MulticastRoutingConfig.FORWARD_SELECTED).build()
        mService.applyMulticastRoutingConfig(mIfName1, mIfName2, configSelectedNoAddress)
        mLooper.dispatchAll()

        val configSelectedWithAddresses = MulticastRoutingConfig.Builder(
            MulticastRoutingConfig.FORWARD_SELECTED)
            .addListeningAddress(mGroupAddressScope5)
            .addListeningAddress(mGroupAddressScope4)
            .build()
        mService.applyMulticastRoutingConfig(mIfName1, mIfName2, configSelectedWithAddresses)
        mLooper.dispatchAll()

        verify(mDeps).joinGroup(eq(mIfPhysicalIndex1), eq(mGroupAddressScope5))
        verify(mDeps).joinGroup(eq(mIfPhysicalIndex1), eq(mGroupAddressScope4))
    }

    @Test
    fun testMulticastRouting_removeListeningAddressInForwardSelected_leavesGroup() {
        setUp()
        val configSelectedWith2Addresses = MulticastRoutingConfig.Builder(
            MulticastRoutingConfig.FORWARD_SELECTED)
            .addListeningAddress(mGroupAddressScope5)
            .addListeningAddress(mGroupAddressScope4)
            .build()
        mService.applyMulticastRoutingConfig(mIfName1, mIfName2, configSelectedWith2Addresses)
        mLooper.dispatchAll()

        verify(mDeps).joinGroup(eq(mIfPhysicalIndex1), eq(mGroupAddressScope5))
        verify(mDeps).joinGroup(eq(mIfPhysicalIndex1), eq(mGroupAddressScope4))

        // remove the scope4 address
        val configSelectedWith1Address = MulticastRoutingConfig.Builder(
            MulticastRoutingConfig.FORWARD_SELECTED)
            .addListeningAddress(mGroupAddressScope5)
            .build()
        mService.applyMulticastRoutingConfig(mIfName1, mIfName2, configSelectedWith1Address)
        mLooper.dispatchAll()

        verify(mDeps).leaveGroup(eq(mIfPhysicalIndex1), eq(mGroupAddressScope4))
        verify(mDeps, never()).leaveGroup(eq(mIfPhysicalIndex1), eq(mGroupAddressScope5))
    }

    @Test
    fun testMulticastRouting_fromForwardSelectedToForwardNone_leavesGroup() {
        setUp()
        applyMulticastForwardSelected(mIfName1, mIfName2)
        mLooper.dispatchAll()

        verify(mDeps).joinGroup(eq(mIfPhysicalIndex1), any())

        applyMulticastForwardNone(mIfName1, mIfName2)
        mLooper.dispatchAll()

        verify(mDeps).leaveGroup(eq(mIfPhysicalIndex1), any())
        assertEquals(MulticastRoutingConfig.CONFIG_FORWARD_NONE,
                mService.getMulticastRoutingConfig(mIfName1, mIfName2));
    }

    @Test
    fun testMulticastRouting_fromFowardSelectedToForwardNone_removesMulticastInterfaces() {
        setUp()

        applyMulticastForwardSelected(mIfName1, mIfName2)
        applyMulticastForwardSelected(mIfName1, mIfName3)
        mLooper.dispatchAll()

        assertNotNull(mService.getVirtualInterfaceIndex(mIfName1))
        assertNotNull(mService.getVirtualInterfaceIndex(mIfName2))
        assertNotNull(mService.getVirtualInterfaceIndex(mIfName3))

        applyMulticastForwardNone(mIfName1, mIfName2)
        mLooper.dispatchAll()

        assertNotNull(mService.getVirtualInterfaceIndex(mIfName1))
        assertNull(mService.getVirtualInterfaceIndex(mIfName2))
        assertNotNull(mService.getVirtualInterfaceIndex(mIfName3))
    }

    @Test
    fun testMulticastRouting_addMulticastRoutingInterfaces() {
        setUp()

        applyMulticastForwardSelected(mIfName1, mIfName2)
        mLooper.dispatchAll()

        assertNotNull(mService.getVirtualInterfaceIndex(mIfName1))
        assertNotNull(mService.getVirtualInterfaceIndex(mIfName2))
        assertNotEquals(mService.getVirtualInterfaceIndex(mIfName1),
                mService.getVirtualInterfaceIndex(mIfName2))
    }

    @Test
    fun testMulticastRouting_removeMulticastRoutingInterfaces() {
        setUp()

        applyMulticastForwardSelected(mIfName1, mIfName2)
        mService.removeInterfaceFromMulticastRouting(mIfName1)
        mLooper.dispatchAll()

        assertNull(mService.getVirtualInterfaceIndex(mIfName1))
        assertNotNull(mService.getVirtualInterfaceIndex(mIfName2))
    }

    @Test
    fun testMulticastRouting_removeMulticastRoutingInterface_removesMfc() {
        setUp()

        applyMulticastForwardMinimumScope(mIfName1, mIfName2, 4 /* minScope */)
        sendMrt6msgNocachePacket(0, mSourceAddress, mGroupAddressScope5)

        verify(mDeps).addMulticastForwardingCache(eq(mFd),
            anyInt(), any(), any(), any())

        mService.removeInterfaceFromMulticastRouting(mIfName1)
        mLooper.dispatchAll()

        verify(mDeps, timeout(TIMEOUT_MS).times(1)).removeMulticastForwardingCache(eq(mFd),
            anyInt(), any(), any())
    }

    @Test
    @LargeTest
    fun testMulticastRouting_maxNumberOfMfcs() {
        setUp()

        // add MFC_MAX_NUMBER_OF_ENTRIES MFCs
        applyMulticastForwardMinimumScope(mIfName1, mIfName2, 4 /* minScope */)
        for (i in 1..RoutingCoordinatorService.MFC_MAX_NUMBER_OF_ENTRIES) {
            val groupAddress =
                Inet6Address.getByName("ff05::" + Integer.toHexString(i)) as Inet6Address
            sendMrt6msgNocachePacket(0, mSourceAddress, groupAddress)
        }

        verify(mDeps, times(RoutingCoordinatorService.MFC_MAX_NUMBER_OF_ENTRIES)).
            addMulticastForwardingCache(eq(mFd), anyInt(), any(), any(), any())
        // when number of mfcs reaches the max value, one mfc should be removed
        verify(mDeps).removeMulticastForwardingCache(eq(mFd),
            anyInt(), any(), any())
    }

    @Test
    fun testMulticastRouting_interfaceWithoutActiveConfig_isRemoved() {
        setUp()
        applyMulticastForwardMinimumScope(mIfName1, mIfName2, 4 /* minScope */)
        mLooper.dispatchAll()
        val virtualIndexIf1 = mService.getVirtualInterfaceIndex(mIfName1)
        val virtualIndexIf2 = mService.getVirtualInterfaceIndex(mIfName2)

        applyMulticastForwardNone(mIfName1, mIfName2)
        mLooper.dispatchAll()

        verify(mDeps).removeMulticastInterface(eq(mFd), eq(virtualIndexIf1))
        verify(mDeps).removeMulticastInterface(eq(mFd), eq(virtualIndexIf2))
    }

    @Test
    fun testMulticastRouting_interfaceWithActiveConfig_isNotRemoved() {
        setUp()
        applyMulticastForwardMinimumScope(mIfName1, mIfName2, 4 /* minScope */)
        applyMulticastForwardMinimumScope(mIfName2, mIfName3, 4 /* minScope */)
        mLooper.dispatchAll()
        val virtualIndexIf1 = mService.getVirtualInterfaceIndex(mIfName1)
        val virtualIndexIf2 = mService.getVirtualInterfaceIndex(mIfName2)
        val virtualIndexIf3 = mService.getVirtualInterfaceIndex(mIfName3)

        applyMulticastForwardNone(mIfName1, mIfName2)
        mLooper.dispatchAll()

        verify(mDeps).removeMulticastInterface(eq(mFd), eq(virtualIndexIf1))
        verify(mDeps, never()).removeMulticastInterface(eq(mFd), eq(virtualIndexIf2))
        verify(mDeps, never()).removeMulticastInterface(eq(mFd), eq(virtualIndexIf3))
    }

    @Test
    fun testMulticastRouting_unusedMfc_isRemovedAfterTimeout() {
        setUp()
        applyMulticastForwardMinimumScope(mIfName1, mIfName2, 4 /* minScope */)
        sendMrt6msgNocachePacket(0, mSourceAddress, mGroupAddressScope5)

        // An MFC is added
        verify(mDeps).addMulticastForwardingCache(eq(mFd),
            eq(0), any(), any(), any())

        repeat(RoutingCoordinatorService.MFC_INACTIVE_TIMEOUT_MS /
                RoutingCoordinatorService.MFC_INACTIVE_CHECK_INTERVAL_MS + 1) {
            mClock.fastForward(RoutingCoordinatorService.MFC_INACTIVE_CHECK_INTERVAL_MS.toLong())
            mLooper.moveTimeForward(RoutingCoordinatorService.MFC_INACTIVE_CHECK_INTERVAL_MS
                                    .toLong())
            mLooper.dispatchAll();
        }

        verify(mDeps).removeMulticastForwardingCache(eq(mFd),
            eq(0), any(), any())
    }
}
