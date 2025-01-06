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

package com.android.cts.net.hostside;

import static android.Manifest.permission.MANAGE_TEST_NETWORKS;
import static android.Manifest.permission.NEARBY_WIFI_DEVICES;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.net.NetworkCapabilities.TRANSPORT_TEST;
import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.IPPROTO_IP;
import static android.system.OsConstants.IPPROTO_TCP;
import static android.system.OsConstants.IPPROTO_UDP;
import static android.system.OsConstants.POLLERR;
import static android.system.OsConstants.POLLIN;
import static android.system.OsConstants.SOCK_DGRAM;
import static android.system.OsConstants.SOCK_STREAM;

import static com.android.compatibility.common.util.SystemUtil.runWithShellPermissionIdentity;
import static com.android.testutils.Cleanup.testAndCleanup;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.annotation.NonNull;
import android.content.Context;
import android.net.InetAddresses;
import android.net.LinkAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TestNetworkInterface;
import android.net.TestNetworkManager;
import android.os.Binder;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.net.module.util.PacketBuilder;
import com.android.testutils.AutoReleaseNetworkCallbackRule;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.RecorderCallback.CallbackEntry;
import com.android.testutils.TestableNetworkCallback;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

@RunWith(AndroidJUnit4.class)
public class LocalNetworkTest {

    @Rule
    public final AutoReleaseNetworkCallbackRule
            networkCallbackRule = new AutoReleaseNetworkCallbackRule();
    private static final long NETWORK_CALLBACK_TIMEOUT_MS = 30_000;
    private static final int SOCKET_TIMEOUT_MS = 100;

    private static final InetAddress LOCAL_IPV4_ADDRESS =
            InetAddresses.parseNumericAddress("192.168.0.10");
    private static final InetAddress LOCAL_IPV6_ADDRESS =
            InetAddresses.parseNumericAddress("::ffff:a00:1");
    private static final InetAddress REMOTE_IPV4_ADDRESS =
            InetAddresses.parseNumericAddress("8.8.8.8");

    private Context mTestContext;
    private Context mTargetContext;

    @Before
    public void setUp() {
        mTestContext = getInstrumentation().getContext();
        mTargetContext = getInstrumentation().getTargetContext();
//        testAppPkg = mTargetContext.getPackageName();
    }

