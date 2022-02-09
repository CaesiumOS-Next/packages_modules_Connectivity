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

package android.net.cts;

import static android.Manifest.permission.MANAGE_TEST_NETWORKS;
import static android.system.OsConstants.IPPROTO_IP;
import static android.system.OsConstants.IPPROTO_UDP;

import static androidx.test.InstrumentationRegistry.getContext;

import static com.android.testutils.TestPermissionUtil.runAsShell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.ConnectivitySettingsManager;
import android.net.InetAddresses;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.TestNetworkInterface;
import android.net.TestNetworkManager;
import android.net.TestNetworkSpecifier;
import android.os.Handler;
import android.os.HandlerThread;
import android.platform.test.annotations.AppModeFull;
import android.util.Log;

import androidx.test.runner.AndroidJUnit4;

import com.android.net.module.util.NetworkStackConstants;
import com.android.net.module.util.PacketBuilder;
import com.android.testutils.RecorderCallback.CallbackEntry;
import com.android.testutils.TestableNetworkAgent;
import com.android.testutils.TestableNetworkCallback;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

@AppModeFull(reason = "Socket cannot bind in instant app mode")
@RunWith(AndroidJUnit4.class)
public class RateLimitTest {
    private static final String TAG = "RateLimitTest";
    private static final int NETWORK_CALLBACK_TIMEOUT_MS = 30_000;
    private static final LinkAddress LOCAL_IP4_ADDR = new LinkAddress("10.0.0.1/8");
    private static final InetAddress REMOTE_IP4_ADDR = InetAddresses.parseNumericAddress("8.8.8.8");
    private static final short TEST_UDP_PORT = 1234;
    private static final byte TOS = 0;
    private static final short ID = 27149;
    private static final short DONT_FRAG_FLAG_MASK = (short) 0x4000; // flags=DF, offset=0
    private static final byte TIME_TO_LIVE = 64;
    private static final byte[] PAYLOAD = new byte[1472];

    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private Context mContext;
    private TestNetworkManager mNetworkManager;
    private TestNetworkInterface mTunInterface;
    private ConnectivityManager mConnectivityManager;
    private TestNetworkSpecifier mNetworkSpecifier;
    private NetworkCapabilities mNetworkCapabilities;
    private TestableNetworkCallback mNetworkCallback;
    private LinkProperties mLinkProperties;
    private TestableNetworkAgent mNetworkAgent;
    private Network mNetwork;
    private DatagramSocket mSocket;

    @Before
    public void setUp() throws IOException {
        mHandlerThread = new HandlerThread(RateLimitTest.class.getSimpleName());
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        runAsShell(MANAGE_TEST_NETWORKS, () -> {
            mContext = getContext();

            mNetworkManager = mContext.getSystemService(TestNetworkManager.class);
            mTunInterface = mNetworkManager.createTunInterface(Arrays.asList(LOCAL_IP4_ADDR));
        });

        mConnectivityManager = mContext.getSystemService(ConnectivityManager.class);
        mNetworkSpecifier = new TestNetworkSpecifier(mTunInterface.getInterfaceName());
        mNetworkCapabilities = new NetworkCapabilities.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
                .addTransportType(NetworkCapabilities.TRANSPORT_TEST)
                .setNetworkSpecifier(mNetworkSpecifier).build();
        mNetworkCallback = new TestableNetworkCallback();

        runAsShell(MANAGE_TEST_NETWORKS, () -> {
            mConnectivityManager.requestNetwork(
                    new NetworkRequest.Builder()
                            .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                            .addTransportType(NetworkCapabilities.TRANSPORT_TEST)
                            .setNetworkSpecifier(mNetworkSpecifier)
                            .build(),
                    mNetworkCallback);
        });

        mLinkProperties = new LinkProperties();
        mLinkProperties.addLinkAddress(LOCAL_IP4_ADDR);
        mLinkProperties.setInterfaceName(mTunInterface.getInterfaceName());
        mLinkProperties.addRoute(
                new RouteInfo(new IpPrefix(NetworkStackConstants.IPV4_ADDR_ANY, 0), null,
                        mTunInterface.getInterfaceName()));


        runAsShell(MANAGE_TEST_NETWORKS, () -> {
            mNetworkAgent = new TestableNetworkAgent(mContext, mHandlerThread.getLooper(),
                    mNetworkCapabilities, mLinkProperties,
                    new NetworkAgentConfig.Builder().setExplicitlySelected(
                            true).setUnvalidatedConnectivityAcceptable(true).build());

            mNetworkAgent.register();
            mNetworkAgent.markConnected();
        });

        mNetwork = mNetworkAgent.getNetwork();
        mNetworkCallback.expectCallback(CallbackEntry.AVAILABLE, mNetwork,
                NETWORK_CALLBACK_TIMEOUT_MS);
        mSocket = new DatagramSocket(TEST_UDP_PORT);
        mSocket.setSoTimeout(1_000);
        mNetwork.bindSocket(mSocket);
    }

    @After
    public void tearDown() throws IOException {
        // whatever happens, don't leave the device in rate limited state.
        ConnectivitySettingsManager.setIngressRateLimitInBytesPerSecond(mContext, -1);
        mSocket.close();
        mNetworkAgent.unregister();
        mTunInterface.getFileDescriptor().close();
        mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
        mHandlerThread.quitSafely();
    }

