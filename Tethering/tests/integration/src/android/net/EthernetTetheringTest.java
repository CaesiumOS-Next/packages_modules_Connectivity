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
import static android.Manifest.permission.MANAGE_TEST_NETWORKS;
import static android.Manifest.permission.NETWORK_SETTINGS;
import static android.Manifest.permission.TETHER_PRIVILEGED;
import static android.net.TetheringManager.CONNECTIVITY_SCOPE_GLOBAL;
import static android.net.TetheringManager.CONNECTIVITY_SCOPE_LOCAL;
import static android.net.TetheringManager.TETHERING_ETHERNET;
import static android.system.OsConstants.IPPROTO_ICMPV6;
import static android.system.OsConstants.IPPROTO_TCP;

import static com.android.net.module.util.ConnectivityUtils.isIPv6ULA;
import static com.android.net.module.util.NetworkStackConstants.ARP_REPLY;
import static com.android.net.module.util.NetworkStackConstants.ETHER_TYPE_IPV4;
import static com.android.net.module.util.NetworkStackConstants.ETHER_TYPE_IPV6;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ROUTER_ADVERTISEMENT;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.app.UiAutomation;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.EthernetManager.TetheredInterfaceCallback;
import android.net.EthernetManager.TetheredInterfaceRequest;
import android.net.LinkProperties;
import android.net.NetworkAgent;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.TetheringManager.StartTetheringCallback;
import android.net.TetheringManager.TetheringEventCallback;
import android.net.TetheringManager.TetheringRequest;
import android.net.dhcp.DhcpAckPacket;
import android.net.dhcp.DhcpOfferPacket;
import android.net.dhcp.DhcpPacket;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.system.Os;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.networkstack.arp.ArpPacket;
import com.android.net.module.util.IpUtils;
import com.android.net.module.util.Ipv6Utils;
import com.android.net.module.util.NetworkStackConstants;
import com.android.net.module.util.Struct;
import com.android.net.module.util.structs.EthernetHeader;
import com.android.net.module.util.structs.Icmpv6Header;
import com.android.net.module.util.structs.Ipv6Header;
import com.android.testutils.HandlerUtils;
import com.android.testutils.TapPacketReader;
import com.android.testutils.TestableNetworkCallback;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileDescriptor;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static android.net.cts.util.CtsNetUtils.TestNetworkCallback;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class EthernetTetheringTest {

    private static final String TAG = EthernetTetheringTest.class.getSimpleName();
    private static final int TIMEOUT_MS = 5000;
    private static final int PACKET_READ_TIMEOUT_MS = 100;
    private static final int DHCP_DISCOVER_ATTEMPTS = 10;
    private static final byte[] DHCP_REQUESTED_PARAMS = new byte[] {
            DhcpPacket.DHCP_SUBNET_MASK,
            DhcpPacket.DHCP_ROUTER,
            DhcpPacket.DHCP_DNS_SERVER,
            DhcpPacket.DHCP_LEASE_TIME,
    };
    private static final String DHCP_HOSTNAME = "testhostname";

    private final Context mContext = InstrumentationRegistry.getContext();
    private final EthernetManager mEm = mContext.getSystemService(EthernetManager.class);
    private final TetheringManager mTm = mContext.getSystemService(TetheringManager.class);

    private TestNetworkInterface mTestIface;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private TapPacketReader mTapPacketReader;

    private TetheredInterfaceRequester mTetheredInterfaceRequester;
    private MyTetheringEventCallback mTetheringEventCallback;

    private UiAutomation mUiAutomation =
            InstrumentationRegistry.getInstrumentation().getUiAutomation();
    private boolean mRunTests;

    @Before
    public void setUp() throws Exception {
        // Needed to create a TestNetworkInterface, to call requestTetheredInterface, and to receive
        // tethered client callbacks.
        mUiAutomation.adoptShellPermissionIdentity(
                MANAGE_TEST_NETWORKS, NETWORK_SETTINGS, TETHER_PRIVILEGED, ACCESS_NETWORK_STATE);
        mRunTests = mTm.isTetheringSupported() && mEm != null;
        assumeTrue(mRunTests);

        mHandlerThread = new HandlerThread(getClass().getSimpleName());
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mTetheredInterfaceRequester = new TetheredInterfaceRequester(mHandler, mEm);
    }

    private void cleanUp() throws Exception {
        mTm.stopTethering(TETHERING_ETHERNET);
        if (mTetheringEventCallback != null) {
            mTetheringEventCallback.awaitInterfaceUntethered();
            mTetheringEventCallback.unregister();
            mTetheringEventCallback = null;
        }
        if (mTapPacketReader != null) {
            TapPacketReader reader = mTapPacketReader;
            mHandler.post(() -> reader.stop());
            mTapPacketReader = null;
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

        mTestIface = createTestInterface();
        // This must be done now because as soon as setIncludeTestInterfaces(true) is called, the
        // interface will be placed in client mode, which will delete the link-local address.
        // At that point NetworkInterface.getByName() will cease to work on the interface, because
        // starting in R NetworkInterface can no longer see interfaces without IP addresses.
        int mtu = getMTU(mTestIface);

        Log.d(TAG, "Including test interfaces");
        mEm.setIncludeTestInterfaces(true);

        final String iface = mTetheredInterfaceRequester.getInterface();
        assertEquals("TetheredInterfaceCallback for unexpected interface",
                mTestIface.getInterfaceName(), iface);

        checkVirtualEthernet(mTestIface, mtu);
    }

    @Test
    public void testVirtualEthernet() throws Exception {
        // This test requires manipulating packets. Skip if there is a physical Ethernet connected.
        assumeFalse(mEm.isAvailable());

        CompletableFuture<String> futureIface = mTetheredInterfaceRequester.requestInterface();

        mEm.setIncludeTestInterfaces(true);

        mTestIface = createTestInterface();

        final String iface = futureIface.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertEquals("TetheredInterfaceCallback for unexpected interface",
                mTestIface.getInterfaceName(), iface);

        checkVirtualEthernet(mTestIface, getMTU(mTestIface));
    }

    @Test
    public void testStaticIpv4() throws Exception {
        assumeFalse(mEm.isAvailable());

        mEm.setIncludeTestInterfaces(true);

        mTestIface = createTestInterface();

        final String iface = mTetheredInterfaceRequester.getInterface();
        assertEquals("TetheredInterfaceCallback for unexpected interface",
                mTestIface.getInterfaceName(), iface);

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

        FileDescriptor fd = mTestIface.getFileDescriptor().getFileDescriptor();
        mTapPacketReader = makePacketReader(fd, getMTU(mTestIface));
        DhcpResults dhcpResults = runDhcp(fd, client1);
        assertEquals(new LinkAddress(clientAddr), dhcpResults.ipAddress);

        try {
            runDhcp(fd, client2);
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
            if (isRouterAdvertisement(pkt)) {
                Log.d(TAG, "RA: " + byteArrayToHex(pkt));
                return;
            }
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

        mTestIface = createTestInterface();

        final String iface = mTetheredInterfaceRequester.getInterface();
        assertEquals("TetheredInterfaceCallback for unexpected interface",
                mTestIface.getInterfaceName(), iface);

        final TetheringRequest request = new TetheringRequest.Builder(TETHERING_ETHERNET)
                .setConnectivityScope(CONNECTIVITY_SCOPE_LOCAL).build();
        mTetheringEventCallback = enableEthernetTethering(iface, request);
        mTetheringEventCallback.awaitInterfaceLocalOnly();

        // makePacketReader only works after tethering is started, because until then the interface
        // does not have an IP address, and unprivileged apps cannot see interfaces without IP
        // addresses. This shouldn't be flaky because the TAP interface will buffer all packets even
        // before the reader is started.
        FileDescriptor fd = mTestIface.getFileDescriptor().getFileDescriptor();
        mTapPacketReader = makePacketReader(fd, getMTU(mTestIface));

        expectRouterAdvertisement(mTapPacketReader, iface, 2000 /* timeoutMs */);
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
        private final TetheringInterface mIface;

        private volatile boolean mInterfaceWasTethered = false;
        private volatile boolean mInterfaceWasLocalOnly = false;
        private volatile boolean mUnregistered = false;
        private volatile Collection<TetheredClient> mClients = null;

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

    private TapPacketReader makePacketReader(FileDescriptor fd, int mtu) {
        final TapPacketReader reader = new TapPacketReader(mHandler, fd, mtu);
        mHandler.post(() -> reader.start());
        HandlerUtils.waitForIdle(mHandler, TIMEOUT_MS);
        return reader;
    }

    private void checkVirtualEthernet(TestNetworkInterface iface, int mtu) throws Exception {
        FileDescriptor fd = iface.getFileDescriptor().getFileDescriptor();
        mTapPacketReader = makePacketReader(fd, mtu);
        mTetheringEventCallback = enableEthernetTethering(iface.getInterfaceName());
        checkTetheredClientCallbacks(fd);
    }

    private DhcpResults runDhcp(FileDescriptor fd, byte[] clientMacAddr) throws Exception {
        // We have to retransmit DHCP requests because IpServer declares itself to be ready before
        // its DhcpServer is actually started. TODO: fix this race and remove this loop.
        DhcpPacket offerPacket = null;
        for (int i = 0; i < DHCP_DISCOVER_ATTEMPTS; i++) {
            Log.d(TAG, "Sending DHCP discover");
            sendDhcpDiscover(fd, clientMacAddr);
            offerPacket = getNextDhcpPacket();
            if (offerPacket instanceof DhcpOfferPacket) break;
        }
        if (!(offerPacket instanceof DhcpOfferPacket)) {
            throw new TimeoutException("No DHCPOFFER received on interface within timeout");
        }

        sendDhcpRequest(fd, offerPacket, clientMacAddr);
        DhcpPacket ackPacket = getNextDhcpPacket();
        if (!(ackPacket instanceof DhcpAckPacket)) {
            throw new TimeoutException("No DHCPACK received on interface within timeout");
        }

        return ackPacket.toDhcpResults();
    }

    private void checkTetheredClientCallbacks(FileDescriptor fd) throws Exception {
        // Create a fake client.
        byte[] clientMacAddr = new byte[6];
        new Random().nextBytes(clientMacAddr);

        DhcpResults dhcpResults = runDhcp(fd, clientMacAddr);

        final Collection<TetheredClient> clients = mTetheringEventCallback.awaitClientConnected();
        assertEquals(1, clients.size());
        final TetheredClient client = clients.iterator().next();

        // Check the MAC address.
        assertEquals(MacAddress.fromBytes(clientMacAddr), client.getMacAddress());
        assertEquals(TETHERING_ETHERNET, client.getTetheringType());

        // Check the hostname.
        assertEquals(1, client.getAddresses().size());
        TetheredClient.AddressInfo info = client.getAddresses().get(0);
        assertEquals(DHCP_HOSTNAME, info.getHostname());

        // Check the address is the one that was handed out in the DHCP ACK.
        assertLinkAddressMatches(dhcpResults.ipAddress, info.getAddress());

        // Check that the lifetime is correct +/- 10s.
        final long now = SystemClock.elapsedRealtime();
        final long actualLeaseDuration = (info.getAddress().getExpirationTime() - now) / 1000;
        final String msg = String.format("IP address should have lifetime of %d, got %d",
                dhcpResults.leaseDuration, actualLeaseDuration);
        assertTrue(msg, Math.abs(dhcpResults.leaseDuration - actualLeaseDuration) < 10);
    }

    private DhcpPacket getNextDhcpPacket() throws ParseException {
        byte[] packet;
        while ((packet = mTapPacketReader.popPacket(PACKET_READ_TIMEOUT_MS)) != null) {
            try {
                return DhcpPacket.decodeFullPacket(packet, packet.length, DhcpPacket.ENCAP_L2);
            } catch (DhcpPacket.ParseException e) {
                // Not a DHCP packet. Continue.
            }
        }
        return null;
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

    private void sendDhcpDiscover(FileDescriptor fd, byte[] macAddress) throws Exception {
        ByteBuffer packet = DhcpPacket.buildDiscoverPacket(DhcpPacket.ENCAP_L2,
                new Random().nextInt() /* transactionId */, (short) 0 /* secs */,
                macAddress,  false /* unicast */, DHCP_REQUESTED_PARAMS,
                false /* rapid commit */,  DHCP_HOSTNAME);
        sendPacket(fd, packet);
    }

    private void sendDhcpRequest(FileDescriptor fd, DhcpPacket offerPacket, byte[] macAddress)
            throws Exception {
        DhcpResults results = offerPacket.toDhcpResults();
        Inet4Address clientIp = (Inet4Address) results.ipAddress.getAddress();
        Inet4Address serverIdentifier = results.serverAddress;
        ByteBuffer packet = DhcpPacket.buildRequestPacket(DhcpPacket.ENCAP_L2,
                0 /* transactionId */, (short) 0 /* secs */, DhcpPacket.INADDR_ANY /* clientIp */,
                false /* broadcast */, macAddress, clientIp /* requestedIpAddress */,
                serverIdentifier, DHCP_REQUESTED_PARAMS, DHCP_HOSTNAME);
        sendPacket(fd, packet);
    }

    private void sendPacket(FileDescriptor fd, ByteBuffer packet) throws Exception {
        assertNotNull("Only tests on virtual interfaces can send packets", fd);
        Os.write(fd, packet);
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
        if (mTestIface != null) {
            mTestIface.getFileDescriptor().close();
            Log.d(TAG, "Deleted test interface " + mTestIface.getInterfaceName());
            mTestIface = null;
        }
    }

    static final byte[] TEST_DATA = "Best test data ever!".getBytes();

    @Test(timeout=15000)
    public void testUpstream() throws Exception {
        CompletableFuture<String> futureIface = mTetheredInterfaceRequester.requestInterface();

        mEm.setIncludeTestInterfaces(true);

        mTestIface = createTestInterface();

        final String iface = futureIface.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertEquals("TetheredInterfaceCallback for unexpected interface",
                mTestIface.getInterfaceName(), iface);

        FileDescriptor fd = mTestIface.getFileDescriptor().getFileDescriptor();
        mTapPacketReader = makePacketReader(fd, getMTU(mTestIface));
        mTetheringEventCallback = enableEthernetTethering(mTestIface.getInterfaceName());

        // Create a fake client.
        byte[] clientMacAddr = new byte[6];
        new Random().nextBytes(clientMacAddr);

        DhcpResults dhcpResults = runDhcp(fd, clientMacAddr);

        final Collection<TetheredClient> clients = mTetheringEventCallback.awaitClientConnected();
        assertEquals(1, clients.size());
        final TetheredClient client = clients.iterator().next();

        // Check the MAC address.
        assertEquals(MacAddress.fromBytes(clientMacAddr), client.getMacAddress());
        assertEquals(TETHERING_ETHERNET, client.getTetheringType());

        // Check the hostname.
        assertEquals(1, client.getAddresses().size());
        TetheredClient.AddressInfo info = client.getAddresses().get(0);
        assertEquals(DHCP_HOSTNAME, info.getHostname());

        // Check the address is the one that was handed out in the DHCP ACK.
        assertLinkAddressMatches(dhcpResults.ipAddress, info.getAddress());

        final NetworkCapabilities nc = new NetworkCapabilities.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_TEST)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build();

        //final TestableNetworkCallback callback = new TestableNetworkCallback();
        final TestNetworkCallback callback = new TestNetworkCallback();
        final NetworkRequest request = new NetworkRequest.Builder()
            .clearCapabilities().build();

        ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
        cm.registerNetworkCallback(request, callback);

        TestNetworkManager tnm = mContext.getSystemService(TestNetworkManager.class);
        ArrayList<LinkAddress> list = new ArrayList<>();
        list.add(new LinkAddress("10.0.0.1/8"));
        list.add(new LinkAddress("2001:db8:1::101/64"));
        TestNetworkInterface upstream = tnm.createTunInterface(list);
        Log.d(TAG, "Created test upstream interface " + upstream.getInterfaceName());
        FileDescriptor ufd = upstream.getFileDescriptor().getFileDescriptor();
        TapPacketReader upstreamReader = makePacketReader(ufd, getMTU(upstream));

        // Get interface name from TestNetworkInterface.
        final LinkProperties lp = new LinkProperties();
        lp.setInterfaceName(upstream.getInterfaceName());
        lp.addLinkAddress(list.get(0));
        lp.addLinkAddress(list.get(1));
        //lp.addRoute(new RouteInfo(new IpPrefix(NetworkStackConstants.IPV6_ADDR_ANY, 0), null, upstream.getInterfaceName(), RouteInfo.RTN_UNICAST, 1280));
        lp.addRoute(new RouteInfo(null, InetAddress.getByName("fe80::38ef:4aff:fe72:3df2"), upstream.getInterfaceName(), RouteInfo.RTN_UNICAST, 1280));
        lp.addRoute(new RouteInfo(new IpPrefix(NetworkStackConstants.IPV4_ADDR_ANY, 0), null, upstream.getInterfaceName(), RouteInfo.RTN_UNICAST, 1280));
        lp.addDnsServer(InetAddress.getByName("8.8.8.8"));
        lp.addDnsServer(InetAddress.getByName("2001:db8:1::888"));
        lp.setMtu(1280);

        Log.d(TAG, "register test network agent");
        final TestNetworkAgent na = new TestNetworkAgent(mContext, mHandlerThread.getLooper(), nc, lp);
        na.setLingerDuration(Duration.ofMinutes(30));

        final Network network = callback.waitForAvailable();
        assertNotNull("Did not receive onAvailable for request", network);


        //Thread.sleep(20000);client
        Log.d(TAG, "network: " + network);
        Log.d(TAG, "client: " + client);

        Log.d(TAG, "build a socket and send from upstream network");
        final DatagramSocket socket = new DatagramSocket(0, InetAddress.getByName("2001:db8:1::101"));
        socket.setSoTimeout(500);
        network.bindSocket(socket);
        socket.send(new DatagramPacket(TEST_DATA, TEST_DATA.length, InetAddress.getByName("2400:222:222::222"), socket.getLocalPort()));

        //for (int i =0; i< 100; i++) {
        //    Log.d(TAG, "start forwarding test");
        //    //runIpv6Test(fd, MacAddress.fromBytes(clientMacAddr), upstreamReader);
        //    runArpTest(fd, MacAddress.fromBytes(clientMacAddr), upstreamReader);
        //    Thread.sleep(1000);
        //}
        Log.d(TAG, "reply arp");
        assertTrue(replyArp(MacAddress.fromBytes(clientMacAddr)));

        Log.d(TAG, "waiting tcp test");
        runTcpHandShackTest(MacAddress.fromBytes(clientMacAddr),
                MacAddress.fromBytes(NetworkInterface.getByName(mTestIface.getInterfaceName()).getHardwareAddress()),
                (Inet4Address) client.getAddresses().get(0).getAddress().getAddress(),
                (Inet4Address) InetAddress.getByName("10.0.0.1"), upstreamReader);

        na.unregister();
        mHandler.post(() -> upstreamReader.stop());
        upstream.getFileDescriptor().close();
        Log.d(TAG, "Deleted upstream interface " + upstream.getInterfaceName());

    }

    // NetworkAgent is abstract, so a subclass is necessary
    private static class TestNetworkAgent extends NetworkAgent {
        TestNetworkAgent(Context context, Looper looper,
            NetworkCapabilities nc, LinkProperties lp) {
            super(context, looper, "TetheringTest", nc, lp, 100
                    , new NetworkAgentConfig.Builder().build(),
                    new NetworkProvider(context, looper, "ScoreReportTest agent"));
            register();
            markConnected();
        }
        //@Override protected void unwanted() { }
    }

    private boolean replyArp(final MacAddress srcMac) throws Exception {
        byte[] packet;

        // TODO: Use ReadHeads as IpClientIntegrationTestCommon.
        for (int repeatCount = 0; repeatCount < 50; repeatCount++) {
            while ((packet = mTapPacketReader.popPacket(PACKET_READ_TIMEOUT_MS)) != null) {
                //Log.d(TAG, "[downstream] Packet in downstream, length: " + packet.length);
                //Log.d(TAG, "[downstream] payLoad: " + byteArrayToHex(packet));
                try {
                    final ArpPacket arp = ArpPacket.parseArpPacket(packet, packet.length);
                    Log.d(TAG, "[downstream] is arp: opCode: " + arp.opCode
                        + ", senderIp" + arp.senderIp
                        + ", targetIp " + arp.targetIp
                        + ", senderHwAddress " + arp.senderHwAddress
                        + ", targetHwAddress " + arp.targetHwAddress);
                    Log.d(TAG, "[downstream] srcMac: " + srcMac);

                    final ByteBuffer arpReply = ArpPacket.buildArpPacket(arp.senderHwAddress.toByteArray() /* dst */,
                            srcMac.toByteArray() /* srcMac */, arp.senderIp.getAddress() /* target IP */,
                            arp.senderHwAddress.toByteArray() /* target HW address */, arp.targetIp.getAddress() /* sender IP */,
                            (short) ARP_REPLY);
                    mTapPacketReader.sendResponse(arpReply);
                    return true;
                } catch (Exception e) {
                    Log.d(TAG, "[downstream] not arp: " + e);
                }
            }
        }
        return false;
    }

    private static final int IPV4_HEADER_LENGTH = 20;
    private static final int TCP_HEADER_LENGTH = 20;

    private static byte[] buildV4Packet(final MacAddress srcMac, final MacAddress dstMac,
            final Inet4Address srcIp, final Inet4Address dstIp, int seq, int ack) {
        final int etherHeaderLen = Struct.getSize(EthernetHeader.class);
        final int length = etherHeaderLen + IPV4_HEADER_LENGTH + TCP_HEADER_LENGTH;
        final ByteBuffer buf = ByteBuffer.allocate(length);
        buf.order(ByteOrder.BIG_ENDIAN);

        final EthernetHeader ethHeader =
                new EthernetHeader(dstMac, srcMac, (short) ETHER_TYPE_IPV4);
        ethHeader.writeToByteBuffer(buf);

        buf.put((byte) 0x45);                       // IP version and IHL
        buf.put((byte) 0x00 /* routine */);         // TOS
        buf.putShort((short) (IPV4_HEADER_LENGTH + TCP_HEADER_LENGTH));
        buf.putInt(0xde214000);                     // ID, flags=DF, offset
        buf.put((byte) 0x40);                       // TTL
        buf.put((byte) IPPROTO_TCP);
        final int ipChecksumOffset = buf.position();
        buf.putShort((short) 0);                    // IP checksum
        buf.put(srcIp.getAddress());
        buf.put(dstIp.getAddress());
        buf.putShort((short) 12345 /* srcPort */);
        buf.putShort((short) 54321 /* dstPort */);
        buf.putInt(seq);                 // Sequence Number
        buf.putInt(ack);                 // ACK
        buf.putShort((short) 0x5002);    // TCP length=5, flags=SYN
        buf.putShort((short) 0xffff);    // Window size
        final int tcpChecksumOffset = buf.position();
        buf.putShort((short) 0);                    // TCP checksum
        // URG is not set therefore the urgent pointer is zero.
        buf.putShort((short) 0);                    // Urgent pointer

        buf.putShort(ipChecksumOffset, IpUtils.ipChecksum(buf, etherHeaderLen));
        buf.putShort(tcpChecksumOffset, IpUtils.tcpChecksum(
                buf, etherHeaderLen, etherHeaderLen + IPV4_HEADER_LENGTH, TCP_HEADER_LENGTH));

        return buf.array();
    }

    private void runTcpHandShackTest(final MacAddress srcMac, final MacAddress dstMac,
            final Inet4Address srcIp, final Inet4Address dstIp, TapPacketReader upstreamReader) throws Exception {
        final TapPacketReader downstreamReader = mTapPacketReader;

        final byte[] synPacket = buildV4Packet(srcMac, dstMac, srcIp, dstIp, 1234 /* seq */, 0 /* ack */);
        Log.d(TAG, "[runTcpHandShackTest] synPacket: " + byteArrayToHex(synPacket));

        final ByteBuffer byteBuffer = ByteBuffer.wrap(synPacket);
        downstreamReader.sendResponse(byteBuffer);

        byte[] packet;
        while ((packet = upstreamReader.popPacket(PACKET_READ_TIMEOUT_MS)) != null) {
            Log.d(TAG, "[upstream] Packet in upstream, length: " + packet.length);
            Log.d(TAG, "[upstream] payLoad: " + byteArrayToHex(packet));
        }
    }


    private void runArpTest(FileDescriptor fd, final MacAddress srcMac, TapPacketReader reader) throws Exception {
        //Inet6Address srcAddr = (Inet6Address) InetAddress.getByName("2001:db8:1::123");
        //Inet6Address dstAddr = (Inet6Address) InetAddress.getByName("2400:222:222::222");
        //ByteBuffer echo = Ipv6Utils.buildEchoRequestPacket(srcMac, MacAddress.fromBytes(NetworkInterface.getByName(mTestIface.getInterfaceName()).getHardwareAddress()), srcAddr, dstAddr);



        Log.d(TAG, "waiting arp");
        //sendPacket(fd, arpReply);

        byte[] packet;
        while ((packet = reader.popPacket(PACKET_READ_TIMEOUT_MS)) != null) {
            Log.d(TAG, "[upstream] Packet in upstream, length: " + packet.length);
            Log.d(TAG, "[upstream] payLoad: " + byteArrayToHex(packet));
            try {
                final ArpPacket arp = ArpPacket.parseArpPacket(packet, packet.length);
                Log.d(TAG, "[upstream] is arp: " + arp);
            } catch (Exception e) {
                Log.d(TAG, "[upstream] not arp: " + e);
            }
        }

        while ((packet = mTapPacketReader.popPacket(PACKET_READ_TIMEOUT_MS)) != null) {
            Log.d(TAG, "[downstream] Packet in downstream, length: " + packet.length);
            Log.d(TAG, "[downstream] payLoad: " + byteArrayToHex(packet));
            try {
                final ArpPacket arp = ArpPacket.parseArpPacket(packet, packet.length);
                Log.d(TAG, "[downstream] is arp: opCode: " + arp.opCode
                    + ", senderIp" + arp.senderIp
                    + ", targetIp " + arp.targetIp
                    + ", senderHwAddress " + arp.senderHwAddress
                    + ", targetHwAddress " + arp.targetHwAddress);

                Log.d(TAG, "[downstream] srcMac: " + srcMac);
// 08-12 11:29:46.592 18646 18669 D EthernetTetheringTest: [downstream] is arp: opCode: 1, senderIp/192.168.114.219, targetIp /192.168.114.133, senderHwAddress 92:f9:a0:5d:4d:e9, targetHwAddress 00:00:00:00:00:00

                final ByteBuffer arpReply = ArpPacket.buildArpPacket(arp.senderHwAddress.toByteArray() /* dst */,
                        srcMac.toByteArray() /* srcMac */, arp.senderIp.getAddress() /* target IP */,
                        arp.senderHwAddress.toByteArray() /* target HW address */, arp.targetIp.getAddress() /* sender IP */,
                        (short) 2 /* ARP_REPLY */);
                
                //mTapPacketReader.sendResponse(arpReply);

            } catch (Exception e) {
                Log.d(TAG, "[downstream] not arp: " + e);
            }
        }
    }

    private void runIpv6Test(FileDescriptor fd, final MacAddress srcMac, TapPacketReader reader) throws Exception {
        Inet6Address srcAddr = (Inet6Address) InetAddress.getByName("2001:db8:1::123");
        Inet6Address dstAddr = (Inet6Address) InetAddress.getByName("2400:222:222::222");
        ByteBuffer echo = Ipv6Utils.buildEchoRequestPacket(srcMac, MacAddress.fromBytes(NetworkInterface.getByName(mTestIface.getInterfaceName()).getHardwareAddress()), srcAddr, dstAddr);
        Log.d(TAG, "Sending ICMP6");
        sendPacket(fd, echo);

        byte[] packet;
        while ((packet = reader.popPacket(PACKET_READ_TIMEOUT_MS)) != null) {
            Log.d(TAG, "Packet in upstream, length: " + packet.length);
            Log.d(TAG, "payLoad: " + byteArrayToHex(packet));
        }
    }

    public static String byteArrayToHex(byte[] a) {
        StringBuilder sb = new StringBuilder(a.length * 2);
        for(byte b: a) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
