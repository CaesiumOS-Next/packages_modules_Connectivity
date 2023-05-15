package com.android.server.connectivity.mdns.internal

import android.net.LinkAddress
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.system.OsConstants
import com.android.net.module.util.SharedLog
import com.android.net.module.util.netlink.NetlinkConstants
import com.android.net.module.util.netlink.RtNetlinkAddressMessage
import com.android.net.module.util.netlink.StructIfaddrMsg
import com.android.net.module.util.netlink.StructNlMsgHdr
import com.android.server.connectivity.mdns.MdnsAdvertiserTest
import com.android.server.connectivity.mdns.MdnsSocketProvider
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.mockito.Mockito.argThat
import org.mockito.Mockito.verify

private val LINKADDRV4 = LinkAddress("192.0.2.0/24")

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
internal class SocketNetlinkMonitorTest {
    private val thread = HandlerThread(MdnsAdvertiserTest::class.simpleName)
    private val sharedlog = Mockito.mock(SharedLog::class.java)
    private val netlinkMonitorCallBack =
            Mockito.mock(MdnsSocketProvider.NetLinkMonitorCallBack::class.java)

    @Before
    fun setUp() {
        thread.start()
    }

    @After
    fun tearDown() {
        thread.quitSafely()
    }

    @Test
    fun testHandleDeprecatedNetlinkMessage() {
        val socketNetlinkMonitor = SocketNetlinkMonitor(Handler(thread.looper), sharedlog,
                netlinkMonitorCallBack)
        val nlmsghdr = StructNlMsgHdr().apply {
            nlmsg_type = NetlinkConstants.RTM_DELADDR
            nlmsg_flags = (StructNlMsgHdr.NLM_F_REQUEST.toInt()
                    or StructNlMsgHdr.NLM_F_ACK.toInt()).toShort()
            nlmsg_seq = 1
        }
        val ifIndex = 32
        val family = OsConstants.AF_INET
        val structIfaddrMsg = StructIfaddrMsg(family.toShort(),
                LINKADDRV4.prefixLength.toShort(),
                (LINKADDRV4.flags or OsConstants.IFA_F_DEPRECATED).toShort(),
                LINKADDRV4.scope.toShort(), ifIndex)
        val netlinkMsg = RtNetlinkAddressMessage(nlmsghdr, structIfaddrMsg, LINKADDRV4.address,
                null /* structIfacacheInfo */, 0 /* flags */)
        socketNetlinkMonitor.processNetlinkMessage(netlinkMsg, 0L /* whenMs */)

        verify(netlinkMonitorCallBack).deleteInterfaceAddress(eq(ifIndex),
                argThat { it.address == LINKADDRV4.address })
    }
}