    private static void sendToTunIface(final TestNetworkInterface iface, ByteBuffer buffer)
            throws Exception {
        FileOutputStream out = new FileOutputStream(iface.getFileDescriptor().getFileDescriptor());
        out.write(buffer.array(), buffer.arrayOffset(), buffer.limit());
    }

    private static void sendDataToTunInterface(final TestNetworkInterface iface,
            int durationMillis) throws Exception {
        final ByteBuffer buffer = PacketBuilder.allocate(false, IPPROTO_IP, IPPROTO_UDP,
                PAYLOAD.length);
        final PacketBuilder builder = new PacketBuilder(buffer);
        builder.writeIpv4Header(TOS, ID, DONT_FRAG_FLAG_MASK, TIME_TO_LIVE,
                (byte) IPPROTO_UDP, (Inet4Address) REMOTE_IP4_ADDR,
                (Inet4Address) LOCAL_IP4_ADDR.getAddress());
        builder.writeUdpHeader((short) TEST_UDP_PORT, (short) TEST_UDP_PORT);
        buffer.put(PAYLOAD);
        builder.finalizePacket();

        long endMillis = System.currentTimeMillis() + durationMillis;
        while (System.currentTimeMillis() < endMillis) {
            sendToTunIface(iface, buffer);
        }
    }

    private static class RateMeasurementSocketReader extends Thread {
        private final AtomicBoolean mIsRunning = new AtomicBoolean(false);
        private DatagramSocket mSocket;
        private long mStartMillis = 0;
        private long mStopMillis = 0;
        private long mBytesReceived = 0;

        RateMeasurementSocketReader(DatagramSocket socket) throws Exception {
            mSocket = socket;
        }

        public void startTest() {
            mIsRunning.set(true);
            start();
        }

        public long stopAndGetResult() throws Exception {
            mIsRunning.set(false);
            join();

            final long durationMillis = mStopMillis - mStartMillis;
            return (long) ((double) mBytesReceived / (durationMillis / 1000.0));
        }

        @Override
        public void run() {
            // Since the actual data is not used, the buffer can just be recycled.
            final byte[] recvBuf = new byte[1500];
            final DatagramPacket receivedPacket = new DatagramPacket(recvBuf, recvBuf.length);
            mStartMillis = System.currentTimeMillis();
            while (mIsRunning.get()) {
                try {
                    mSocket.receive(receivedPacket);
                    mBytesReceived += receivedPacket.getLength();
                } catch (SocketTimeoutException e) {
                    // sender has stopped sending data, do nothing and return.
                } catch (IOException e) {
                    Log.e(TAG, "socket receive failed", e);
                }
            }
            mStopMillis = System.currentTimeMillis();
        }
    }

    private void assertGreaterThan(long lhs, long rhs) {
        assertTrue("Failed comparison: " + lhs + " > " + rhs, lhs > rhs);
    }

    private void assertLessThan(long lhs, long rhs) {
        assertTrue("Failed comparison: " + lhs + " < " + rhs, lhs < rhs);
    }

    @Test
    public void testIngressRateLimit_testSetting() {
        int dataLimitInBytesPerSecond = 1000;
        ConnectivitySettingsManager.setIngressRateLimitInBytesPerSecond(mContext,
                dataLimitInBytesPerSecond);
        assertEquals(dataLimitInBytesPerSecond,
                ConnectivitySettingsManager.getIngressRateLimitInBytesPerSecond(mContext));
        ConnectivitySettingsManager.setIngressRateLimitInBytesPerSecond(mContext, -1);
        assertEquals(-1, ConnectivitySettingsManager.getIngressRateLimitInBytesPerSecond(mContext));
    }

    // This test runs for about 15s.
    private long runIngressDataRateMeasurement() throws Exception {
        final RateMeasurementSocketReader reader = new RateMeasurementSocketReader(mSocket);
        reader.startTest();
        final int durationMillis = 15_000;
        sendDataToTunInterface(mTunInterface, durationMillis);
        return reader.stopAndGetResult();
    }

    @Test
    public void testIngressRateLimit_testLimit() throws Exception {
        // If this value is too low, this test might become flaky because of the burst value that
        // allows to send at a higher data rate for a short period of time. The faster the data rate
        // and the longer the test, the less this test will be affected.
        final long dataLimitInBytesPerSecond = 500_000; // 500KB/s
        long resultInBytesPerSecond = runIngressDataRateMeasurement();
        Log.i(TAG, "rate limit disabled result: " + resultInBytesPerSecond);
        assertGreaterThan(resultInBytesPerSecond, dataLimitInBytesPerSecond);

        // enable rate limit and wait a second to make sure the tc filter is installed before
        // starting the test.
        ConnectivitySettingsManager.setIngressRateLimitInBytesPerSecond(mContext,
                dataLimitInBytesPerSecond);
        // TODO: this may not be necessary
        Thread.sleep(500);

        resultInBytesPerSecond = runIngressDataRateMeasurement();
        Log.i(TAG, "rate limit enabled result: " + resultInBytesPerSecond);
        assertLessThan(resultInBytesPerSecond, dataLimitInBytesPerSecond);

        ConnectivitySettingsManager.setIngressRateLimitInBytesPerSecond(mContext, -1);

        resultInBytesPerSecond = runIngressDataRateMeasurement();
        assertGreaterThan(resultInBytesPerSecond, dataLimitInBytesPerSecond);
    }
}
