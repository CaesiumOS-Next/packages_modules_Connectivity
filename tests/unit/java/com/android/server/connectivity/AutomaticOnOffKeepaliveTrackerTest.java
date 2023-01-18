/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.connectivity;

import static android.Manifest.permission.GET_INTENT_SENDER_INTENT;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.net.NattSocketKeepalive.NATT_PORT;
import static android.net.SocketKeepalive.SUCCESS;
import static android.provider.DeviceConfig.NAMESPACE_CONNECTIVITY;
import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.IPPROTO_UDP;
import static android.system.OsConstants.SOCK_DGRAM;

import static com.android.server.connectivity.AutomaticOnOffKeepaliveTracker.AUTOMATIC_ON_OFF_KEEPALIVE_MODE;
import static com.android.server.connectivity.AutomaticOnOffKeepaliveTracker.AUTOMATIC_ON_OFF_KEEPALIVE_MODE_ENABLED;
import static com.android.server.connectivity.AutomaticOnOffKeepaliveTracker.EXTRA_SLOT;
import static com.android.testutils.ContextUtils.mockService;
import static com.android.testutils.TestPermissionUtil.runAsShell;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.net.INetd;
import android.net.ISocketKeepaliveCallback;
import android.net.KeepalivePacketData;
import android.net.MarkMaskParcel;
import android.net.NattKeepalivePacketData;
import android.net.Network;
import android.net.NetworkAgent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.UserHandle;
import android.system.Os;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;
import com.android.testutils.HandlerUtils;

