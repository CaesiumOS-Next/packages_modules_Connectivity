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
import static android.net.NattSocketKeepalive.NATT_PORT;
import static android.provider.DeviceConfig.NAMESPACE_CONNECTIVITY;

import static com.android.server.connectivity.KeepaliveTracker.DYNAMIC_KEEPALIVE_MODE;
import static com.android.server.connectivity.KeepaliveTracker.DYNAMIC_KEEPALIVE_MODE_ENABLED;
import static com.android.testutils.TestPermissionUtil.runAsShell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.IDnsResolver;
import android.net.INetd;
import android.net.INetworkAgent;
import android.net.INetworkAgentRegistry;
import android.net.ISocketKeepaliveCallback;
import android.net.InetAddresses;
import android.net.KeepalivePacketData;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.MarkMaskParcel;
import android.net.NattKeepalivePacketData;
import android.net.Network;
import android.net.NetworkAgent;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkProvider;
import android.net.NetworkScore;
import android.net.QosFilterParcelable;
import android.net.SocketKeepalive;
import android.net.TcpKeepalivePacketData;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Process;
import android.os.UserHandle;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.ArrayMap;

import com.android.connectivity.resources.R;
import com.android.server.ConnectivityService;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import libcore.util.HexEncoding;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.FileDescriptor;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@RunWith(DevSdkIgnoreRunner.class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
public class KeepaliveTrackerTest {
    private static final int[] TEST_SUPPORTED_KEEPALIVES = {1, 3, 0, 0, 0, 0, 0, 0, 0};
    private static final int TEST_NETID = 0xA85;
    private static final int TEST_NETID_FWMARK = 0x0A85;
    private static final int OTHER_NETID = 0x1A85;
    private static final int NETID_MASK = 0xffff;
    private static final int UNDERLY_NETID = 0xB85;
    private static final int SUPPORTED_SLOT_COUNT = 2;
    private static final String TEST_SRC_ADDR = "192.0.2.139";
    private static final UserInfo SYSTEM_USER = new UserInfo(0, "system", UserInfo.FLAG_PRIMARY);
    private KeepaliveTracker mKeepaliveTracker;
    private HandlerThread mHandlerThread;
    private final FileDescriptor mTestFd = new FileDescriptor();

    @Mock INetd mNetd;
    @Mock KeepaliveTracker.Dependencies mDependencies;
    @Mock Context mCtx;
    @Mock Resources mResources;
    @Mock ConnectivityService mConnService;
    @Mock IDnsResolver mDnsResolver;
    @Mock QosCallbackTracker mQosCallbackTracker;
    @Mock PackageManager mPackageManager;
    @Mock AlarmManager mAlarmManager;
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

        mockService(AlarmManager.class, Context.ALARM_SERVICE, mAlarmManager);
        doReturn(mNetd).when(mDependencies).getNetd();
        doReturn(makeMarkMaskParcel(NETID_MASK, TEST_NETID_FWMARK)).when(mNetd)
                .getFwmarkForNetwork(TEST_NETID);
        doReturn(2).when(mDependencies)
                .getSupportedKeepalivesForNetworkCapabilities(any(), any());
        doReturn(Process.myProcessName()).when(mCtx).getPackageName();
        doReturn(mPackageManager).when(mCtx).getPackageManager();
        doReturn(mTestFd).when(mDependencies).dupFileDescriptor(any());
        doReturn(TEST_SUPPORTED_KEEPALIVES).when(mDependencies).getSupportedKeepalives();
        doReturn(mResources).when(mDependencies).newConnectivityResources();
        mockResource();
        doNothing().when(mDependencies).sendPollingRequest(any(), any());
        doReturn(DYNAMIC_KEEPALIVE_MODE_ENABLED).when(mDependencies).getDeviceConfigPropertyInt(
                NAMESPACE_CONNECTIVITY, DYNAMIC_KEEPALIVE_MODE, DYNAMIC_KEEPALIVE_MODE_ENABLED);

        final UserHandle userHandle = UserHandle.of(SYSTEM_USER.id);
        doReturn(userHandle).when(mCtx).getUser();
        Map<String, Integer> sPackages = new ArrayMap<>();
        sPackages.put(Process.myProcessName(), Process.myUid());
        setMockedPackages(sPackages);

        mHandlerThread = new HandlerThread("KeepaliveTrackerTest");
        mHandlerThread.start();

        mKeepaliveTracker = new KeepaliveTracker(mCtx, mHandlerThread.getThreadHandler(),
                mDependencies);
    }

    private void mockResource() {
        doReturn(SUPPORTED_SLOT_COUNT).when(mResources).getInteger(
                R.integer.config_reservedPrivilegedKeepaliveSlots);
        doReturn(SUPPORTED_SLOT_COUNT).when(mResources).getInteger(
                R.integer.config_allowedUnprivilegedKeepalivePerUid);
    }

    @Test
    public void testIsAnyTcpSocketConnected_withTargetNetId() throws Exception {
        setupResponseWithSocketExisting();
        assertTrue(mKeepaliveTracker.isAnyTcpSocketConnected(TEST_NETID));
    }

    @Test
    public void testIsAnyTcpSocketConnected_withIncorrectNetId() throws Exception {
        setupResponseWithSocketExisting();
        assertFalse(mKeepaliveTracker.isAnyTcpSocketConnected(OTHER_NETID));
    }

    @Test
    public void testIsAnyTcpSocketConnected_noSocketExists() throws Exception {
        setupResponseWithoutSocketExisting();
        assertFalse(mKeepaliveTracker.isAnyTcpSocketConnected(TEST_NETID));
    }

    private void setupResponseWithSocketExisting() throws Exception {
        final ByteBuffer tcpBufferV6 = getByteBuffer(TEST_RESPONSE_BYTES);
        final ByteBuffer tcpBufferV4 = getByteBuffer(TEST_RESPONSE_BYTES);
        doReturn(tcpBufferV6, tcpBufferV4).when(mDependencies).recvMessage(any());
    }

    private void setupResponseWithoutSocketExisting() throws Exception {
        final ByteBuffer tcpBufferV6 = getByteBuffer(SOCK_DIAG_NO_TCP_INET_BYTES);
        final ByteBuffer tcpBufferV4 = getByteBuffer(SOCK_DIAG_NO_TCP_INET_BYTES);
        doReturn(tcpBufferV6, tcpBufferV4).when(mDependencies).recvMessage(any());
    }

    private MarkMaskParcel makeMarkMaskParcel(final int mask, final int mark) {
        final MarkMaskParcel parcel = new MarkMaskParcel();
        parcel.mask = mask;
        parcel.mark = mark;
        return parcel;
    }

    private ByteBuffer getByteBuffer(final byte[] bytes) {
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        return buffer;
    }

    private TestSocketKeepaliveCallback mKeepaliveCallback = new TestSocketKeepaliveCallback();

    private static class TestSocketKeepaliveCallback extends ISocketKeepaliveCallback.Stub {
        private static final int TIMEOUT_MS = 2000;
        public enum CallbackType { ON_STARTED, ON_STOPPED, ON_ERROR, ON_DATARECEIVED }
        private final LinkedBlockingQueue<CallbackType> mCallbacks = new LinkedBlockingQueue<>();
        @Override
        public void onStarted(int slot) {
            mCallbacks.add(CallbackType.ON_STARTED);
        }

        @Override
        public void onStopped() {
            mCallbacks.add(CallbackType.ON_STOPPED);
        }

        @Override
        public void onError(int error) {
            mCallbacks.add(CallbackType.ON_ERROR);
        }

        @Override
        public void onDataReceived() {
            mCallbacks.add(CallbackType.ON_DATARECEIVED);
        }

        public void expect(CallbackType callbackValue) throws InterruptedException {
            assertEquals(callbackValue, mCallbacks.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        }
    }

    private static class TestNetworkAgent extends INetworkAgent.Stub {
        private final Handler mHandler;
        private static final int TIMEOUT_MS = 2000;
        public enum CallbackType {
            ON_START_NATT_KEEPALIVE,
            ON_START_TCP_KEEPALIVE,
            ON_STOP_KEEPALIVE,
            ON_ADD_NATT_KEEPALIVE_PACKET_FILTER,
            ON_ADD_TCP_KEEPALIVE_PACKET_FILTER,
            ON_REMOVE_KEEPALIVE_PACKET_FILTER
        }

        private final LinkedBlockingQueue<CallbackType> mCallbacks = new LinkedBlockingQueue<>();
        public int mSlot = -1;
        private TestNetworkAgent(Handler handler) {
            mHandler = handler;
        }

        @Override
        public void onRegistered(INetworkAgentRegistry registry) {
            // Not care
        }

        @Override
        public void onDisconnected() {
            // Not care
        }

        @Override
        public void onBandwidthUpdateRequested() {
            // Not care
        }

        @Override
        public void onValidationStatusChanged(
                int validationStatus,  String captivePortalUrl) {
            // Not care
        }

        @Override
        public void onSaveAcceptUnvalidated(boolean acceptUnvalidated) {
            // Not care
        }

        @Override
        public void onStartNattSocketKeepalive(int slot, int intervalDurationMs,
                 NattKeepalivePacketData packetData) {
            mSlot = slot;
            mCallbacks.add(CallbackType.ON_START_NATT_KEEPALIVE);
        }

        @Override
        public void onStartTcpSocketKeepalive(int slot, int intervalDurationMs,
                TcpKeepalivePacketData packetData) {
            mSlot = slot;
            mCallbacks.add(CallbackType.ON_START_TCP_KEEPALIVE);
        }

        @Override
        public void onStopSocketKeepalive(int slot) {
            if (mSlot == slot) mSlot = -1;

            mCallbacks.add(CallbackType.ON_STOP_KEEPALIVE);
        }

        @Override
        public void onSignalStrengthThresholdsUpdated(int[] thresholds) {
            // Not care
        }

        @Override
        public void onPreventAutomaticReconnect() {
            // Not care
        }

        @Override
        public void onAddNattKeepalivePacketFilter(int slot,
                NattKeepalivePacketData packetData) {
            mCallbacks.add(CallbackType.ON_ADD_NATT_KEEPALIVE_PACKET_FILTER);
        }

        @Override
        public void onAddTcpKeepalivePacketFilter(int slot,
                TcpKeepalivePacketData packetData) {
            mCallbacks.add(CallbackType.ON_ADD_TCP_KEEPALIVE_PACKET_FILTER);
        }

        @Override
        public void onRemoveKeepalivePacketFilter(int slot) {
            mCallbacks.add(CallbackType.ON_REMOVE_KEEPALIVE_PACKET_FILTER);
        }

        @Override
        public void onQosFilterCallbackRegistered(final int qosCallbackId,
                final QosFilterParcelable qosFilterParcelable) {
            // Not care
        }

        @Override
        public void onQosCallbackUnregistered(final int qosCallbackId) {
            // Not care
        }

        @Override
        public void onNetworkCreated() {
            // Not care
        }

        @Override
        public void onNetworkDestroyed() {
            // Not care
        }

        @Override
        public void onDscpPolicyStatusUpdated(final int policyId,
                @NetworkAgent.DscpPolicyStatus final int status) {
            // Not care
        }

        public void expect(CallbackType callbackValue) throws InterruptedException {
            assertEquals(callbackValue, mCallbacks.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        }
    }

    /**
     * Populate {@link #mPackageManager} with a fake packageName-to-UID mapping.
     */
    private void setMockedPackages(final Map<String, Integer> packages) {
        try {
            doAnswer(invocation -> {
                final String appName = (String) invocation.getArguments()[0];
                final int userId = (int) invocation.getArguments()[1];
                Integer appId = packages.get(appName);
                if (appId == null) throw new PackageManager.NameNotFoundException(appName);
                return UserHandle.getUid(userId, appId);
            }).when(mPackageManager).getPackageUidAsUser(anyString(), anyInt());
        } catch (Exception e) {
        }
    }

    private <T> void mockService(Class<T> clazz, String name, T service) {
        doReturn(service).when(mCtx).getSystemService(name);
        doReturn(name).when(mCtx).getSystemServiceName(clazz);
        if (mCtx.getSystemService(clazz).getClass().equals(Object.class)) {
            // Test is using mockito-extended (mContext uses Answers.RETURNS_DEEP_STUBS and returned
            // a mock object on a final method)
            doCallRealMethod().when(mCtx).getSystemService(clazz);
        }
    }

    private NetworkAgentInfo setupTestNetworkAgentInfo(INetworkAgent agent) {
        final NetworkInfo networkinfo = new NetworkInfo(
                ConnectivityManager.TYPE_WIFI, 0, "WIFI", "");
        networkinfo.setDetailedState(NetworkInfo.DetailedState.CONNECTED, null, null);
        final LinkProperties lp = new LinkProperties();
        lp.addLinkAddress(new LinkAddress(InetAddresses.parseNumericAddress(TEST_SRC_ADDR), 32));

        final NetworkAgentInfo nai = new NetworkAgentInfo(
                agent, new Network(UNDERLY_NETID),
                networkinfo, lp, new NetworkCapabilities(),
                new NetworkScore.Builder().setLegacyInt(50).build(),
                mCtx, null, new NetworkAgentConfig.Builder().build(), mConnService, mNetd,
                mDnsResolver, NetworkProvider.ID_NONE, Binder.getCallingUid(), 100,
                mQosCallbackTracker, new ConnectivityService.Dependencies());

        return nai;
    }

    private KeepaliveTracker.KeepaliveInfo buildTestKeepaliveInfo(NetworkAgentInfo nai,
            boolean dynamicKeepalive) throws Exception {
        final KeepalivePacketData packet = NattKeepalivePacketData.nattKeepalivePacket(
                InetAddress.getByName(TEST_SRC_ADDR), 1234 /* srcPort */,
                InetAddress.getByName("8.8.8.8"), NATT_PORT);
        KeepaliveTracker.KeepaliveInfo ki = mKeepaliveTracker.new KeepaliveInfo(
                mKeepaliveCallback, nai, packet, 2000 /* intervalSeconds */,
                KeepaliveTracker.KeepaliveInfo.TYPE_NATT, new FileDescriptor(), dynamicKeepalive);
        return ki;
    }

    @Test
    public void testDynamicKeepaliveDisabled() throws Exception {
        testDynamicKeepalive(false);
    }

    @Test
    public void testDynamicKeepaliveEnabled() throws Exception {
        testDynamicKeepalive(true);
    }

    public void testDynamicKeepalive(boolean dynamicKeepalive) throws Exception {
        final TestNetworkAgent agent = new TestNetworkAgent(mHandlerThread.getThreadHandler());
        final NetworkAgentInfo nai = setupTestNetworkAgentInfo(agent);
        final KeepaliveTracker.KeepaliveInfo ki =
                buildTestKeepaliveInfo(nai, true /* dynamicKeepalive */);
        final ArgumentCaptor<PendingIntent> mIntentCaptor =
                ArgumentCaptor.forClass(PendingIntent.class);

        // Start keepalive
        mHandlerThread.getThreadHandler().post(()-> mKeepaliveTracker.handleStartKeepalive(
                Message.obtain(mHandlerThread.getThreadHandler(), 0, ki)));
        agent.expect(TestNetworkAgent.CallbackType.ON_ADD_NATT_KEEPALIVE_PACKET_FILTER);
        agent.expect(TestNetworkAgent.CallbackType.ON_START_NATT_KEEPALIVE);

        // Lower the polling interval so that the alarm will be fired soon.
        doReturn(100L).when(mDependencies).getTcpPollingInterval();

        // Verify alarm being received.
        if (dynamicKeepalive) {
            verify(mAlarmManager, timeout(1000)).setExact(
                    anyInt(), anyLong(), mIntentCaptor.capture());
            final PendingIntent pi = mIntentCaptor.getValue();
            final Intent alarmIntent = runAsShell(GET_INTENT_SENDER_INTENT, () -> pi.getIntent());
            final int slot = alarmIntent.getIntExtra(KeepaliveTracker.EXTRA_SLOT, -1);
            assertNotNull(alarmIntent);
            assertEquals(agent.mSlot, slot);
        } else {
            verify(mAlarmManager, never()).setExact(anyInt(), anyLong(), any());
        }

        // Make keepalive status connected.
        // In real case, this should be triggered from network agent.
        mHandlerThread.getThreadHandler().post(() -> mKeepaliveTracker.handleEventSocketKeepalive(
                nai, agent.mSlot, SocketKeepalive.SUCCESS));
        mKeepaliveCallback.expect(TestSocketKeepaliveCallback.CallbackType.ON_STARTED);

        setupResponseWithoutSocketExisting();
        final int slot = agent.mSlot;
        if (dynamicKeepalive) {
            // Store current slot so that the value will not be removed when keepalive is stopped.
            // Trigger TCP socket status check.
            // Expect no socket and then stop the keepalive.
            mHandlerThread.getThreadHandler().post(() ->
                    mKeepaliveTracker.handleMonitorTcpConnections(nai.network(), slot, TEST_NETID));
            agent.expect(TestNetworkAgent.CallbackType.ON_STOP_KEEPALIVE);
            agent.expect(TestNetworkAgent.CallbackType.ON_REMOVE_KEEPALIVE_PACKET_FILTER);

            // Make status to stopped
            // In real case, this should be triggered from network agent.
            mHandlerThread.getThreadHandler().post(() ->
                    mKeepaliveTracker.handleEventSocketKeepalive(
                            nai, slot, SocketKeepalive.SUCCESS));
            mKeepaliveCallback.expect(TestSocketKeepaliveCallback.CallbackType.ON_ERROR);

            setupResponseWithSocketExisting();

            // Trigger TCP socket status check. Expect to restart keepalive.
            mHandlerThread.getThreadHandler().post(() ->
                    mKeepaliveTracker.handleMonitorTcpConnections(nai.network(), slot, TEST_NETID));
            agent.expect(TestNetworkAgent.CallbackType.ON_ADD_NATT_KEEPALIVE_PACKET_FILTER);
            agent.expect(TestNetworkAgent.CallbackType.ON_START_NATT_KEEPALIVE);
        }

        mKeepaliveTracker.handleStopKeepalive(nai, slot, SocketKeepalive.SUCCESS);
        agent.expect(TestNetworkAgent.CallbackType.ON_STOP_KEEPALIVE);
        agent.expect(TestNetworkAgent.CallbackType.ON_REMOVE_KEEPALIVE_PACKET_FILTER);
    }
}
