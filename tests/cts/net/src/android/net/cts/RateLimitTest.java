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

import static com.android.net.module.util.NetworkStackConstants.ETHER_TYPE_IPV4;
import static com.android.testutils.TestPermissionUtil.runAsShell;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.MacAddress;
import android.net.Network;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TestNetworkInterface;
import android.net.TestNetworkManager;
import android.net.TestNetworkSpecifier;
import android.os.Handler;
import android.os.HandlerThread;
import android.platform.test.annotations.AppModeFull;

import androidx.test.runner.AndroidJUnit4;

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
import java.nio.ByteBuffer;

@AppModeFull(reason = "Socket cannot bind in instant app mode")
@RunWith(AndroidJUnit4.class)
public class RateLimitTest {
    private static final String TAG = "RateLimitTest";
    private static final int NETWORK_CALLBACK_TIMEOUT_MS = 30_000;
    private static final LinkAddress LOCAL_IP4_ADDR = new LinkAddress("10.0.0.1/8");
    private static final LinkAddress REMOTE_IP4_ADDR = new LinkAddress("10.0.0.1/8");
    private static final byte TYPE_OF_SERVICE = 0;
    private static final short ID = 27149;
    private static final short FLAGS_AND_FRAGMENT_OFFSET = (short) 0x4000; // flags=DF, offset=0
    private static final byte TIME_TO_LIVE = (byte) 0x40;
    private static final ByteBuffer PAYLOAD = ByteBuffer.wrap("prohr: hopefully I will get this to work soon.".getBytes());
    private static short TEST_UDP_PORT = 1234;
    private static final MacAddress MAC_SOURCE = MacAddress.fromString("1:2:3:4:5:6");
    private static final MacAddress MAC_DEST = MacAddress.fromString("a:b:c:d:e:f");


    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private Context mContext;
    private TestNetworkManager mNetworkManager;
    private TestNetworkInterface mTapInterface;
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
            runAsShell(MANAGE_TEST_NETWORKS, NETWORK_FACTORY, NETWORK_SETTINGS, () -> {
                mHandlerThread = new HandlerThread(RateLimitTest.class.getSimpleName());
                mHandlerThread.start();
                mHandler = new Handler(mHandlerThread.getLooper());

                mContext = getContext();

                mNetworkManager = mContext.getSystemService(TestNetworkManager.class);
                mTapInterface = mNetworkManager.createTapInterface();

                mConnectivityManager = mContext.getSystemService(ConnectivityManager.class);
                mNetworkSpecifier = new TestNetworkSpecifier(mTapInterface.getInterfaceName());
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
                mLinkProperties.setInterfaceName(mTapInterface.getInterfaceName());
                // TODO: not needed for ingress test
//                mLinkProperties.addRoute(
//                        new RouteInfo(new IpPrefix(NetworkStackConstants.IPV6_ADDR_ANY, 0), null,
//                                mTapInterface.getInterfaceName()));

                mNetworkAgent = new TestableNetworkAgent(mContext, mHandlerThread.getLooper(),
                        mNetworkCapabilities, mLinkProperties,
                        new NetworkAgentConfig.Builder().setExplicitlySelected(true).setUnvalidatedConnectivityAcceptable(true).build());

                mNetworkAgent.register();
                mNetworkAgent.markConnected();

                mNetwork = mNetworkAgent.getNetwork();
                mNetworkCallback.expectCallback(CallbackEntry.AVAILABLE, mNetwork,
                        NETWORK_CALLBACK_TIMEOUT_MS);
                mSocket = new DatagramSocket();
                mNetwork.bindSocket(mSocket);
            });
    }

    @After
    public void tearDown() throws IOException {
            mNetworkAgent.unregister();
            mTapInterface.getFileDescriptor().close();
            mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
            mHandlerThread.quitSafely();
    }

    @Test
    public void testIngressRateLimit_EnableDisable() throws Exception {
        ByteBuffer buffer = PacketBuilder.allocate(true, IPPROTO_IP, IPPROTO_UDP, 1472);

        PacketBuilder builder = new PacketBuilder(buffer);
        builder.writeL2Header(MAC_SOURCE, MAC_DEST, (short) ETHER_TYPE_IPV4);
        builder.writeIpv4Header(TYPE_OF_SERVICE, ID, FLAGS_AND_FRAGMENT_OFFSET, TIME_TO_LIVE,
                (byte) IPPROTO_UDP, (Inet4Address) REMOTE_IP4_ADDR.getAddress(),
                (Inet4Address) LOCAL_IP4_ADDR.getAddress());
        builder.writeUdpHeader(TEST_UDP_PORT, TEST_UDP_PORT);
        buffer.put(PAYLOAD);
        builder.finalizePacket();

        // mTapInterface.getFileDescriptor() returns a ParcelFileDescriptor()
        FileOutputStream out = new FileOutputStream(mTapInterface.getFileDescriptor().getFileDescriptor());
        byte[] packetBytes = new byte[buffer.limit()];
        buffer.get(packetBytes);
        out.write(packetBytes);

        final byte[] dataReceive = new byte[1500];
        DatagramPacket receivedPacket = new DatagramPacket(dataReceive, dataReceive.length);
        mSocket.receive(receivedPacket);

//        ConnectivitySettingsManager.setIngressRateLimitInBytesPerSecond(mContext, 1000);
//        ConnectivitySettingsManager.setIngressRateLimitInBytesPerSecond(mContext, -1);
    }
}