import libcore.util.HexEncoding;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.FileDescriptor;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@RunWith(DevSdkIgnoreRunner.class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
public class AutomaticOnOffKeepaliveTrackerTest {
    private static final int TEST_NETID = 0xA85;
    private static final int TEST_NETID_FWMARK = 0x0A85;
    private static final int OTHER_NETID = 0x1A85;
    private static final int UNDERLY_NETID = 0xB85;
    private static final int NETID_MASK = 0xffff;
    private static final String TEST_SRC_ADDR = "192.0.2.139";
    private static final UserInfo SYSTEM_USER = new UserInfo(0, "system", UserInfo.FLAG_PRIMARY);
    private static final int HANDLER_TIMEOUT_MS = 1000;
    private AutomaticOnOffKeepaliveTracker mAOOKeepaliveTracker;
    private Handler mHandler;

    @Mock INetd mNetd;
    @Mock AutomaticOnOffKeepaliveTracker.Dependencies mDependencies;
    @Mock Context mCtx;
    @Mock KeepaliveTracker mKeepaliveTracker;
    @Mock PackageManager mPackageManager;
    @Mock AlarmManager mAlarmManager;
    @Mock NetworkAgentInfo mNai;
    @Mock ISocketKeepaliveCallback mKeepaliveCallback;
    @Mock IBinder mBinder;
    // Hexadecimal representation of a SOCK_DIAG response with tcp info.
    private static final String SOCK_DIAG_TCP_INET_HEX =
            // struct nlmsghdr.
            "14010000" +        // length = 276
            "1400" +            // type = SOCK_DIAG_BY_FAMILY
            "0301" +            // flags = NLM_F_REQUEST | NLM_F_DUMP
            "00000000" +        // seqno
            "00000000" +        // pid (0 == kernel)
            // struct inet_diag_req_v2
            "02" +              // family = AF_INET
            "06" +              // state
            "00" +              // timer
            "00" +              // retrans
            // inet_diag_sockid
            "DEA5" +            // idiag_sport = 42462
            "71B9" +            // idiag_dport = 47473
            "0a006402000000000000000000000000" + // idiag_src = 10.0.100.2
            "08080808000000000000000000000000" + // idiag_dst = 8.8.8.8
            "00000000" +            // idiag_if
            "34ED000076270000" +    // idiag_cookie = 43387759684916
            "00000000" +            // idiag_expires
            "00000000" +            // idiag_rqueue
            "00000000" +            // idiag_wqueue
            "00000000" +            // idiag_uid
            "00000000" +            // idiag_inode
            // rtattr
            "0500" +            // len = 5
            "0800" +            // type = 8
            "00000000" +        // data
            "0800" +            // len = 8
            "0F00" +            // type = 15(INET_DIAG_MARK)
            "850A0C00" +        // data, socket mark=789125
            "AC00" +            // len = 172
            "0200" +            // type = 2(INET_DIAG_INFO)
            // tcp_info
            "01" +              // state = TCP_ESTABLISHED
            "00" +              // ca_state = TCP_CA_OPEN
            "05" +              // retransmits = 5
            "00" +              // probes = 0
            "00" +              // backoff = 0
            "07" +              // option = TCPI_OPT_WSCALE|TCPI_OPT_SACK|TCPI_OPT_TIMESTAMPS
            "88" +              // wscale = 8
            "00" +              // delivery_rate_app_limited = 0
            "4A911B00" +        // rto = 1806666
            "00000000" +        // ato = 0
            "2E050000" +        // sndMss = 1326
            "18020000" +        // rcvMss = 536
            "00000000" +        // unsacked = 0
            "00000000" +        // acked = 0
            "00000000" +        // lost = 0
            "00000000" +        // retrans = 0
            "00000000" +        // fackets = 0
            "BB000000" +        // lastDataSent = 187
            "00000000" +        // lastAckSent = 0
            "BB000000" +        // lastDataRecv = 187
            "BB000000" +        // lastDataAckRecv = 187
            "DC050000" +        // pmtu = 1500
            "30560100" +        // rcvSsthresh = 87600
            "3E2C0900" +        // rttt = 601150
            "1F960400" +        // rttvar = 300575
            "78050000" +        // sndSsthresh = 1400
            "0A000000" +        // sndCwnd = 10
            "A8050000" +        // advmss = 1448
            "03000000" +        // reordering = 3
            "00000000" +        // rcvrtt = 0
            "30560100" +        // rcvspace = 87600
            "00000000" +        // totalRetrans = 0
            "53AC000000000000" +    // pacingRate = 44115
            "FFFFFFFFFFFFFFFF" +    // maxPacingRate = 18446744073709551615
            "0100000000000000" +    // bytesAcked = 1
            "0000000000000000" +    // bytesReceived = 0
            "0A000000" +        // SegsOut = 10
            "00000000" +        // SegsIn = 0
            "00000000" +        // NotSentBytes = 0
            "3E2C0900" +        // minRtt = 601150
            "00000000" +        // DataSegsIn = 0
            "00000000" +        // DataSegsOut = 0
            "0000000000000000"; // deliverRate = 0
    private static final String SOCK_DIAG_NO_TCP_INET_HEX =
            // struct nlmsghdr
            "14000000"     // length = 20
            + "0300"         // type = NLMSG_DONE
            + "0301"         // flags = NLM_F_REQUEST | NLM_F_DUMP
            + "00000000"     // seqno
            + "00000000"     // pid (0 == kernel)
            // struct inet_diag_req_v2
            + "02"           // family = AF_INET
            + "06"           // state
            + "00"           // timer
            + "00";          // retrans
    private static final byte[] SOCK_DIAG_NO_TCP_INET_BYTES =
            HexEncoding.decode(SOCK_DIAG_NO_TCP_INET_HEX.toCharArray(), false);
    private static final String TEST_RESPONSE_HEX =
            SOCK_DIAG_TCP_INET_HEX + SOCK_DIAG_NO_TCP_INET_HEX;
    private static final byte[] TEST_RESPONSE_BYTES =
            HexEncoding.decode(TEST_RESPONSE_HEX.toCharArray(), false);

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        mockService(mCtx, AlarmManager.class, Context.ALARM_SERVICE, mAlarmManager);
        doReturn(mNetd).when(mDependencies).getNetd();
        doReturn(makeMarkMaskParcel(NETID_MASK, TEST_NETID_FWMARK)).when(mNetd)
                .getFwmarkForNetwork(TEST_NETID);
        doNothing().when(mDependencies).sendRequest(any(), any());
        doNothing().when(mKeepaliveTracker).handleStartKeepalive(any());
        // For creating KeepaliveInfo.
        doReturn(mBinder).when(mKeepaliveCallback).asBinder();
        doNothing().when(mBinder).linkToDeath(any(), anyInt());
        doReturn(PERMISSION_GRANTED).when(mCtx).checkPermission(anyString(), anyInt(), anyInt());

        doReturn(AUTOMATIC_ON_OFF_KEEPALIVE_MODE_ENABLED).when(mDependencies)
                .getDeviceConfigPropertyInt(
                        eq(NAMESPACE_CONNECTIVITY), eq(AUTOMATIC_ON_OFF_KEEPALIVE_MODE), anyInt());

        // Mocking for pending intent.
        doReturn(Process.myProcessName()).when(mCtx).getPackageName();
        final UserHandle userHandle = UserHandle.of(SYSTEM_USER.id);
        doReturn(userHandle).when(mCtx).getUser();
        doReturn(UserHandle.getUid(SYSTEM_USER.id, Process.myUid()))
                .when(mPackageManager).getPackageUidAsUser(anyString(), anyInt());

        final HandlerThread handlerThread = new HandlerThread("KeepaliveTrackerTest");
        handlerThread.start();
        mHandler = handlerThread.getThreadHandler();
        doReturn(mKeepaliveTracker).when(mDependencies).newKeepaliveTracker(mCtx, mHandler);
        mAOOKeepaliveTracker = new AutomaticOnOffKeepaliveTracker(mCtx, mHandler, mDependencies);
    }

    @Test
    public void testIsAnyTcpSocketConnected_runOnNonHandlerThread() throws Exception {
        setupResponseWithSocketExisting();
        assertThrows(IllegalStateException.class,
                () -> mAOOKeepaliveTracker.isAnyTcpSocketConnected(TEST_NETID));
    }

    @Test
    public void testIsAnyTcpSocketConnected_withTargetNetId() throws Exception {
        setupResponseWithSocketExisting();
        mHandler.post(() -> assertTrue(mAOOKeepaliveTracker.isAnyTcpSocketConnected(TEST_NETID)));
    }

    @Test
    public void testIsAnyTcpSocketConnected_withIncorrectNetId() throws Exception {
        setupResponseWithSocketExisting();
        mHandler.post(() -> assertFalse(mAOOKeepaliveTracker.isAnyTcpSocketConnected(OTHER_NETID)));
    }

    @Test
    public void testIsAnyTcpSocketConnected_noSocketExists() throws Exception {
        setupResponseWithoutSocketExisting();
        mHandler.post(() -> assertFalse(mAOOKeepaliveTracker.isAnyTcpSocketConnected(TEST_NETID)));
    }

    private void setupResponseWithSocketExisting() throws Exception {
        final ByteBuffer tcpBufferV6 = getByteBuffer(TEST_RESPONSE_BYTES);
        final ByteBuffer tcpBufferV4 = getByteBuffer(TEST_RESPONSE_BYTES);
        doReturn(tcpBufferV6, tcpBufferV4).when(mDependencies).recvSockDiagResponse(any());
    }

    private void setupResponseWithoutSocketExisting() throws Exception {
        final ByteBuffer tcpBufferV6 = getByteBuffer(SOCK_DIAG_NO_TCP_INET_BYTES);
        final ByteBuffer tcpBufferV4 = getByteBuffer(SOCK_DIAG_NO_TCP_INET_BYTES);
        doReturn(tcpBufferV6, tcpBufferV4).when(mDependencies).recvSockDiagResponse(any());
    }

    private MarkMaskParcel makeMarkMaskParcel(final int mask, final int mark) {
        final MarkMaskParcel parcel = new MarkMaskParcel();
        parcel.mask = mask;
        parcel.mark = mark;
        return parcel;
    }

    private ByteBuffer getByteBuffer(final byte[] bytes) {
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.nativeOrder());
        return buffer;
    }

    private KeepaliveTracker.KeepaliveInfo buildTestKeepaliveInfo(int slot) throws Exception {
        final KeepalivePacketData packet = NattKeepalivePacketData.nattKeepalivePacket(
                InetAddress.getByName(TEST_SRC_ADDR), 1234 /* srcPort */,
                InetAddress.getByName("8.8.8.8"), NATT_PORT);
        final FileDescriptor fd = Os.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
        final KeepaliveTracker.KeepaliveInfo ki = mKeepaliveTracker.new KeepaliveInfo(mCtx,
                mKeepaliveCallback, mNai, packet, 2000 /* intervalSeconds */,
                KeepaliveTracker.KeepaliveInfo.TYPE_NATT, fd);
        doReturn(new Network(UNDERLY_NETID)).when(mNai).network();
        doReturn(ki).when(mKeepaliveTracker).createNewKeepaliveWithNewFd(any(), any());
        ki.mSlot = slot;
        return ki;
    }

    @Test
    public void testAutomaticOnOffKeepaliveEnabled() throws Exception {
        testAutomaticOnOffKeepalive(true, true);
    }

    @Test
    public void testAutomaticOnOffKeepaliveDisabled() throws Exception {
        testAutomaticOnOffKeepalive(false, true);
    }

    @Test
    public void testAutomaticOnOffKeepaliveEnabled_noVpnNetwork() throws Exception {
        testAutomaticOnOffKeepalive(true, false);
    }

    public void testAutomaticOnOffKeepalive(boolean automaticOnOff, boolean isAnyVpnNetwork)
            throws Exception {
        final int slot = 3;
        final KeepaliveTracker.KeepaliveInfo ki = buildTestKeepaliveInfo(slot);

        // Lower the polling interval so that the alarm will be fired soon.
        doReturn(100L).when(mDependencies).getTcpPollingInterval();

        // Start keepalive
        final Message msg = Message.obtain(mHandler,
                NetworkAgent.CMD_START_SOCKET_KEEPALIVE, automaticOnOff ? 1 : 0, 0 , ki);
        mHandler.post(()-> mAOOKeepaliveTracker.handleStartKeepalive(msg));
        HandlerUtils.waitForIdle(mHandler, HANDLER_TIMEOUT_MS);

        verify(mKeepaliveTracker).handleStartKeepalive(msg);
        // Verify alarm being received.
        if (automaticOnOff) {
            verify(mAlarmManager, timeout(1000)).setExact(
                    anyInt(), anyLong(), argThat(intent -> getSlot(intent) == ki.mSlot));

            // TCP sockets connected on the VPN network, so keepalive stays in started.
            reset(mAlarmManager);
            setupResponseWithSocketExisting();
            mHandler.post(() ->
                    mAOOKeepaliveTracker.handleMonitorAutomaticKeepalive(
                            new Network(UNDERLY_NETID), slot, TEST_NETID, isAnyVpnNetwork));
            HandlerUtils.waitForIdle(mHandler, HANDLER_TIMEOUT_MS);

            if (!isAnyVpnNetwork) {
                // Keepalive should be stop if there is no VPN network.
                verify(mKeepaliveTracker).handleStopKeepalive(mNai, slot, SUCCESS);
                verify(mAlarmManager, never()).setExact(anyInt(), anyLong(), any());
            } else {
                // Trigger next alarm.
                verify(mAlarmManager, timeout(1000)).setExact(
                        anyInt(), anyLong(), argThat(intent -> getSlot(intent) == ki.mSlot));

                // No TCP socket on the VPN network. Keepalive will be suspended.
                setupResponseWithoutSocketExisting();
                // Reset it because only one VerificationMode is allowed.
                reset(mAlarmManager);
                mHandler.post(() ->
                        mAOOKeepaliveTracker.handleMonitorAutomaticKeepalive(
                                new Network(UNDERLY_NETID), slot, TEST_NETID, isAnyVpnNetwork));
                HandlerUtils.waitForIdle(mHandler, HANDLER_TIMEOUT_MS);
                // Keepalive should be suspended
                verify(mKeepaliveTracker).handleStopKeepalive(mNai, slot, SUCCESS);
                // Trigger next alarm.
                verify(mAlarmManager, timeout(1000)).setExact(
                        anyInt(), anyLong(), argThat(intent -> getSlot(intent) == ki.mSlot));

                // TCP sockets connected on the VPN network, so keepalive is resumed.
                setupResponseWithSocketExisting();
                reset(mAlarmManager);
                mHandler.post(() ->
                        mAOOKeepaliveTracker.handleMonitorAutomaticKeepalive(
                                new Network(UNDERLY_NETID), slot, TEST_NETID, isAnyVpnNetwork));
                HandlerUtils.waitForIdle(mHandler, HANDLER_TIMEOUT_MS);
                // Keepalive should be resumed to trigger another handleStartKeepalive.
                // Keepalive is re-created, so verify the interaction only with specifying the
                // message.
                verify(mKeepaliveTracker, times(2)).handleStartKeepalive(any());
                // Trigger next alarm.
                verify(mAlarmManager, timeout(1000)).setExact(
                        anyInt(), anyLong(), argThat(intent -> getSlot(intent) == ki.mSlot));
            }
        } else {
            verify(mAlarmManager, never()).setExact(anyInt(), anyLong(), any());
        }
    }

    private int getSlot(PendingIntent pi) {
        final Intent alarmIntent = runAsShell(GET_INTENT_SENDER_INTENT, () -> pi.getIntent());
        assertNotNull(alarmIntent);
        return alarmIntent.getIntExtra(EXTRA_SLOT, -1);
    }
}
