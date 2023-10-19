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
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants.AF_INET6
import android.system.OsConstants.IPPROTO_UDP
import android.system.OsConstants.SOCK_DGRAM
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.google.common.truth.Truth.assertThat
import java.io.FileDescriptor
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Inet6Address
import java.net.InetSocketAddress
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val TIMEOUT_MS = 2_000L
private const val LONG_TIMEOUT_MS = 20_000L

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
    val mIfPhysicalIndex1 = 10
    val mIfPhysicalIndex2 = 11
    val mSourceAddress = Inet6Address.getByName("2000::8888") as Inet6Address
    val mGroupAddressScope5 = Inet6Address.getByName("ff05::1234") as Inet6Address
    val mGroupAddressScope3 = Inet6Address.getByName("ff03::1234") as Inet6Address
    val mEmptyOifs = listOf<Int>()
    // RoutingCoordinatorService needs to be initialized after the dependencies
    // are mocked.
    lateinit var mService: RoutingCoordinatorService

    @Before
    fun setUp() {
        doReturn(mFd).`when`(mDeps).createMulticastRoutingSocket()
        doReturn(mIfPhysicalIndex1).`when`(mDeps).getInterfaceIndex(mIfName1)
        doReturn(mIfPhysicalIndex2).`when`(mDeps).getInterfaceIndex(mIfName2)

        mService = RoutingCoordinatorService(mNetd, mDeps)
    }

    @After
    fun tearDown() {
        mService.removeInterfaceFromMulticastRouting(mIfName1)
        mService.removeInterfaceFromMulticastRouting(mIfName2)
        mSock.close()
    }

    // Send a MRT6MSG_NOCACHE packet to sock, to indicate a packet has arrived without matching MulticastRoutingCache
    private fun sendPacket(interfaceVirtualIndex: Int, source: Inet6Address, destination: Inet6Address) {
        val mrt6Msg = StructMrt6Msg(0 /* mbz must be 0 */, StructMrt6Msg.MRT6MSG_NOCACHE,
                interfaceVirtualIndex, 0 /* padding */, source, destination)
        val bytes = mrt6Msg.writeToBytes()
        val sender = DatagramSocket()
        val socketAddr = mSock.getLocalSocketAddress()
        sender.connect(socketAddr)
        val mrt6Packet = DatagramPacket(bytes, bytes.size)
        sender.send(mrt6Packet)
        sender.close()
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
        val inOrder = inOrder(mNetd)

        mService.addInterfaceForward("from1", "to1")
        inOrder.verify(mNetd).ipfwdEnableForwarding(any())
        inOrder.verify(mNetd).tetherAddForward("from1", "to1")
        inOrder.verify(mNetd).ipfwdAddInterfaceForward("from1", "to1")

        mService.addInterfaceForward("from2", "to1")
        inOrder.verify(mNetd).tetherAddForward("from2", "to1")
        inOrder.verify(mNetd).ipfwdAddInterfaceForward("from2", "to1")

        assertFailsWith<IllegalStateException> {
            // Can't add the same pair again
            mService.addInterfaceForward("from2", "to1")
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
        verify(mDeps).createMulticastRoutingSocket()
    }

    @Test
    fun testMulticastRouting_applyForwardNone() {
        applyMulticastForwardNone(mIfName1, mIfName2)

        // Both interfaces are added as multicast routing interfaces
        verify(mDeps, timeout(TIMEOUT_MS).times(2)).
                addMulticastInterface(eq(mFd), anyInt(), anyInt())

        // No MFC should be added for FORWARD_NONE
        verify(mDeps, never()).addMulticastForwardingCache(eq(mFd), anyInt(), any(), any(), any())
    }

    @Test
    fun testMulticastRouting_applyForwardMinimumScope() {
        applyMulticastForwardMinimumScope(mIfName1, mIfName2, 4)

        // No MFC is added for FORWARD_WITH_MIN_SCOPE
        verify(mDeps, never()).addMulticastForwardingCache(eq(mFd), anyInt(), any(), any(), any())
    }

    @Test
    fun testMulticastRouting_applyForwardMinimumScope_forwardPacket() {
        applyMulticastForwardMinimumScope(mIfName1, mIfName2, 4)
        SystemClock.sleep(50 /* ms */)
        val oifs = listOf(mService.getVirtualInterfaceIndex(mIfName2))
        // simulate a MRT6MSG_NOCACHE upcall when a packet should be forwarded
        sendPacket(0, mSourceAddress, mGroupAddressScope5)
        // an MFC is added for the packet
        verify(mDeps, timeout(TIMEOUT_MS).times(1)).addMulticastForwardingCache(eq(mFd),
            anyInt(), eq(oifs), any(), any())
    }

    @Test
    fun testMulticastRouting_applyForwardMinimumScope_doNotForwardPacket() {
        applyMulticastForwardMinimumScope(mIfName1, mIfName2, 4)

        // simulate a MRT6MSG_NOCACHE upcall when a packet should not be forwarded
        sendPacket(0, mSourceAddress, mGroupAddressScope3)
        // a blocking MFC is added
        verify(mDeps, timeout(TIMEOUT_MS).times(1)).addMulticastForwardingCache(eq(mFd),
            anyInt(), eq(mEmptyOifs), any(), any())
    }

    @Test
    fun testMulticastRouting_applyForwardSelected() {
        applyMulticastForwardSelected(mIfName1, mIfName2)

        verify(mDeps, timeout(TIMEOUT_MS).times(1)).joinGroup(any(), eq(mIfPhysicalIndex1), any())
    }

    @Test
    fun testMulticastRouting_fromForwardSelectedToForwardNone() {
        applyMulticastForwardSelected(mIfName1, mIfName2)

        verify(mDeps, timeout(TIMEOUT_MS).times(1)).joinGroup(any(), eq(mIfPhysicalIndex1), any())

        applyMulticastForwardNone(mIfName1, mIfName2)

        verify(mDeps, timeout(TIMEOUT_MS).times(1)).leaveGroup(any(), eq(mIfPhysicalIndex1), any())
    }

    @Test
    fun testMulticastRouting_addMulticastRoutingInterfaces() {
        applyMulticastForwardSelected(mIfName1, mIfName2)

        SystemClock.sleep(50 /* ms */)
        assertNotNull(mService.getVirtualInterfaceIndex(mIfName1))
        assertNotNull(mService.getVirtualInterfaceIndex(mIfName2))
        assertNotEquals(mService.getVirtualInterfaceIndex(mIfName1),
                mService.getVirtualInterfaceIndex(mIfName2))
    }

    @Test
    fun testMulticastRouting_removeMulticastRoutingInterfaces() {
        applyMulticastForwardSelected(mIfName1, mIfName2)
        SystemClock.sleep(50 /* ms */)
        mService.removeInterfaceFromMulticastRouting(mIfName1)
        SystemClock.sleep(50 /* ms */)
        assertNull(mService.getVirtualInterfaceIndex(mIfName1))
        assertNotNull(mService.getVirtualInterfaceIndex(mIfName2))
    }

    @Test
    fun testMulticastRouting_removeMulticastRoutingInterface_removesMfc() {
        applyMulticastForwardMinimumScope(mIfName1, mIfName2, 4)
        sendPacket(0, mSourceAddress, mGroupAddressScope5)
        verify(mDeps, timeout(TIMEOUT_MS).times(1)).addMulticastForwardingCache(eq(mFd),
            anyInt(), any(), any(), any())
        mService.removeInterfaceFromMulticastRouting(mIfName1)
        verify(mDeps, timeout(TIMEOUT_MS).times(1)).removeMulticastForwardingCache(eq(mFd),
            anyInt(), any(), any())
    }

    @Test
    fun testMulticastRouting_maxNumberOfMfcs() {
        // add MFC_MAX_NUMBER_OF_ENTRIES MFCs
        applyMulticastForwardMinimumScope(mIfName1, mIfName2, 4)
        for (i in 1..mService.MFC_MAX_NUMBER_OF_ENTRIES) {
            val groupAddress =
                Inet6Address.getByName("ff05::" + Integer.toHexString(i)) as Inet6Address
            sendPacket(0, mSourceAddress, groupAddress)
        }
        verify(mDeps, timeout(LONG_TIMEOUT_MS).times(mService.MFC_MAX_NUMBER_OF_ENTRIES)).
            addMulticastForwardingCache(eq(mFd), anyInt(), any(), any(), any())
        // when number of mfcs reaches the max value, one mfc should be removed
        verify(mDeps, timeout(TIMEOUT_MS).times(1)).removeMulticastForwardingCache(eq(mFd),
            anyInt(), any(), any())
    }
}
