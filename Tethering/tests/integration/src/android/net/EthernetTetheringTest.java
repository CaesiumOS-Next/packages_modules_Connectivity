/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.net;

import static android.Manifest.permission.ACCESS_NETWORK_STATE;
import static android.Manifest.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS;
import static android.Manifest.permission.MANAGE_TEST_NETWORKS;
import static android.Manifest.permission.NETWORK_SETTINGS;
import static android.Manifest.permission.TETHER_PRIVILEGED;
import static android.net.InetAddresses.parseNumericAddress;
import static android.net.TetheringManager.CONNECTIVITY_SCOPE_GLOBAL;
import static android.net.TetheringManager.CONNECTIVITY_SCOPE_LOCAL;
import static android.net.TetheringManager.TETHERING_ETHERNET;
import static android.net.TetheringTester.RemoteResponder;
import static android.system.OsConstants.IPPROTO_ICMPV6;
import static android.system.OsConstants.IPPROTO_UDP;

import static com.android.net.module.util.ConnectivityUtils.isIPv6ULA;
import static com.android.net.module.util.HexDump.dumpHexString;
import static com.android.net.module.util.NetworkStackConstants.ETHER_TYPE_IPV6;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ECHO_REPLY_TYPE;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ECHO_REQUEST_TYPE;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ROUTER_ADVERTISEMENT;
import static com.android.testutils.TestNetworkTrackerKt.initTestNetwork;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.app.UiAutomation;
import android.content.Context;
import android.net.EthernetManager.TetheredInterfaceCallback;
import android.net.EthernetManager.TetheredInterfaceRequest;
import android.net.TetheringManager.StartTetheringCallback;
import android.net.TetheringManager.TetheringEventCallback;
import android.net.TetheringManager.TetheringRequest;
import android.net.TetheringTester.TetheredDevice;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.net.module.util.Ipv4Utils;
import com.android.net.module.util.Ipv6Utils;
import com.android.net.module.util.Struct;
import com.android.net.module.util.structs.EthernetHeader;
import com.android.net.module.util.structs.Icmpv6Header;
import com.android.net.module.util.structs.Ipv4Header;
import com.android.net.module.util.structs.Ipv6Header;
import com.android.net.module.util.structs.UdpHeader;
import com.android.testutils.HandlerUtils;
import com.android.testutils.TapPacketReader;
import com.android.testutils.TestNetworkTracker;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class EthernetTetheringTest {

    private static final String TAG = EthernetTetheringTest.class.getSimpleName();
    private static final int TIMEOUT_MS = 5000;
    private static final int SHELL_CMD_MAX_RETRY = 5;
    private static final int DUMP_POLLING_MAX_RETRY = 10;
    private static final int DUMP_POLLING_INTERVAL_MS = 200;
    private static final LinkAddress TEST_IP4_ADDR = new LinkAddress("10.0.0.1/8");
    private static final LinkAddress TEST_IP6_ADDR = new LinkAddress("2001:db8:1::101/64");
    private static final InetAddress TEST_IP4_DNS = parseNumericAddress("8.8.8.8");
    private static final InetAddress TEST_IP6_DNS = parseNumericAddress("2001:db8:1::888");

    private static final String DUMP_TETHERING_CMD = "dumpsys tethering --short";
    private static final String DUMP_IPV4_UPSTREAM_RULES_TITLE = "IPv4 Upstream: proto [inDstMac] "
                    + "iif(iface) src -> nat -> dst [outDstMac] age";

    private final Context mContext = InstrumentationRegistry.getContext();
    private final EthernetManager mEm = mContext.getSystemService(EthernetManager.class);
    private final TetheringManager mTm = mContext.getSystemService(TetheringManager.class);

    private TestNetworkInterface mDownstreamIface;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private TapPacketReader mDownstreamReader;
    private TapPacketReader mUpstreamReader;

    private TetheredInterfaceRequester mTetheredInterfaceRequester;
    private MyTetheringEventCallback mTetheringEventCallback;

    private UiAutomation mUiAutomation =
            InstrumentationRegistry.getInstrumentation().getUiAutomation();
    private boolean mRunTests;

    private TestNetworkTracker mUpstreamTracker;

    @Before
    public void setUp() throws Exception {
        // Needed to create a TestNetworkInterface, to call requestTetheredInterface, and to receive
        // tethered client callbacks. The restricted networks permission is needed to ensure that
        // EthernetManager#isAvailable will correctly return true on devices where Ethernet is
        // marked restricted, like cuttlefish.
        mUiAutomation.adoptShellPermissionIdentity(
                MANAGE_TEST_NETWORKS, NETWORK_SETTINGS, TETHER_PRIVILEGED, ACCESS_NETWORK_STATE,
                CONNECTIVITY_USE_RESTRICTED_NETWORKS);
        mRunTests = mTm.isTetheringSupported() && mEm != null;
        assumeTrue(mRunTests);

        mHandlerThread = new HandlerThread(getClass().getSimpleName());
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mTetheredInterfaceRequester = new TetheredInterfaceRequester(mHandler, mEm);
    }

    private void cleanUp() throws Exception {
        mTm.setPreferTestNetworks(false);

        if (mUpstreamTracker != null) {
            mUpstreamTracker.teardown();
            mUpstreamTracker = null;
        }
        if (mUpstreamReader != null) {
            TapPacketReader reader = mUpstreamReader;
            mHandler.post(() -> reader.stop());
            mUpstreamReader = null;
        }

        mTm.stopTethering(TETHERING_ETHERNET);
        if (mTetheringEventCallback != null) {
            mTetheringEventCallback.awaitInterfaceUntethered();
            mTetheringEventCallback.unregister();
            mTetheringEventCallback = null;
        }
        if (mDownstreamReader != null) {
            TapPacketReader reader = mDownstreamReader;
            mHandler.post(() -> reader.stop());
            mDownstreamReader = null;
        }
        mHandlerThread.quitSafely();
        mTetheredInterfaceRequester.release();
        mEm.setIncludeTestInterfaces(false);
        maybeDeleteTestInterface();
    }

    @After
    public void tearDown() throws Exception {
        try {
            if (mRunTests) cleanUp();
        } finally {
            mUiAutomation.dropShellPermissionIdentity();
        }
    }

    @Test
    public void testVirtualEthernetAlreadyExists() throws Exception {
        // This test requires manipulating packets. Skip if there is a physical Ethernet connected.
        assumeFalse(mEm.isAvailable());

        mDownstreamIface = createTestInterface();
        // This must be done now because as soon as setIncludeTestInterfaces(true) is called, the
        // interface will be placed in client mode, which will delete the link-local address.
        // At that point NetworkInterface.getByName() will cease to work on the interface, because
        // starting in R NetworkInterface can no longer see interfaces without IP addresses.
        int mtu = getMTU(mDownstreamIface);

        Log.d(TAG, "Including test interfaces");
        mEm.setIncludeTestInterfaces(true);

        final String iface = mTetheredInterfaceRequester.getInterface();
        assertEquals("TetheredInterfaceCallback for unexpected interface",
                mDownstreamIface.getInterfaceName(), iface);

        checkVirtualEthernet(mDownstreamIface, mtu);
    }

    @Test
    public void testVirtualEthernet() throws Exception {
        // This test requires manipulating packets. Skip if there is a physical Ethernet connected.
        assumeFalse(mEm.isAvailable());

        CompletableFuture<String> futureIface = mTetheredInterfaceRequester.requestInterface();

        mEm.setIncludeTestInterfaces(true);

        mDownstreamIface = createTestInterface();

        final String iface = futureIface.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertEquals("TetheredInterfaceCallback for unexpected interface",
                mDownstreamIface.getInterfaceName(), iface);

        checkVirtualEthernet(mDownstreamIface, getMTU(mDownstreamIface));
    }

    @Test
    public void testStaticIpv4() throws Exception {
        assumeFalse(mEm.isAvailable());

        mEm.setIncludeTestInterfaces(true);

        mDownstreamIface = createTestInterface();

        final String iface = mTetheredInterfaceRequester.getInterface();
        assertEquals("TetheredInterfaceCallback for unexpected interface",
                mDownstreamIface.getInterfaceName(), iface);

        assertInvalidStaticIpv4Request(iface, null, null);
        assertInvalidStaticIpv4Request(iface, "2001:db8::1/64", "2001:db8:2::/64");
        assertInvalidStaticIpv4Request(iface, "192.0.2.2/28", "2001:db8:2::/28");
        assertInvalidStaticIpv4Request(iface, "2001:db8:2::/28", "192.0.2.2/28");
        assertInvalidStaticIpv4Request(iface, "192.0.2.2/28", null);
        assertInvalidStaticIpv4Request(iface, null, "192.0.2.2/28");
        assertInvalidStaticIpv4Request(iface, "192.0.2.3/27", "192.0.2.2/28");

        final String localAddr = "192.0.2.3/28";
        final String clientAddr = "192.0.2.2/28";
        mTetheringEventCallback = enableEthernetTethering(iface,
                requestWithStaticIpv4(localAddr, clientAddr));

        mTetheringEventCallback.awaitInterfaceTethered();
        assertInterfaceHasIpAddress(iface, localAddr);

        byte[] client1 = MacAddress.fromString("1:2:3:4:5:6").toByteArray();
        byte[] client2 = MacAddress.fromString("a:b:c:d:e:f").toByteArray();

        FileDescriptor fd = mDownstreamIface.getFileDescriptor().getFileDescriptor();
        mDownstreamReader = makePacketReader(fd, getMTU(mDownstreamIface));
        TetheringTester tester = new TetheringTester(mDownstreamReader);
        DhcpResults dhcpResults = tester.runDhcp(client1);
        assertEquals(new LinkAddress(clientAddr), dhcpResults.ipAddress);

        try {
            tester.runDhcp(client2);
            fail("Only one client should get an IP address");
        } catch (TimeoutException expected) { }

    }

    private static boolean isRouterAdvertisement(byte[] pkt) {
        if (pkt == null) return false;

        ByteBuffer buf = ByteBuffer.wrap(pkt);

        final EthernetHeader ethHdr = Struct.parse(EthernetHeader.class, buf);
        if (ethHdr.etherType != ETHER_TYPE_IPV6) return false;

        final Ipv6Header ipv6Hdr = Struct.parse(Ipv6Header.class, buf);
        if (ipv6Hdr.nextHeader != (byte) IPPROTO_ICMPV6) return false;

        final Icmpv6Header icmpv6Hdr = Struct.parse(Icmpv6Header.class, buf);
        return icmpv6Hdr.type == (short) ICMPV6_ROUTER_ADVERTISEMENT;
    }

    private static void expectRouterAdvertisement(TapPacketReader reader, String iface,
            long timeoutMs) {
        final long deadline = SystemClock.uptimeMillis() + timeoutMs;
        do {
            byte[] pkt = reader.popPacket(timeoutMs);
            if (isRouterAdvertisement(pkt)) return;
            timeoutMs = deadline - SystemClock.uptimeMillis();
        } while (timeoutMs > 0);
        fail("Did not receive router advertisement on " + iface + " after "
                +  timeoutMs + "ms idle");
    }

    private static void expectLocalOnlyAddresses(String iface) throws Exception {
        final List<InterfaceAddress> interfaceAddresses =
                NetworkInterface.getByName(iface).getInterfaceAddresses();

        boolean foundIpv6Ula = false;
        for (InterfaceAddress ia : interfaceAddresses) {
            final InetAddress addr = ia.getAddress();
            if (isIPv6ULA(addr)) {
                foundIpv6Ula = true;
            }
            final int prefixlen = ia.getNetworkPrefixLength();
            final LinkAddress la = new LinkAddress(addr, prefixlen);
            if (la.isIpv6() && la.isGlobalPreferred()) {
                fail("Found global IPv6 address on local-only interface: " + interfaceAddresses);
            }
        }

        assertTrue("Did not find IPv6 ULA on local-only interface " + iface,
                foundIpv6Ula);
    }

    @Test
    public void testLocalOnlyTethering() throws Exception {
        assumeFalse(mEm.isAvailable());

        mEm.setIncludeTestInterfaces(true);

        mDownstreamIface = createTestInterface();

        final String iface = mTetheredInterfaceRequester.getInterface();
        assertEquals("TetheredInterfaceCallback for unexpected interface",
                mDownstreamIface.getInterfaceName(), iface);

        final TetheringRequest request = new TetheringRequest.Builder(TETHERING_ETHERNET)
                .setConnectivityScope(CONNECTIVITY_SCOPE_LOCAL).build();
        mTetheringEventCallback = enableEthernetTethering(iface, request);
        mTetheringEventCallback.awaitInterfaceLocalOnly();

        // makePacketReader only works after tethering is started, because until then the interface
        // does not have an IP address, and unprivileged apps cannot see interfaces without IP
        // addresses. This shouldn't be flaky because the TAP interface will buffer all packets even
        // before the reader is started.
        mDownstreamReader = makePacketReader(mDownstreamIface);

        expectRouterAdvertisement(mDownstreamReader, iface, 2000 /* timeoutMs */);
        expectLocalOnlyAddresses(iface);
    }

    private boolean isAdbOverNetwork() {
        // If adb TCP port opened, this test may running by adb over network.
        return (SystemProperties.getInt("persist.adb.tcp.port", -1) > -1)
                || (SystemProperties.getInt("service.adb.tcp.port", -1) > -1);
    }

    @Test
    public void testPhysicalEthernet() throws Exception {
        assumeTrue(mEm.isAvailable());
        // Do not run this test if adb is over network and ethernet is connected.
        // It is likely the adb run over ethernet, the adb would break when ethernet is switching
        // from client mode to server mode. See b/160389275.
        assumeFalse(isAdbOverNetwork());

        // Get an interface to use.
        final String iface = mTetheredInterfaceRequester.getInterface();

        // Enable Ethernet tethering and check that it starts.
        mTetheringEventCallback = enableEthernetTethering(iface);

        // There is nothing more we can do on a physical interface without connecting an actual
        // client, which is not possible in this test.
    }

    private static final class MyTetheringEventCallback implements TetheringEventCallback {
        private final TetheringManager mTm;
        private final CountDownLatch mTetheringStartedLatch = new CountDownLatch(1);
        private final CountDownLatch mTetheringStoppedLatch = new CountDownLatch(1);
        private final CountDownLatch mLocalOnlyStartedLatch = new CountDownLatch(1);
        private final CountDownLatch mLocalOnlyStoppedLatch = new CountDownLatch(1);
        private final CountDownLatch mClientConnectedLatch = new CountDownLatch(1);
        private final CountDownLatch mUpstreamConnectedLatch = new CountDownLatch(1);
        private final TetheringInterface mIface;

        private volatile boolean mInterfaceWasTethered = false;
        private volatile boolean mInterfaceWasLocalOnly = false;
        private volatile boolean mUnregistered = false;
        private volatile Collection<TetheredClient> mClients = null;
        private volatile Network mUpstream = null;

        MyTetheringEventCallback(TetheringManager tm, String iface) {
            mTm = tm;
            mIface = new TetheringInterface(TETHERING_ETHERNET, iface);
        }

        public void unregister() {
            mTm.unregisterTetheringEventCallback(this);
            mUnregistered = true;
        }
        @Override
        public void onTetheredInterfacesChanged(List<String> interfaces) {
            fail("Should only call callback that takes a Set<TetheringInterface>");
        }

        @Override
        public void onTetheredInterfacesChanged(Set<TetheringInterface> interfaces) {
            // Ignore stale callbacks registered by previous test cases.
            if (mUnregistered) return;

            if (!mInterfaceWasTethered && interfaces.contains(mIface)) {
                // This interface is being tethered for the first time.
                Log.d(TAG, "Tethering started: " + interfaces);
                mInterfaceWasTethered = true;
                mTetheringStartedLatch.countDown();
            } else if (mInterfaceWasTethered && !interfaces.contains(mIface)) {
                Log.d(TAG, "Tethering stopped: " + interfaces);
                mTetheringStoppedLatch.countDown();
            }
        }

        @Override
        public void onLocalOnlyInterfacesChanged(List<String> interfaces) {
            fail("Should only call callback that takes a Set<TetheringInterface>");
        }

        @Override
        public void onLocalOnlyInterfacesChanged(Set<TetheringInterface> interfaces) {
            // Ignore stale callbacks registered by previous test cases.
            if (mUnregistered) return;

            if (!mInterfaceWasLocalOnly && interfaces.contains(mIface)) {
                // This interface is being put into local-only mode for the first time.
                Log.d(TAG, "Local-only started: " + interfaces);
                mInterfaceWasLocalOnly = true;
                mLocalOnlyStartedLatch.countDown();
            } else if (mInterfaceWasLocalOnly && !interfaces.contains(mIface)) {
                Log.d(TAG, "Local-only stopped: " + interfaces);
                mLocalOnlyStoppedLatch.countDown();
            }
        }

        public void awaitInterfaceTethered() throws Exception {
            assertTrue("Ethernet not tethered after " + TIMEOUT_MS + "ms",
                    mTetheringStartedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        }

        public void awaitInterfaceLocalOnly() throws Exception {
            assertTrue("Ethernet not local-only after " + TIMEOUT_MS + "ms",
                    mLocalOnlyStartedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        }

        public void awaitInterfaceUntethered() throws Exception {
            // Don't block teardown if the interface was never tethered.
            // This is racy because the interface might become tethered right after this check, but
            // that can only happen in tearDown if startTethering timed out, which likely means
            // the test has already failed.
            if (!mInterfaceWasTethered && !mInterfaceWasLocalOnly) return;

            if (mInterfaceWasTethered) {
                assertTrue(mIface + " not untethered after " + TIMEOUT_MS + "ms",
                        mTetheringStoppedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            } else if (mInterfaceWasLocalOnly) {
                assertTrue(mIface + " not untethered after " + TIMEOUT_MS + "ms",
                        mLocalOnlyStoppedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            } else {
                fail(mIface + " cannot be both tethered and local-only. Update this test class.");
            }
        }

        @Override
        public void onError(String ifName, int error) {
            // Ignore stale callbacks registered by previous test cases.
            if (mUnregistered) return;

            fail("TetheringEventCallback got error:" + error + " on iface " + ifName);
        }

        @Override
        public void onClientsChanged(Collection<TetheredClient> clients) {
            // Ignore stale callbacks registered by previous test cases.
            if (mUnregistered) return;

            Log.d(TAG, "Got clients changed: " + clients);
            mClients = clients;
            if (clients.size() > 0) {
                mClientConnectedLatch.countDown();
            }
        }

        public Collection<TetheredClient> awaitClientConnected() throws Exception {
            assertTrue("Did not receive client connected callback after " + TIMEOUT_MS + "ms",
                    mClientConnectedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            return mClients;
        }

        @Override
        public void onUpstreamChanged(Network network) {
            // Ignore stale callbacks registered by previous test cases.
            if (mUnregistered) return;

            Log.d(TAG, "Got upstream changed: " + network);
            mUpstream = network;
            if (mUpstream != null) mUpstreamConnectedLatch.countDown();
        }

        public Network awaitFirstUpstreamConnected() throws Exception {
            assertTrue("Did not receive upstream connected callback after " + TIMEOUT_MS + "ms",
                    mUpstreamConnectedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            return mUpstream;
        }
    }

    private MyTetheringEventCallback enableEthernetTethering(String iface,
            TetheringRequest request) throws Exception {
        MyTetheringEventCallback callback = new MyTetheringEventCallback(mTm, iface);
        mTm.registerTetheringEventCallback(mHandler::post, callback);

        StartTetheringCallback startTetheringCallback = new StartTetheringCallback() {
            @Override
            public void onTetheringFailed(int resultCode) {
                fail("Unexpectedly got onTetheringFailed");
            }
        };
        Log.d(TAG, "Starting Ethernet tethering");
        mTm.startTethering(request, mHandler::post /* executor */,  startTetheringCallback);

        final int connectivityType = request.getConnectivityScope();
        switch (connectivityType) {
            case CONNECTIVITY_SCOPE_GLOBAL:
                callback.awaitInterfaceTethered();
                break;
            case CONNECTIVITY_SCOPE_LOCAL:
                callback.awaitInterfaceLocalOnly();
                break;
            default:
                fail("Unexpected connectivity type requested: " + connectivityType);
        }

        return callback;
    }

    private MyTetheringEventCallback enableEthernetTethering(String iface) throws Exception {
        return enableEthernetTethering(iface,
                new TetheringRequest.Builder(TETHERING_ETHERNET)
                .setShouldShowEntitlementUi(false).build());
    }

    private int getMTU(TestNetworkInterface iface) throws SocketException {
        NetworkInterface nif = NetworkInterface.getByName(iface.getInterfaceName());
        assertNotNull("Can't get NetworkInterface object for " + iface.getInterfaceName(), nif);
        return nif.getMTU();
    }

    private TapPacketReader makePacketReader(final TestNetworkInterface iface) throws Exception {
        FileDescriptor fd = iface.getFileDescriptor().getFileDescriptor();
        return makePacketReader(fd, getMTU(iface));
    }

    private TapPacketReader makePacketReader(FileDescriptor fd, int mtu) {
        final TapPacketReader reader = new TapPacketReader(mHandler, fd, mtu);
        mHandler.post(() -> reader.start());
        HandlerUtils.waitForIdle(mHandler, TIMEOUT_MS);
        return reader;
    }

    private void checkVirtualEthernet(TestNetworkInterface iface, int mtu) throws Exception {
        FileDescriptor fd = iface.getFileDescriptor().getFileDescriptor();
        mDownstreamReader = makePacketReader(fd, mtu);
        mTetheringEventCallback = enableEthernetTethering(iface.getInterfaceName());
        checkTetheredClientCallbacks(mDownstreamReader);
    }

    private void checkTetheredClientCallbacks(TapPacketReader packetReader) throws Exception {
        // Create a fake client.
        byte[] clientMacAddr = new byte[6];
        new Random().nextBytes(clientMacAddr);

        TetheringTester tester = new TetheringTester(packetReader);
        DhcpResults dhcpResults = tester.runDhcp(clientMacAddr);

        final Collection<TetheredClient> clients = mTetheringEventCallback.awaitClientConnected();
        assertEquals(1, clients.size());
        final TetheredClient client = clients.iterator().next();

        // Check the MAC address.
        assertEquals(MacAddress.fromBytes(clientMacAddr), client.getMacAddress());
        assertEquals(TETHERING_ETHERNET, client.getTetheringType());

        // Check the hostname.
        assertEquals(1, client.getAddresses().size());
        TetheredClient.AddressInfo info = client.getAddresses().get(0);
        assertEquals(TetheringTester.DHCP_HOSTNAME, info.getHostname());

        // Check the address is the one that was handed out in the DHCP ACK.
        assertLinkAddressMatches(dhcpResults.ipAddress, info.getAddress());

        // Check that the lifetime is correct +/- 10s.
        final long now = SystemClock.elapsedRealtime();
        final long actualLeaseDuration = (info.getAddress().getExpirationTime() - now) / 1000;
        final String msg = String.format("IP address should have lifetime of %d, got %d",
                dhcpResults.leaseDuration, actualLeaseDuration);
        assertTrue(msg, Math.abs(dhcpResults.leaseDuration - actualLeaseDuration) < 10);
    }

    private static final class TetheredInterfaceRequester implements TetheredInterfaceCallback {
        private final Handler mHandler;
        private final EthernetManager mEm;

        private TetheredInterfaceRequest mRequest;
        private final CompletableFuture<String> mFuture = new CompletableFuture<>();

        TetheredInterfaceRequester(Handler handler, EthernetManager em) {
            mHandler = handler;
            mEm = em;
        }

        @Override
        public void onAvailable(String iface) {
            Log.d(TAG, "Ethernet interface available: " + iface);
            mFuture.complete(iface);
        }

        @Override
        public void onUnavailable() {
            mFuture.completeExceptionally(new IllegalStateException("onUnavailable received"));
        }

        public CompletableFuture<String> requestInterface() {
            assertNull("BUG: more than one tethered interface request", mRequest);
            Log.d(TAG, "Requesting tethered interface");
            mRequest = mEm.requestTetheredInterface(mHandler::post, this);
            return mFuture;
        }

        public String getInterface() throws Exception {
            return requestInterface().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        public void release() {
            if (mRequest != null) {
                mFuture.obtrudeException(new IllegalStateException("Request already released"));
                mRequest.release();
                mRequest = null;
            }
        }
    }

    public void assertLinkAddressMatches(LinkAddress l1, LinkAddress l2) {
        // Check all fields except the deprecation and expiry times.
        String msg = String.format("LinkAddresses do not match. expected: %s actual: %s", l1, l2);
        assertTrue(msg, l1.isSameAddressAs(l2));
        assertEquals("LinkAddress flags do not match", l1.getFlags(), l2.getFlags());
        assertEquals("LinkAddress scope does not match", l1.getScope(), l2.getScope());
    }

    private TetheringRequest requestWithStaticIpv4(String local, String client) {
        LinkAddress localAddr = local == null ? null : new LinkAddress(local);
        LinkAddress clientAddr = client == null ? null : new LinkAddress(client);
        return new TetheringRequest.Builder(TETHERING_ETHERNET)
                .setStaticIpv4Addresses(localAddr, clientAddr)
                .setShouldShowEntitlementUi(false).build();
    }

    private void assertInvalidStaticIpv4Request(String iface, String local, String client)
            throws Exception {
        try {
            enableEthernetTethering(iface, requestWithStaticIpv4(local, client));
            fail("Unexpectedly accepted invalid IPv4 configuration: " + local + ", " + client);
        } catch (IllegalArgumentException | NullPointerException expected) { }
    }

    private void assertInterfaceHasIpAddress(String iface, String expected) throws Exception {
        LinkAddress expectedAddr = new LinkAddress(expected);
        NetworkInterface nif = NetworkInterface.getByName(iface);
        for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
            final LinkAddress addr = new LinkAddress(ia.getAddress(), ia.getNetworkPrefixLength());
            if (expectedAddr.equals(addr)) {
                return;
            }
        }
        fail("Expected " + iface + " to have IP address " + expected + ", found "
                + nif.getInterfaceAddresses());
    }

    private TestNetworkInterface createTestInterface() throws Exception {
        TestNetworkManager tnm = mContext.getSystemService(TestNetworkManager.class);
        TestNetworkInterface iface = tnm.createTapInterface();
        Log.d(TAG, "Created test interface " + iface.getInterfaceName());
        return iface;
    }

    private void maybeDeleteTestInterface() throws Exception {
        if (mDownstreamIface != null) {
            mDownstreamIface.getFileDescriptor().close();
            Log.d(TAG, "Deleted test interface " + mDownstreamIface.getInterfaceName());
            mDownstreamIface = null;
        }
    }

    private TestNetworkTracker createTestUpstream(final List<LinkAddress> addresses,
            final List<InetAddress> dnses) throws Exception {
        mTm.setPreferTestNetworks(true);

        final LinkProperties lp = new LinkProperties();
        lp.setLinkAddresses(addresses);
        lp.setDnsServers(dnses);

        return initTestNetwork(mContext, lp, TIMEOUT_MS);
    }

    @Test
    public void testIcmpv6Echo() throws Exception {
        assumeFalse(mEm.isAvailable());

        // MyTetheringEventCallback currently only support await first available upstream. Tethering
        // may select internet network as upstream if test network is not available and not be
        // preferred yet. Create test upstream network before enable tethering.
        mUpstreamTracker = createTestUpstream(toList(TEST_IP4_ADDR, TEST_IP6_ADDR),
                toList(TEST_IP4_DNS, TEST_IP6_DNS));

        mDownstreamIface = createTestInterface();
        mEm.setIncludeTestInterfaces(true);

        final String iface = mTetheredInterfaceRequester.getInterface();
        assertEquals("TetheredInterfaceCallback for unexpected interface",
                mDownstreamIface.getInterfaceName(), iface);

        mTetheringEventCallback = enableEthernetTethering(mDownstreamIface.getInterfaceName());
        assertEquals("onUpstreamChanged for unexpected network", mUpstreamTracker.getNetwork(),
                mTetheringEventCallback.awaitFirstUpstreamConnected());

        mDownstreamReader = makePacketReader(mDownstreamIface);
        mUpstreamReader = makePacketReader(mUpstreamTracker.getTestIface());

        runPing6Test(new TetheringTester(mDownstreamReader), new RemoteResponder(mUpstreamReader));
    }

    private static boolean isIcmpv6Type(byte[] packet, boolean hasEthernetHeader, int type) {
        ByteBuffer buf = ByteBuffer.wrap(packet);
        return Ipv6Utils.isIcmpv6Type(buf, hasEthernetHeader, type);
    }

    private static boolean isIcmpv6EchoRequest(byte[] packet) {
        return isIcmpv6Type(packet, false /* hasEthernetHeader */, ICMPV6_ECHO_REQUEST_TYPE);
    }

    private static boolean isIcmpv6EchoReply(byte[] packet) {
        return isIcmpv6Type(packet, true /* hasEthernetHeader */, ICMPV6_ECHO_REPLY_TYPE);
    }

    private void runPing6Test(TetheringTester tester, RemoteResponder remote) throws Exception {
        TetheredDevice tethered = tester.createTetheredDevice(MacAddress.fromString("1:2:3:4:5:6"));
        Inet6Address remoteIp6Addr = (Inet6Address) parseNumericAddress("2400:222:222::222");
        ByteBuffer request = Ipv6Utils.buildEchoRequestPacket(tethered.macAddr,
                tethered.routerMacAddr, tethered.ipv6Addr, remoteIp6Addr);
        tester.sendPacket(request);

        remote.getNextMatchedPacket((p) -> {
            Log.d(TAG, "Packet in upstream: " + dumpHexString(p));

            return isIcmpv6EchoRequest(p) ? p : null;
        });

        ByteBuffer reply = Ipv6Utils.buildEchoReplyPacket(remoteIp6Addr, tethered.ipv6Addr);
        remote.sendPacket(reply);

        tester.getNextMatchedPacket((p) -> {
            Log.d(TAG, "Packet in downstream: " + dumpHexString(p));

            return isIcmpv6EchoReply(p) ? p : null;
        });
    }

    // Test network topology:
    //
    //         public network (rawip)                 private network
    //                   |                 UE                |
    // +------------+    V    +------------+------------+    V    +------------+
    // |   Sever    +---------+  Upstream  | Downstream +---------+   Client   |
    // +------------+         +------------+------------+         +------------+
    // remote ip              public ip                           private ip
    // 8.8.8.8:443            <Upstream ip>:9876                  <TetheredDevice ip>:9876
    //
    private static final Inet4Address REMOTE_IP4_ADDR =
            (Inet4Address) parseNumericAddress("8.8.8.8");
    private static final short PRIVATE_PORT = 9876;
    private static final short PUBLIC_PORT = 9876;
    private static final short REMOTE_PORT = 433;
    private static final byte TYPE_OF_SERVICE = 0;
    private static final short ID = 27149;
    private static final short ID2 = 27150;
    private static final short FLAGS_AND_FRAGMENT_OFFSET = (short) 0x4000; // flags=DF, offset=0
    private static final byte TIME_TO_LIVE = (byte) 0x40;

    // Assume that the packet has two-byte data.
    private void sendUdpPacketAndCheckData(final TetheringTester src, final RemoteResponder dst,
            final ByteBuffer packet, short expectedData) throws Exception {
        src.sendPacket(packet);

        dst.getNextMatchedPacket((p) -> {
            Log.d(TAG, "Packet in upstream: " + dumpHexString(p));

            ByteBuffer buf = ByteBuffer.wrap(p);
            final Ipv4Header ipv4Header = Struct.parse(Ipv4Header.class, buf);
            if (ipv4Header == null) return null;

            final UdpHeader udpHeader = Struct.parse(UdpHeader.class, buf);
            if (udpHeader == null) return null;

            if (buf.remaining() != Short.BYTES) return null;

            return (buf.getShort() == expectedData) ? p : null;
        });
    }

    // Assume that the packet has two-byte data.
    private void sendUdpPacketAndCheckData(final RemoteResponder src, final TetheringTester dst,
            final ByteBuffer packet, short expectedData) throws Exception {
        src.sendPacket(packet);

        dst.getNextMatchedPacket((p) -> {
            Log.d(TAG, "Packet in downstream: " + dumpHexString(p));

            ByteBuffer buf = ByteBuffer.wrap(p);
            final EthernetHeader etherHeader = Struct.parse(EthernetHeader.class, buf);
            if (etherHeader == null) return null;

            final Ipv4Header ipv4Header = Struct.parse(Ipv4Header.class, buf);
            if (ipv4Header == null) return null;

            final UdpHeader udpHeader = Struct.parse(UdpHeader.class, buf);
            if (udpHeader == null) return null;

            if (buf.remaining() != Short.BYTES) return null;

            return (buf.getShort() == expectedData) ? p : null;
        });
    }

    private ByteBuffer buildUdpv4Packet(@NonNull final MacAddress srcMac,
            @NonNull final MacAddress dstMac, short identification,
            @NonNull final Inet4Address srcIp, @NonNull final Inet4Address dstIp,
            short srcPort, short dstPort, @Nullable final ByteBuffer data) {
        return Ipv4Utils.buildUdpv4Packet(srcMac, dstMac, TYPE_OF_SERVICE, identification,
                FLAGS_AND_FRAGMENT_OFFSET, TIME_TO_LIVE, srcIp, dstIp, srcPort, dstPort, data);
    }

    private ByteBuffer buildUdpv4Packet(short identification,
            @NonNull final Inet4Address srcIp, @NonNull final Inet4Address dstIp,
            short srcPort, short dstPort, @Nullable final ByteBuffer data) {
        return Ipv4Utils.buildUdpv4Packet(TYPE_OF_SERVICE, identification,
                FLAGS_AND_FRAGMENT_OFFSET, TIME_TO_LIVE, srcIp, dstIp, srcPort, dstPort, data);
    }

    private void runUdp4Test(TetheringTester tester, RemoteResponder remote) throws Exception {
        final TetheredDevice tethered = tester.createTetheredDevice(MacAddress.fromString(
                "1:2:3:4:5:6"));

        final ByteBuffer originalData = ByteBuffer.allocate(Short.BYTES).putShort(ID);
        originalData.clear();
        final ByteBuffer originalPacket = buildUdpv4Packet(tethered.macAddr,
                tethered.routerMacAddr, ID, tethered.ipv4Addr /* srcIp */,
                REMOTE_IP4_ADDR /* dstIp */, PRIVATE_PORT /* srcPort */, REMOTE_PORT /*dstPort */,
                originalData /* data */);
        sendUdpPacketAndCheckData(tester, remote, originalPacket, ID /* expectedData */);

        final Inet4Address publicIp4Addr = (Inet4Address) TEST_IP4_ADDR.getAddress();
        final ByteBuffer replyData = ByteBuffer.allocate(Short.BYTES).putShort(ID2);
        replyData.clear();
        final ByteBuffer replyPacket = buildUdpv4Packet(ID2, REMOTE_IP4_ADDR /* srcIp */,
                publicIp4Addr /* dstIp */, REMOTE_PORT /* srcPort */, PUBLIC_PORT /*dstPort */,
                replyData /* data */);
        sendUdpPacketAndCheckData(remote, tester, replyPacket, ID2 /* expectedData */);

        // The BPF coordinator only offloads the ASSURED conntrack entry. The "request + reply"
        // packets can make status IPS_SEEN_REPLY to be set. Need one more packet to make status
        // IPS_ASSURED_BIT to be set.
        // See nf_conntrack_udp_packet in net/netfilter/nf_conntrack_proto_udp.c.
        sendUdpPacketAndCheckData(tester, remote, originalPacket, ID /* expectedData */);

        final ArrayList<Ipv4ForwardingRule> upstreamRules = pollIpv4UpstreamRulesFromDump();
        assertNotNull(upstreamRules);
        assertEquals(1, upstreamRules.size());

        final Ipv4ForwardingRule rule = upstreamRules.get(0);
        assertEquals(IPPROTO_UDP, rule.l4ProtoType);
        assertTrue(Arrays.equals(tethered.ipv4Addr.getAddress(), rule.srcIp.getAddress()));
        assertEquals(PRIVATE_PORT, rule.srcPort);
        assertTrue(Arrays.equals(publicIp4Addr.getAddress(), rule.publicIp.getAddress()));
        assertEquals(PUBLIC_PORT, rule.publicPort);
        assertTrue(Arrays.equals(REMOTE_IP4_ADDR.getAddress(), rule.dstIp.getAddress()));
        assertEquals(REMOTE_PORT, rule.dstPort);
    }

    void initializeTethering() throws Exception {
        assumeFalse(mEm.isAvailable());

        // MyTetheringEventCallback currently only support await first available upstream. Tethering
        // may select internet network as upstream if test network is not available and not be
        // preferred yet. Create test upstream network before enable tethering.
        mUpstreamTracker = createTestUpstream(toList(TEST_IP4_ADDR, TEST_IP6_ADDR),
                toList(TEST_IP4_DNS, TEST_IP6_DNS));

        mDownstreamIface = createTestInterface();
        mEm.setIncludeTestInterfaces(true);

        final String iface = mTetheredInterfaceRequester.getInterface();
        assertEquals("TetheredInterfaceCallback for unexpected interface",
                mDownstreamIface.getInterfaceName(), iface);

        mTetheringEventCallback = enableEthernetTethering(mDownstreamIface.getInterfaceName());
        assertEquals("onUpstreamChanged for unexpected network", mUpstreamTracker.getNetwork(),
                mTetheringEventCallback.awaitFirstUpstreamConnected());

        mDownstreamReader = makePacketReader(mDownstreamIface);
        mUpstreamReader = makePacketReader(mUpstreamTracker.getTestIface());
    }

    @Test
    public void testTetherUdpV4() throws Exception {
        initializeTethering();
        runUdp4Test(new TetheringTester(mDownstreamReader), new RemoteResponder(mUpstreamReader));
    }

    /**
     * A class for parsing the IPv4 forwarding rule from dumpsys string output.
     */
    private static class Ipv4ForwardingRule {
        // IPv4 forwarding rule regex pattern. See BpfCoordinator#dumpIpv4ForwardingRules for the
        // rule dump string format.
        //
        // Format:
        //   l4proto [inDstMac] iif(iifname) srcIp4:srcPort -> oif(oifname) publicIp4:publicPort
        //   -> dstIp4:dstPort [outDstMac] age
        //
        // Example:
        //   udp [ae:0f:db:f7:c6:44] 115(115) 192.168.12.21:9876
        //   -> 114(testtun30) 10.0.0.1:9876 -> 8.8.8.8:433 [00:00:00:00:00:00] -
        //
        // TODO: use restricted regex pattern for {IFINDEX, IFNAME, MAC}.
        private static final String PROTO = "(udp)";
        private static final String IFINDEX = "([0-9]+)";
        private static final String IFNAME = "\\((.*?)\\)"; // (ifname)
        private static final String ADDR4 = "([0-9]{1,3}.[0-9]{1,3}.[0-9]{1,3}.[0-9]{1,3})";
        private static final String PORT = "([0-9]{1,5})";
        private static final String MAC = "\\[(.*?)\\]";  // [mac address]
        private static final int MATCH_GROUP_COUNT = 13;
        private static final int MATCH_GROUP_SRC_ADDR = 5;
        private static final int MATCH_GROUP_SRC_PORT = 6;
        private static final int MATCH_GROUP_PUBLIC_ADDR = 9;
        private static final int MATCH_GROUP_PUBLIC_PORT = 10;
        private static final int MATCH_GROUP_DST_ADDR = 11;
        private static final int MATCH_GROUP_DST_PORT = 12;

        // The lastUsed field (age) is ignored with regex ".*$".
        private static final String IPV4_FORWARDING_RULE_PATTERN =
                "^" + PROTO + " " + MAC + " " + IFINDEX + IFNAME + " "
                + ADDR4 + ":" + PORT + " -> " + IFINDEX + IFNAME + " "
                + ADDR4 + ":" + PORT + " -> "
                + ADDR4 + ":" + PORT + " " + MAC + ".*$";

        public final Inet4Address srcIp;
        public final Inet4Address publicIp;
        public final Inet4Address dstIp;

        public final int srcPort;
        public final int publicPort;
        public final int dstPort;
        public final int l4ProtoType;

        private Ipv4ForwardingRule(int l4ProtoType, Inet4Address srcIp, int srcPort,
                Inet4Address publicIp, int publicPort, Inet4Address dstIp, int dstPort) {
            this.l4ProtoType = l4ProtoType;
            this.srcIp = srcIp;
            this.publicIp = publicIp;
            this.dstIp = dstIp;
            this.srcPort = srcPort;
            this.publicPort = publicPort;
            this.dstPort = dstPort;
        }

        private static boolean isValidPortNumber(int port) {
            return port > 0 && port < 65536;
        }

        @Nullable
        public static Ipv4ForwardingRule parse(@NonNull String s) {
            final Pattern pattern = Pattern.compile(IPV4_FORWARDING_RULE_PATTERN);
            final Matcher matcher = pattern.matcher(s);

            if (matcher.matches()) {
                if (matcher.groupCount() != MATCH_GROUP_COUNT) return null;

                Log.v(TAG, "Pattern found in " + s);
                for (int i = 1; i <= MATCH_GROUP_COUNT; ++i) {
                    Log.v(TAG, "[" + i + "]: " + matcher.group(i));
                }

                // The pattern guarantees tht the L4 protocol is always UDP.
                final int l4ProtoType = IPPROTO_UDP;

                // The regex pattern ADDR4 guarantees the MATCH_GROUP_{SRC, PUBLIC, DST}_ADDR
                // is an IPv4 numeric address string.
                final Inet4Address srcIp = (Inet4Address) parseNumericAddress(
                        matcher.group(MATCH_GROUP_SRC_ADDR));
                final int srcPort = Integer.parseInt(matcher.group(MATCH_GROUP_SRC_PORT));
                if (!isValidPortNumber(srcPort)) return null;

                final Inet4Address publicIp = (Inet4Address) parseNumericAddress(
                        matcher.group(MATCH_GROUP_PUBLIC_ADDR));
                final int publicPort = Integer.parseInt(matcher.group(MATCH_GROUP_PUBLIC_PORT));
                if (!isValidPortNumber(publicPort)) return null;

                final Inet4Address dstIp = (Inet4Address) parseNumericAddress(
                        matcher.group(MATCH_GROUP_DST_ADDR));
                final int dstPort = Integer.parseInt(matcher.group(MATCH_GROUP_DST_PORT));
                if (!isValidPortNumber(dstPort)) return null;

                return new Ipv4ForwardingRule(l4ProtoType, srcIp, srcPort,
                        publicIp, publicPort, dstIp, dstPort);
            } else {
                return null;
            }
        }
    }

    @NonNull
    private ArrayList<Ipv4ForwardingRule> dumpIpv4UpstreamRules() throws Exception {
        boolean tagFound = false;
        final ArrayList<String> rawDumpStrs = executeShellCommand(DUMP_TETHERING_CMD);
        final ArrayList<Ipv4ForwardingRule> rules = new ArrayList<Ipv4ForwardingRule>();

        for (int i = 0; i < rawDumpStrs.size(); i++) {
            final String s = rawDumpStrs.get(i).trim();

            // Find the ipv4 upstream rule title.
            if (!tagFound && s.contains(DUMP_IPV4_UPSTREAM_RULES_TITLE)) {
                tagFound = true;
                continue;
            }
            // Start parsing the rules after the title has found until we can't find the rule
            // anymore. Assumpt that next logging section has another title which can't be
            // parsed by rule regex. Stop the rule parsing either no rule or entering next
            // log section.
            if (tagFound) {
                final Ipv4ForwardingRule r = Ipv4ForwardingRule.parse(s);
                if (r == null) break;
                rules.add(r);
            }
        }
        return rules;
    }

    @Nullable
    private ArrayList<Ipv4ForwardingRule> pollIpv4UpstreamRulesFromDump() throws Exception {
        int retryCount = 0;

        while (true) {
            final ArrayList<Ipv4ForwardingRule> rules = dumpIpv4UpstreamRules();
            if (!rules.isEmpty()) return rules;

            retryCount++;
            if (retryCount > DUMP_POLLING_MAX_RETRY) {
                Log.e(TAG, "cannot get rules after (ms): "
                        + DUMP_POLLING_MAX_RETRY
                        * DUMP_POLLING_INTERVAL_MS);
                return null;
            }
            try {
                Thread.sleep(DUMP_POLLING_INTERVAL_MS);
            } catch (InterruptedException e) {
                Log.e(TAG, "interrupted while waiting for rules");
                return null;
            }
        }
    }

    @NonNull
    private ArrayList<String> executeShellCommand(String pattern, Object...args)
            throws IOException {
        String command = String.format(pattern, args);
        int retryCount = 0;
        while (retryCount++ < SHELL_CMD_MAX_RETRY) {
            try {
                return executeShellCommandInternal(command);
            } catch (InterruptedIOException e) {
                Log.d(TAG, "Executing " + command + " failed; trying again", e);
            }
        }
        throw new IOException("Failed to execute " + command);
    }

    @NonNull
    private ArrayList<String> executeShellCommandInternal(String command) throws IOException {
        Log.d(TAG, "$ " + command);
        final ParcelFileDescriptor pfd = mUiAutomation.executeShellCommand(command.toString());
        BufferedReader br = null;
        try (InputStream in = new FileInputStream(pfd.getFileDescriptor());) {
            br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String str = null;
            ArrayList<String> out = new ArrayList<String>();
            while ((str = br.readLine()) != null) {
                Log.d(TAG, "> " + str);
                out.add(str);
            }
            return out;
        } finally {
            if (br != null) br.close();
        }
    }

    private <T> List<T> toList(T... array) {
        return Arrays.asList(array);
    }
}