    private void doTestSendPacket(FileDescriptor srcSock, boolean isIpv6, boolean shouldDrop)
            throws Exception {
        final InetAddress dstAddress = isIpv6 ? LOCAL_IPV6_ADDRESS : LOCAL_IPV4_ADDRESS;
        final byte[] sendData = new byte[16];
        final Random random = new Random();
        random.nextBytes(sendData);
        try {
            Os.sendto(srcSock, ByteBuffer.wrap(sendData), 0, dstAddress, 443);
        } catch (ErrnoException e) {
            if (shouldDrop) {
                assertEquals(OsConstants.EPERM, e.errno);
                return;
            } else {
                fail("Failed to send packet to local network with permissions granted.");
            }
        }
        if (shouldDrop) {
            fail("Expected packet to be dropped due to EPERM");
        }
    }

    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.BAKLAVA)
    @Test
    public void testMissingPermission_dropsEgressUdpPacket() throws Exception {
        assertEquals(PERMISSION_DENIED,
                mTargetContext.checkSelfPermission(NEARBY_WIFI_DEVICES));
        final ParcelFileDescriptor tunFd = setupTestNetworkAndGetFd();
        final Network testNetwork = getTestNetwork();
        assertNotNull(testNetwork);

        final FileDescriptor srcSock = Os.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
        testNetwork.bindSocket(srcSock);
        testAndCleanup(() -> {
            doTestSendPacket(srcSock, false /*isIpv6*/, true /*shouldDrop*/);
            doTestSendPacket(srcSock, true /*isIpv6*/, true /*shouldDrop*/);
            },
                /* cleanup */ tunFd::close,
                /* cleanup */ () -> runWithShellPermissionIdentity(() -> {
                    mTestContext.getSystemService(TestNetworkManager.class)
                            .teardownTestNetwork(testNetwork);
                }, MANAGE_TEST_NETWORKS));
    }

    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.BAKLAVA)
    @Test
    @Ignore
    // TODO(b/398287882): Remove @Ignore
    public void testMissingPermission_dropsEgressTcpPacket() throws Exception {
        assertEquals(PERMISSION_DENIED,
                mTargetContext.checkSelfPermission(NEARBY_WIFI_DEVICES));
        final ParcelFileDescriptor tunFd = setupTestNetworkAndGetFd();
        final Network testNetwork = getTestNetwork();
        assertNotNull(testNetwork);

        final FileDescriptor srcSock = Os.socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
        testNetwork.bindSocket(srcSock);
        final FileDescriptor dstSock = Os.socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
        testNetwork.bindSocket(dstSock);
        Os.connect(srcSock, LOCAL_IPV4_ADDRESS, 445);

        testAndCleanup(() -> {
            doTestSendPacket(srcSock, false /*isIpv6*/, true /*shouldDrop*/);
            doTestSendPacket(srcSock, true /*isIpv6*/, true /*shouldDrop*/);
            }, /* cleanup */ tunFd::close,
                /* cleanup */ () -> runWithShellPermissionIdentity(() -> {
                    mTestContext.getSystemService(TestNetworkManager.class)
                        .teardownTestNetwork(testNetwork);
                    }, MANAGE_TEST_NETWORKS));
    }

    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.BAKLAVA)
    @Test
    public void testPermissionGranted_sendsEgressUdpPacket() throws Exception {
        Log.e("LocalNetworkTest", "package name is:"+mTargetContext.getPackageName());
        getInstrumentation()
                .getUiAutomation()
                .grantRuntimePermission(mTargetContext.getPackageName(), NEARBY_WIFI_DEVICES);
        assertEquals(PERMISSION_GRANTED,
                mTargetContext.checkSelfPermission(NEARBY_WIFI_DEVICES));
        final ParcelFileDescriptor tunFd = setupTestNetworkAndGetFd();
        final Network testNetwork = getTestNetwork();
        assertNotNull(testNetwork);

        final FileDescriptor srcSock = Os.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
        testNetwork.bindSocket(srcSock);

        testAndCleanup(() -> {
            doTestSendPacket(srcSock, false /*isIpv6*/, false /*shouldDrop*/);
            doTestSendPacket(srcSock, true /*isIpv6*/, false /*shouldDrop*/);
            },/* cleanup */ tunFd::close,
                /* cleanup */ () -> runWithShellPermissionIdentity(() -> {
                    mTestContext.getSystemService(TestNetworkManager.class)
                            .teardownTestNetwork(testNetwork);
                }, MANAGE_TEST_NETWORKS));
    }

    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.BAKLAVA)
    @Test
    public void testPermissionMissing_dropsIngressPacket() throws Exception {
        assertEquals(PERMISSION_DENIED,
                mTargetContext.checkSelfPermission(NEARBY_WIFI_DEVICES));
        final ParcelFileDescriptor tunFd = setupTestNetworkAndGetFd();  // use this as src
        final Network testNetwork = getTestNetwork();
        assertNotNull(testNetwork);

        final Random random = new Random();
        final byte[] sendData = new byte[100];
        random.nextBytes(sendData);

        final ByteBuffer buf = buildIpv4UdpPacket((Inet4Address) LOCAL_IPV4_ADDRESS,
                (Inet4Address) REMOTE_IPV4_ADDRESS,
                (short) 443 /*dstPort*/, (short) 1234 /*srcPort*/, sendData);

        Os.write(tunFd.getFileDescriptor(), buf.array(), 0,
                buf.limit());

        final StructPollfd pollfd = new StructPollfd();
        pollfd.events = (short) POLLIN;
        pollfd.fd = tunFd.getFileDescriptor();
        final int ret = Os.poll(new StructPollfd[]{pollfd}, SOCKET_TIMEOUT_MS);
        assertEquals("Expect not to receive a packet but received a packet", 0, ret);
    }

    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.BAKLAVA)
    @Test
    public void testPermissionGranted_receivesIngressPacket() throws Exception {
        getInstrumentation()
                .getUiAutomation()
                .grantRuntimePermission(mTargetContext.getPackageName(), NEARBY_WIFI_DEVICES);
        assertEquals(PERMISSION_GRANTED,
                mTargetContext.checkSelfPermission(NEARBY_WIFI_DEVICES));
        final ParcelFileDescriptor tunFd = setupTestNetworkAndGetFd();  // use this as src
        final Network testNetwork = getTestNetwork();
        assertNotNull(testNetwork);

        final Random random = new Random();
        final byte[] sendData = new byte[100];
        random.nextBytes(sendData);
        Log.e("LocalNetworkTest", "sendData="+ Arrays.toString(sendData));

        final ByteBuffer buf = buildIpv4UdpPacket((Inet4Address) LOCAL_IPV4_ADDRESS,
                (Inet4Address) REMOTE_IPV4_ADDRESS,
                (short) 443 /*dstPort*/, (short) 1234 /*srcPort*/, sendData);

        Os.write(tunFd.getFileDescriptor(), buf.array(), 0,
                buf.limit());

        final StructPollfd pollfd = new StructPollfd();
        pollfd.events = (short) POLLIN;
        pollfd.fd = tunFd.getFileDescriptor();
        final int ret = Os.poll(new StructPollfd[]{pollfd}, SOCKET_TIMEOUT_MS);

        assertEquals("Expect to receive a packet but did not receive a packet", 1, ret);
        final byte[] recvData = new byte[100];
        final int readSize = Os.read(tunFd.getFileDescriptor(), recvData, 0 /* byteOffset */, recvData.length);
        Log.e("LocalNetworkTest", "recvdata="+ Arrays.toString(recvData));
        assertEquals(recvData.length, readSize);  // packet received is 48, not 100
    }

    private ByteBuffer buildIpv4UdpPacket(final Inet4Address dstAddr, final Inet4Address srcAddr,
            final short dstPort, final short srcPort, final byte[] payload) throws IOException {

        final ByteBuffer buffer = PacketBuilder.allocate(false /* hasEther */,
                OsConstants.IPPROTO_IP, OsConstants.IPPROTO_UDP, payload.length);
        final PacketBuilder packetBuilder = new PacketBuilder(buffer);

        packetBuilder.writeIpv4Header(
                (byte) 0 /* TOS */,
                (short) 27149 /* ID */,
                (short) 0x4000 /* flags=DF, offset=0 */,
                (byte) 64 /* TTL */,
                (byte) OsConstants.IPPROTO_UDP,
                srcAddr,
                dstAddr);
        packetBuilder.writeUdpHeader(srcPort, dstPort);
        buffer.put(payload);

        return packetBuilder.finalizePacket();
    }


    private ParcelFileDescriptor setupTestNetworkAndGetFd() {
        return runWithShellPermissionIdentity(() -> {
            final TestNetworkManager tnm = mTestContext.getSystemService(TestNetworkManager.class);
            final List<LinkAddress> linkAddresses = List.of(new LinkAddress("10.0.0.0/8"),
                    new LinkAddress("192.168.0.0/16"));
            final TestNetworkInterface iface = tnm.createTunInterface(linkAddresses);
            tnm.setupTestNetwork(iface.getInterfaceName(), new Binder());
            return iface.getFileDescriptor();
        }, MANAGE_TEST_NETWORKS);
    }

    private Network getTestNetwork() {
        final TestableNetworkCallback callback = networkCallbackRule.requestNetwork(
                new NetworkRequest.Builder()
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                        .addTransportType(TRANSPORT_TEST)
                        .build()
        );
        return waitForAvailable(callback).getNetwork();
    }

    private CallbackEntry.Available waitForAvailable(@NonNull final TestableNetworkCallback cb) {
        return cb.eventuallyExpect(CallbackEntry.AVAILABLE, NETWORK_CALLBACK_TIMEOUT_MS,
                c -> c instanceof CallbackEntry.Available);
    }
}
