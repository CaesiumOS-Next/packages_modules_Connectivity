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
import static android.Manifest.permission.NETWORK_FACTORY;
import static android.Manifest.permission.NETWORK_SETTINGS;
import static android.system.OsConstants.IPPROTO_IP;
import static android.system.OsConstants.IPPROTO_UDP;

import static androidx.test.InstrumentationRegistry.getContext;

import static com.android.testutils.TestPermissionUtil.runAsShell;

import android.content.Context;
import android.net.ConnectivityManager;
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
import com.android.net.module.util.Struct;
import com.android.net.module.util.structs.Ipv4Header;
import com.android.net.module.util.structs.UdpHeader;
import com.android.testutils.RecorderCallback.CallbackEntry;
import com.android.testutils.TapPacketReader;
import com.android.testutils.TestableNetworkAgent;
import com.android.testutils.TestableNetworkCallback;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.util.Arrays;

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
    private static final byte[] PAYLOAD = "Oh look, data!".getBytes();

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

    private TapPacketReader mPacketReader;

    @Before
    public void setUp() throws IOException {
        runAsShell(MANAGE_TEST_NETWORKS, NETWORK_FACTORY, NETWORK_SETTINGS, () -> {
            mHandlerThread = new HandlerThread(RateLimitTest.class.getSimpleName());
            mHandlerThread.start();
            mHandler = new Handler(mHandlerThread.getLooper());

            mContext = getContext();

            mNetworkManager = mContext.getSystemService(TestNetworkManager.class);
            mTunInterface = mNetworkManager.createTunInterface(Arrays.asList(LOCAL_IP4_ADDR));

            mConnectivityManager = mContext.getSystemService(ConnectivityManager.class);
            mNetworkSpecifier = new TestNetworkSpecifier(mTunInterface.getInterfaceName());
            mNetworkCapabilities = new NetworkCapabilities.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
                    .addTransportType(NetworkCapabilities.TRANSPORT_TEST)
                    .setNetworkSpecifier(mNetworkSpecifier).build();
            mNetworkCallback = new TestableNetworkCallback();

            mConnectivityManager.requestNetwork(
                    new NetworkRequest.Builder()
                            .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                            .addTransportType(NetworkCapabilities.TRANSPORT_TEST)
                            .setNetworkSpecifier(mNetworkSpecifier)
                            .build(),
                    mNetworkCallback);

            mLinkProperties = new LinkProperties();
            mLinkProperties.addLinkAddress(LOCAL_IP4_ADDR);
            mLinkProperties.setInterfaceName(mTunInterface.getInterfaceName());
            mLinkProperties.addRoute(
                    new RouteInfo(new IpPrefix(NetworkStackConstants.IPV4_ADDR_ANY, 0), null,
                            mTunInterface.getInterfaceName()));

            mNetworkAgent = new TestableNetworkAgent(mContext, mHandlerThread.getLooper(),
                    mNetworkCapabilities, mLinkProperties,
                    new NetworkAgentConfig.Builder().setExplicitlySelected(
                            true).setUnvalidatedConnectivityAcceptable(true).build());

            mNetworkAgent.register();
            mNetworkAgent.markConnected();

            mNetwork = mNetworkAgent.getNetwork();
            mNetworkCallback.expectCallback(CallbackEntry.AVAILABLE, mNetwork,
                    NETWORK_CALLBACK_TIMEOUT_MS);
            mSocket = new DatagramSocket();
            mNetwork.bindSocket(mSocket);

            NetworkInterface iface = NetworkInterface.getByName(mTunInterface.getInterfaceName());
            mPacketReader = new TapPacketReader(mHandler,
                    mTunInterface.getFileDescriptor().getFileDescriptor(), iface.getMTU());

            mPacketReader.startAsyncForTest();
        });
    }

    @After
    public void tearDown() throws IOException {
        mHandler.post(() -> mPacketReader.stop());
        mNetworkAgent.unregister();
        mTunInterface.getFileDescriptor().close();
        mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
        mHandlerThread.quitSafely();
    }

    @Test
    public void testIngressRateLimit_EnableDisable() throws Exception {
        DatagramPacket initialPacket = new DatagramPacket(PAYLOAD, 0, PAYLOAD.length,
                REMOTE_IP4_ADDR, TEST_UDP_PORT);

        mSocket.send(initialPacket);

        final ByteBuffer outBuf = ByteBuffer.wrap(mPacketReader.poll(30_000));
        final Ipv4Header ipv4Hdr = Struct.parse(Ipv4Header.class, outBuf);
        final UdpHeader udpHdr = Struct.parse(UdpHeader.class, outBuf);
        Log.e(TAG, "prohr: received ip source address is: " + ipv4Hdr.srcIp);
        Log.e(TAG, "prohr: received ip dest address is: " + ipv4Hdr.dstIp);
        Log.e(TAG, "prohr: received source port is: " + udpHdr.srcPort);
        Log.e(TAG, "prohr: received dest port is: " + udpHdr.dstPort);

        final ByteBuffer inBuf = PacketBuilder.allocate(false, IPPROTO_IP, IPPROTO_UDP,
                PAYLOAD.length);
        final PacketBuilder builder = new PacketBuilder(inBuf);
//        builder.writeL2Header(ethHdr.dstMac, ethHdr.srcMac, (short) ETHER_TYPE_IPV4);
        builder.writeIpv4Header(TOS, ID, DONT_FRAG_FLAG_MASK, TIME_TO_LIVE,
                (byte) IPPROTO_UDP, (Inet4Address) REMOTE_IP4_ADDR,
                (Inet4Address) LOCAL_IP4_ADDR.getAddress());
        builder.writeUdpHeader((short) udpHdr.dstPort, (short) udpHdr.srcPort);
        inBuf.put(PAYLOAD);
        builder.finalizePacket();

        mPacketReader.sendResponse(inBuf);

        final byte[] recvBuf = new byte[1500];
        final DatagramPacket receivedPacket = new DatagramPacket(recvBuf, recvBuf.length);
        mSocket.receive(receivedPacket);
        Log.e(TAG, "prohr: RECEIVED - " + receivedPacket.getData());

//        ConnectivitySettingsManager.setIngressRateLimitInBytesPerSecond(mContext, 1000);
//        ConnectivitySettingsManager.setIngressRateLimitInBytesPerSecond(mContext, -1);
    }
}
