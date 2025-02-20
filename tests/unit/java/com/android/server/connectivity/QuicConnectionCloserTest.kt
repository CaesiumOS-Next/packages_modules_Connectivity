/*
 * Copyright (C) 2025 The Android Open Source Project
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

import android.net.InetAddresses
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.TEST_IFACE
import android.util.SparseArray
import com.android.net.module.util.netlink.InetDiagMessage
import com.android.net.module.util.netlink.StructInetDiagSockId
import com.android.net.module.util.netlink.StructNlMsgHdr
import com.android.server.connectivity.QuicConnectionCloser.QuicConnectionCloseInfo
import java.net.InetSocketAddress
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.InOrder
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never

private const val TEST_UID = 1234
private const val TEST_NETID = 789
private const val TEST_SOCKET_COOKIE = 12321L
private const val TEST_IFINDEX = 12

private val TEST_SRC_ADDRESS = InetAddresses.parseNumericAddress("2001:db8:1:2::2")
private val TEST_SRC_SOCKET_ADDRESS = InetSocketAddress(
        TEST_SRC_ADDRESS,
        1234
)
private val TEST_DST_SOCKET_ADDRESS = InetSocketAddress(
        InetAddresses.parseNumericAddress("2001:db8:1:2::3"),
        443
)
private val TEST_LP = LinkProperties().apply {
    interfaceName = TEST_IFACE
    addLinkAddress(LinkAddress(TEST_SRC_ADDRESS, 64))
}
private val TEST_NETWORK = Network(TEST_NETID)
private val TEST_PAYLOAD = byteArrayOf(0, 1, 2, 3, 4, 5)

private fun quicConnectionCloseInfo() = QuicConnectionCloseInfo(
        TEST_UID,
        TEST_NETID,
        TEST_SOCKET_COOKIE,
        TEST_SRC_SOCKET_ADDRESS,
        TEST_DST_SOCKET_ADDRESS,
        TEST_PAYLOAD
)

class QuicConnectionCloserTest {
    private val mDeps = mock(QuicConnectionCloser.Dependencies::class.java).also {
        doReturn(TEST_IFINDEX).`when`(it).ifNameToIndex(anyString())
    }

    private val mQuicConnectionCloser = QuicConnectionCloser(mDeps)

    private val nai = mock(NetworkAgentInfo::class.java).also {
        it.linkProperties = TEST_LP
        doReturn(TEST_NETWORK).`when`(it).network()
    }

    private fun netIdToNet() = SparseArray<NetworkAgentInfo>().apply {
        put(TEST_NETID, nai)
    }

    private fun expectDestroyUdpSocket(inOrder: InOrder) {
        inOrder.verify(mDeps).destroyUdpSocket(
                TEST_SRC_SOCKET_ADDRESS,
                TEST_DST_SOCKET_ADDRESS,
                TEST_IFINDEX,
                TEST_SOCKET_COOKIE
        )
    }

    private fun assertNoDestroyUdpSocket(inOrder: InOrder) {
        inOrder.verify(mDeps, never()).destroyUdpSocket(
                any(),
                any(),
                anyInt(),
                anyLong()
        )
    }

    private fun expectSendQuicConnectionClosePayload(inOrder: InOrder) {
        inOrder.verify(mDeps).sendQuicConnectionClosePayload(
                TEST_NETWORK,
                TEST_SRC_SOCKET_ADDRESS,
                TEST_DST_SOCKET_ADDRESS,
                TEST_PAYLOAD
        )
    }

    private fun assertNoSendQuicConnectionClosePayload(inOrder: InOrder) {
        inOrder.verify(mDeps, never()).sendQuicConnectionClosePayload(
                any(),
                any(),
                any(),
                any()
        )
    }

    @Test
    fun testCloseQuicConnectionByUids() {
        mQuicConnectionCloser.handleRegisterQuicConnectionCloseInfo(quicConnectionCloseInfo())
        mQuicConnectionCloser.closeQuicConnectionByUids(setOf(TEST_UID), netIdToNet())

        val inOrder = inOrder(mDeps)
        expectDestroyUdpSocket(inOrder)
        expectSendQuicConnectionClosePayload(inOrder)
    }

    @Test
    fun testCloseQuicConnectionByUids_unregisterQuicConnectionCloseInfo() {
        mQuicConnectionCloser.handleRegisterQuicConnectionCloseInfo(quicConnectionCloseInfo())
        mQuicConnectionCloser.handleUnregisterQuicConnectionCloseInfo(TEST_SOCKET_COOKIE)
        mQuicConnectionCloser.closeQuicConnectionByUids(setOf(TEST_UID), netIdToNet())

        val inOrder = inOrder(mDeps)
        assertNoDestroyUdpSocket(inOrder)
        assertNoSendQuicConnectionClosePayload(inOrder)
    }

    @Test
    fun testCloseQuicConnectionByUids_networkDisconnected() {
        mQuicConnectionCloser.handleRegisterQuicConnectionCloseInfo(quicConnectionCloseInfo())
        // closeQuicConnectionByUids knows the network is disconnected since it's not in this set of
        // networkForNetId.
        mQuicConnectionCloser.closeQuicConnectionByUids(
            setOf(TEST_UID),
            SparseArray() /* networkForNetId */
        )

        val inOrder = inOrder(mDeps)
        assertNoDestroyUdpSocket(inOrder)
        assertNoSendQuicConnectionClosePayload(inOrder)
    }

    @Test
    fun testCloseQuicConnectionByUids_networkAddressChange() {
        mQuicConnectionCloser.handleRegisterQuicConnectionCloseInfo(quicConnectionCloseInfo())
        // Update address to different address from TEST_SRC_SOCKET_ADDRESS
        nai.linkProperties = LinkProperties().apply {
            interfaceName = TEST_IFACE
            addLinkAddress(LinkAddress(InetAddresses.parseNumericAddress("2001:db8:1:3::2"), 64))
        }
        mQuicConnectionCloser.closeQuicConnectionByUids(setOf(TEST_UID), netIdToNet())

        val inOrder = inOrder(mDeps)
        assertNoDestroyUdpSocket(inOrder)
        assertNoSendQuicConnectionClosePayload(inOrder)
    }

    @Test
    fun testHandleUdpSocketDestroy() {
        mQuicConnectionCloser.handleRegisterQuicConnectionCloseInfo(quicConnectionCloseInfo())

        val inetDiagMessage = InetDiagMessage(StructNlMsgHdr())
        inetDiagMessage.inetDiagMsg.id = StructInetDiagSockId(TEST_SRC_SOCKET_ADDRESS,
                TEST_DST_SOCKET_ADDRESS, TEST_IFINDEX, TEST_SOCKET_COOKIE)
        mQuicConnectionCloser.handleUdpSocketDestroy(inetDiagMessage, netIdToNet())

        val inOrder = inOrder(mDeps)
        // DestroyUdpSocket is not called since the socket is already closed
        assertNoDestroyUdpSocket(inOrder)
        expectSendQuicConnectionClosePayload(inOrder)
    }

    @Test
    fun testHandleUdpSocketDestroy_unregisterBeforeSocketClose() {
        mQuicConnectionCloser.handleRegisterQuicConnectionCloseInfo(quicConnectionCloseInfo())
        mQuicConnectionCloser.handleUnregisterQuicConnectionCloseInfo(TEST_SOCKET_COOKIE)

        val inetDiagMessage = InetDiagMessage(StructNlMsgHdr())
        inetDiagMessage.inetDiagMsg.id = StructInetDiagSockId(TEST_SRC_SOCKET_ADDRESS,
                TEST_DST_SOCKET_ADDRESS, TEST_IFINDEX, TEST_SOCKET_COOKIE)
        mQuicConnectionCloser.handleUdpSocketDestroy(inetDiagMessage, netIdToNet())

        val inOrder = inOrder(mDeps)
        assertNoDestroyUdpSocket(inOrder)
        assertNoSendQuicConnectionClosePayload(inOrder)
    }
}
