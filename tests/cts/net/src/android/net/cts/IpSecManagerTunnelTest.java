/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.app.AppOpsManager.OP_MANAGE_IPSEC_TUNNELS;
import static android.net.IpSecManager.UdpEncapsulationSocket;
import static android.net.cts.PacketUtils.AES_CBC_BLK_SIZE;
import static android.net.cts.PacketUtils.AES_CBC_IV_LEN;
import static android.net.cts.PacketUtils.BytePayload;
import static android.net.cts.PacketUtils.EspHeader;
import static android.net.cts.PacketUtils.IP4_HDRLEN;
import static android.net.cts.PacketUtils.IP6_HDRLEN;
import static android.net.cts.PacketUtils.IpHeader;
import static android.net.cts.PacketUtils.UDP_HDRLEN;
import static android.net.cts.PacketUtils.UdpHeader;
import static android.net.cts.PacketUtils.getIpHeader;
import static android.net.cts.util.CtsNetUtils.TestNetworkCallback;
import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.AF_INET6;

import static com.android.compatibility.common.util.PropertyUtil.getVsrApiLevel;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.IpSecAlgorithm;
import android.net.IpSecManager;
import android.net.IpSecManager.IpSecTunnelInterface;
import android.net.IpSecManager.SecurityParameterIndex;
import android.net.IpSecTransform;
import android.net.LinkAddress;
import android.net.Network;
import android.net.TestNetworkInterface;
import android.net.TestNetworkManager;
import android.net.cts.PacketUtils.Payload;
import android.net.cts.util.CtsNetUtils;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.platform.test.annotations.AppModeFull;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;

import dalvik.system.CloseGuard;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;

@RunWith(AndroidJUnit4.class)
@AppModeFull(reason = "MANAGE_TEST_NETWORKS permission can't be granted to instant apps")
public class IpSecManagerTunnelTest extends IpSecBaseTest {
    @Rule public final DevSdkIgnoreRule ignoreRule = new DevSdkIgnoreRule();

    private static final String TAG = IpSecManagerTunnelTest.class.getSimpleName();

    private static final InetAddress LOCAL_OUTER_4 = InetAddress.parseNumericAddress("192.0.2.1");
    private static final InetAddress REMOTE_OUTER_4 = InetAddress.parseNumericAddress("192.0.2.2");
    private static final InetAddress LOCAL_OUTER_6 =
            InetAddress.parseNumericAddress("2001:db8:1::1");
    private static final InetAddress REMOTE_OUTER_6 =
            InetAddress.parseNumericAddress("2001:db8:1::2");

    private static final InetAddress LOCAL_OUTER_4_NEW =
            InetAddress.parseNumericAddress("192.0.2.101");
    private static final InetAddress REMOTE_OUTER_4_NEW =
            InetAddress.parseNumericAddress("192.0.2.102");
    private static final InetAddress LOCAL_OUTER_6_NEW =
            InetAddress.parseNumericAddress("2001:db8:1::101");
    private static final InetAddress REMOTE_OUTER_6_NEW =
            InetAddress.parseNumericAddress("2001:db8:1::102");

    private static final InetAddress LOCAL_INNER_4 =
            InetAddress.parseNumericAddress("198.51.100.1");
    private static final InetAddress REMOTE_INNER_4 =
            InetAddress.parseNumericAddress("198.51.100.2");
    private static final InetAddress LOCAL_INNER_6 =
            InetAddress.parseNumericAddress("2001:db8:2::1");
    private static final InetAddress REMOTE_INNER_6 =
            InetAddress.parseNumericAddress("2001:db8:2::2");

    private static final int IP4_PREFIX_LEN = 32;
    private static final int IP6_PREFIX_LEN = 128;

    private static final int TIMEOUT_MS = 500;

    // Static state to reduce setup/teardown
    private static ConnectivityManager sCM;
    private static TestNetworkManager sTNM;

    private static TunNetworkWrapper sTunWrapper;
    private static TunNetworkWrapper sTunWrapperNew;

    private static Context sContext = InstrumentationRegistry.getContext();
    private static final CtsNetUtils mCtsNetUtils = new CtsNetUtils(sContext);

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity();
        sCM = (ConnectivityManager) sContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        sTNM = (TestNetworkManager) sContext.getSystemService(Context.TEST_NETWORK_SERVICE);

        // Under normal circumstances, the MANAGE_IPSEC_TUNNELS appop would be auto-granted, and
        // a standard permission is insufficient. So we shell out the appop, to give us the
        // right appop permissions.
        mCtsNetUtils.setAppopPrivileged(OP_MANAGE_IPSEC_TUNNELS, true);

        sTunWrapper = new TunNetworkWrapper(LOCAL_OUTER_4, LOCAL_OUTER_6);
        sTunWrapperNew = new TunNetworkWrapper(LOCAL_OUTER_4_NEW, LOCAL_OUTER_6_NEW);
    }

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        // Set to true before every run; some tests flip this.
        mCtsNetUtils.setAppopPrivileged(OP_MANAGE_IPSEC_TUNNELS, true);

        // Clear TunUtils state
        sTunWrapper.utils.reset();
        sTunWrapperNew.utils.reset();
    }

    private static void tearDownTunWrapperIfNotNull(TunNetworkWrapper tunWrapper) throws Exception {
        if (tunWrapper != null) {
            tunWrapper.tearDown();
        }
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception {
        mCtsNetUtils.setAppopPrivileged(OP_MANAGE_IPSEC_TUNNELS, false);

        tearDownTunWrapperIfNotNull(sTunWrapper);
        tearDownTunWrapperIfNotNull(sTunWrapperNew);

        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .dropShellPermissionIdentity();
    }

    private static class TunNetworkWrapper {
        public final ParcelFileDescriptor fd;
        public final TestNetworkCallback networkCallback;
        public final Network network;
        public final TunUtils utils;

        TunNetworkWrapper(InetAddress... addresses) throws Exception {
            final LinkAddress[] linkAddresses = new LinkAddress[addresses.length];
            for (int i = 0; i < linkAddresses.length; i++) {
                InetAddress addr = addresses[i];
                if (addr instanceof Inet4Address) {
                    linkAddresses[i] = new LinkAddress(addr, IP4_PREFIX_LEN);
                } else {
                    linkAddresses[i] = new LinkAddress(addr, IP6_PREFIX_LEN);
                }
            }

            try {
                final TestNetworkInterface testIface = sTNM.createTunInterface(linkAddresses);

                fd = testIface.getFileDescriptor();
                networkCallback = mCtsNetUtils.setupAndGetTestNetwork(testIface.getInterfaceName());
                networkCallback.waitForAvailable();
                network = networkCallback.currentNetwork;
            } catch (Exception e) {
                tearDown();
                throw e;
            }

            utils = new TunUtils(fd);
        }

        public void tearDown() throws Exception {
            if (networkCallback != null) {
                sCM.unregisterNetworkCallback(networkCallback);
            }

            if (network != null) {
                sTNM.teardownTestNetwork(network);
            }

            if (fd != null) {
                fd.close();
            }
        }
    }

    @Test
    public void testSecurityExceptionCreateTunnelInterfaceWithoutAppop() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());

        // Ensure we don't have the appop. Permission is not requested in the Manifest
        mCtsNetUtils.setAppopPrivileged(OP_MANAGE_IPSEC_TUNNELS, false);

        // Security exceptions are thrown regardless of IPv4/IPv6. Just test one
        try {
            mISM.createIpSecTunnelInterface(LOCAL_INNER_6, REMOTE_INNER_6, sTunWrapper.network);
            fail("Did not throw SecurityException for Tunnel creation without appop");
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void testSecurityExceptionBuildTunnelTransformWithoutAppop() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());

        // Ensure we don't have the appop. Permission is not requested in the Manifest
        mCtsNetUtils.setAppopPrivileged(OP_MANAGE_IPSEC_TUNNELS, false);

        // Security exceptions are thrown regardless of IPv4/IPv6. Just test one
        try (IpSecManager.SecurityParameterIndex spi =
                        mISM.allocateSecurityParameterIndex(LOCAL_INNER_4);
                IpSecTransform transform =
                        new IpSecTransform.Builder(sContext)
                                .buildTunnelModeTransform(REMOTE_INNER_4, spi)) {
            fail("Did not throw SecurityException for Transform creation without appop");
        } catch (SecurityException expected) {
        }
    }

    /** Configuration object to group all the parameters for tunnel tests */
    private static class IpSecTunnelTestParams {
        public final Network ipsecNetwork;
        public final IpSecTunnelInterface tunnelIface;
        public final TunUtils tunUtils;
        public final IpSecTransform inTunnelTransform;
        public final IpSecTransform outTunnelTransform;
        public final InetAddress localOuter;
        public final InetAddress remoteOuter;
        public final int seqNum;

        /**
         * Construct IpSecTunnelTestParams
         *
         * @param ipsecNetwork The IPsec Interface based Network for binding sockets on
         * @param tunnelIface The IPsec tunnel interface that will be tested
         * @param tunUtils The utility of the IPsec tunnel interface's underlying TUN network
         * @param inTunnelTransform The inbound tunnel mode transform
         * @param outTunnelTransform The outbound tunnel mode transform
         * @param localOuter The local address of the outer IP packet
         * @param remoteOuter The remote address of the outer IP packet
         * @param seqNum The expected sequence number of the inbound packet
         */
        IpSecTunnelTestParams(
                Network ipsecNetwork,
                IpSecTunnelInterface tunnelIface,
                TunUtils tunUtils,
                IpSecTransform inTunnelTransform,
                IpSecTransform outTunnelTransform,
                InetAddress localOuter,
                InetAddress remoteOuter,
                int seqNum) {
            this.ipsecNetwork = ipsecNetwork;
            this.tunnelIface = tunnelIface;
            this.tunUtils = tunUtils;
            this.inTunnelTransform = inTunnelTransform;
            this.outTunnelTransform = outTunnelTransform;
            this.localOuter = localOuter;
            this.remoteOuter = remoteOuter;
            this.seqNum = seqNum;
        }
    }

    /* Test runnables for callbacks after IPsec tunnels are set up. */
    private abstract class IpSecTunnelTestRunnable {
        /**
         * Runs the test code, and returns the inner socket port, if any.
         *
         * @param testParams the parameters for running the test
         * @return the integer port of the inner socket if outbound, or 0 if inbound
         *     IpSecTunnelTestRunnable
         * @throws Exception if any part of the test failed.
         */
        public abstract int run(IpSecTunnelTestParams testParams) throws Exception;
    }

    private int getPacketSize(
            int innerFamily, int outerFamily, boolean useEncap, boolean transportInTunnelMode) {
        int expectedPacketSize = TEST_DATA.length + UDP_HDRLEN;

        // Inner Transport mode packet size
        if (transportInTunnelMode) {
            expectedPacketSize =
                    PacketUtils.calculateEspPacketSize(
                            expectedPacketSize,
                            AES_CBC_IV_LEN,
                            AES_CBC_BLK_SIZE,
                            AUTH_KEY.length * 4);
        }

        // Inner IP Header
        expectedPacketSize += innerFamily == AF_INET ? IP4_HDRLEN : IP6_HDRLEN;

        // Tunnel mode transform size
        expectedPacketSize =
                PacketUtils.calculateEspPacketSize(
                        expectedPacketSize, AES_CBC_IV_LEN, AES_CBC_BLK_SIZE, AUTH_KEY.length * 4);

        // UDP encap size
        expectedPacketSize += useEncap ? UDP_HDRLEN : 0;

        // Outer IP Header
        expectedPacketSize += outerFamily == AF_INET ? IP4_HDRLEN : IP6_HDRLEN;

        return expectedPacketSize;
    }

    /** Configuration object for building an IpSecTunnelTestRunnable */
    private class IpSecTunnelTestRunnableConfig implements AutoCloseable {
        // Out, In, Reflect, MigrateTunnel, MigrateTransform
        public final boolean transportInTunnelMode;
        // Out, In, MigrateTunnel, MigrateTransform
        public final int spi;
        // Out, In, Reflect, MigrateTunnel, MigrateTransform
        public final InetAddress localInner;
        // Out, In, MigrateTunnel, MigrateTransform
        public final InetAddress remoteInner;
        // Out, In, Reflect, MigrateTunnel, MigrateTransform
        public final IpSecTransform inTransportTransform;
        // Out, In, Reflect, MigrateTunnel, MigrateTransform
        public final IpSecTransform outTransportTransform;
        // Out, In, MigrateTunnel, MigrateTransform, MigrateTransform
        public final int encapPort;
        // Reflect,
        public final int innerSocketPort;

        private UdpEncapsulationSocket encapSocket = null;

        private final CloseGuard mCloseGuard = CloseGuard.get();
        /**
         * Construct IpSecTunnelTestRunnableConfig
         *
         * @param transportInTunnelMode indicate if there needs to be a transport mode transform
         *     inside the tunnel mode transform
         * @param spi The IPsec SPI
         * @param localInner The local address of the inner IP packet
         * @param remoteInner The remote address of the inner IP packet
         * @param inTransportTransform The inbound transport mode transform
         * @param outTransportTransform The outbound transport mode transform
         * @param encapPort The port of the UDP encapsulation socket
         * @param innerSocketPort The inner socket port
         */
        IpSecTunnelTestRunnableConfig(
                boolean transportInTunnelMode,
                int spi,
                InetAddress localInner,
                InetAddress remoteInner,
                IpSecTransform inTransportTransform,
                IpSecTransform outTransportTransform,
                int encapPort,
                int innerSocketPort) {
            this.transportInTunnelMode = transportInTunnelMode;
            this.spi = spi;
            this.localInner = localInner;
            this.remoteInner = remoteInner;
            this.inTransportTransform = inTransportTransform;
            this.outTransportTransform = outTransportTransform;
            this.encapPort = encapPort;
            this.innerSocketPort = innerSocketPort;
        }

        private IpSecTunnelTestRunnableConfig(
                boolean transportInTunnelMode,
                int spi,
                InetAddress localInner,
                InetAddress remoteInner,
                IpSecTransform inTransportTransform,
                IpSecTransform outTransportTransform,
                int encapPort,
                int innerSocketPort,
                UdpEncapsulationSocket encapSocket) {
            this.transportInTunnelMode = transportInTunnelMode;
            this.spi = spi;
            this.localInner = localInner;
            this.remoteInner = remoteInner;
            this.inTransportTransform = inTransportTransform;
            this.outTransportTransform = outTransportTransform;
            this.encapPort = encapPort;
            this.innerSocketPort = innerSocketPort;
            this.encapSocket = encapSocket;
        }

        IpSecTunnelTestRunnableConfig getCopy(
                InetAddress localInner, InetAddress remoteInner, int innerSocketPort) {
            return new IpSecTunnelTestRunnableConfig(
                    this.transportInTunnelMode,
                    this.spi,
                    localInner,
                    remoteInner,
                    this.inTransportTransform,
                    this.outTransportTransform,
                    this.encapPort,
                    innerSocketPort,
                    this.encapSocket);
        }

        IpSecTunnelTestRunnableConfig(
                InetAddress localInner,
                InetAddress remoteInner,
                int spi,
                boolean useEncap,
                boolean transportInTunnelMode)
                throws Exception {
            this.transportInTunnelMode = transportInTunnelMode;
            this.localInner = localInner;
            this.remoteInner = remoteInner;
            this.spi = spi;

            SecurityParameterIndex inTransportSpi =
                    mISM.allocateSecurityParameterIndex(localInner, spi);
            SecurityParameterIndex outTransportSpi =
                    mISM.allocateSecurityParameterIndex(remoteInner, spi);
            this.inTransportTransform =
                    buildIpSecTransform(sContext, inTransportSpi, null, remoteInner);
            this.outTransportTransform =
                    buildIpSecTransform(sContext, outTransportSpi, null, localInner);
            inTransportSpi.close();
            outTransportSpi.close();

            this.encapSocket = useEncap ? mISM.openUdpEncapsulationSocket() : null;

            this.encapPort = useEncap ? encapSocket.getPort() : 0;
            this.innerSocketPort = 0;

            mCloseGuard.open("close");
        }

        IpSecTunnelTestRunnableConfig(
                int innerFamily, int spi, boolean useEncap, boolean transportInTunnelMode)
                throws Exception {
            this(
                    (innerFamily == AF_INET ? LOCAL_INNER_4 : LOCAL_INNER_6),
                    (innerFamily == AF_INET ? REMOTE_INNER_4 : REMOTE_INNER_6),
                    spi,
                    useEncap,
                    transportInTunnelMode);
        }

        @Override
        public void close() {
            inTransportTransform.close();
            outTransportTransform.close();
        }

        /** Check that the SPI was closed properly. */
        @Override
        protected void finalize() throws Throwable {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }
            close();
        }
    }

    private IpSecTransform buildTunnelModeTransform(
            UdpEncapsulationSocket encapSocket, int spi, InetAddress srcOuter, InetAddress dstOuter)
            throws Exception {
        // Configure tunnel mode Transform parameters
        IpSecTransform.Builder transformBuilder = new IpSecTransform.Builder(sContext);
        transformBuilder.setEncryption(new IpSecAlgorithm(IpSecAlgorithm.CRYPT_AES_CBC, CRYPT_KEY));
        transformBuilder.setAuthentication(
                new IpSecAlgorithm(IpSecAlgorithm.AUTH_HMAC_SHA256, AUTH_KEY, AUTH_KEY.length * 4));
        if (encapSocket != null) {
            transformBuilder.setIpv4Encapsulation(encapSocket, encapSocket.getPort());
        }

        IpSecManager.SecurityParameterIndex ipsecSpi =
                mISM.allocateSecurityParameterIndex(dstOuter, spi);
        IpSecTransform transform = transformBuilder.buildTunnelModeTransform(srcOuter, ipsecSpi);
        ipsecSpi.close();
        return transform;
    }

    private interface IpSecTunnelTestRunnableFactory {
        IpSecTunnelTestRunnable getIpSecTunnelTestRunnable(
                IpSecTunnelTestRunnableConfig runnableConfig) throws Exception;
    }

    private class OutputIpSecTunnelTestRunnableFactory implements IpSecTunnelTestRunnableFactory {
        public IpSecTunnelTestRunnable getIpSecTunnelTestRunnable(
                IpSecTunnelTestRunnableConfig runnableConfig) {
            return new IpSecTunnelTestRunnable() {
                @Override
                public int run(IpSecTunnelTestParams testParams) throws Exception {
                    // Build a socket and send traffic
                    JavaUdpSocket socket = new JavaUdpSocket(runnableConfig.localInner);
                    testParams.ipsecNetwork.bindSocket(socket.mSocket);
                    int innerSocketPort = socket.getPort();

                    // For Transport-In-Tunnel mode, apply transform to socket
                    if (runnableConfig.transportInTunnelMode) {
                        mISM.applyTransportModeTransform(
                                socket.mSocket,
                                IpSecManager.DIRECTION_IN,
                                runnableConfig.inTransportTransform);
                        mISM.applyTransportModeTransform(
                                socket.mSocket,
                                IpSecManager.DIRECTION_OUT,
                                runnableConfig.outTransportTransform);
                    }

                    socket.sendTo(TEST_DATA, runnableConfig.remoteInner, socket.getPort());

                    // Verify that an encrypted packet is sent. As of right now, checking encrypted
                    // body is not possible, due to the test not knowing some of the fields of the
                    // inner IP header (flow label, flags, etc)
                    int innerFamily =
                            runnableConfig.localInner instanceof Inet4Address ? AF_INET : AF_INET6;
                    int outerFamily =
                            testParams.localOuter instanceof Inet4Address ? AF_INET : AF_INET6;
                    boolean useEncap = runnableConfig.encapPort != 0;

                    int expectedPacketSize =
                            getPacketSize(
                                    innerFamily,
                                    outerFamily,
                                    useEncap,
                                    runnableConfig.transportInTunnelMode);

                    testParams.tunUtils.awaitEspPacketNoPlaintext(
                            runnableConfig.spi,
                            testParams.seqNum,
                            TEST_DATA,
                            useEncap,
                            expectedPacketSize);

                    socket.close();

                    return innerSocketPort;
                }
            };
        }
    }

    private class InputReflectedIpSecTunnelTestRunnableFactory
            implements IpSecTunnelTestRunnableFactory {
        public IpSecTunnelTestRunnable getIpSecTunnelTestRunnable(
                IpSecTunnelTestRunnableConfig runnableConfig) throws Exception {
            return new IpSecTunnelTestRunnable() {
                @Override
                public int run(IpSecTunnelTestParams testParams) throws Exception {
                    // Build a socket and receive traffic
                    JavaUdpSocket socket =
                            new JavaUdpSocket(
                                    runnableConfig.localInner, runnableConfig.innerSocketPort);
                    testParams.ipsecNetwork.bindSocket(socket.mSocket);

                    // For Transport-In-Tunnel mode, apply transform to socket
                    if (runnableConfig.transportInTunnelMode) {
                        mISM.applyTransportModeTransform(
                                socket.mSocket,
                                IpSecManager.DIRECTION_IN,
                                runnableConfig.outTransportTransform);
                        mISM.applyTransportModeTransform(
                                socket.mSocket,
                                IpSecManager.DIRECTION_OUT,
                                runnableConfig.inTransportTransform);
                    }

                    testParams.tunUtils.reflectPackets();

                    // Receive packet from socket, and validate that the payload is correct
                    receiveAndValidatePacket(socket);

                    socket.close();

                    return 0;
                }
            };
        }
    }

    private class InputPacketGeneratorIpSecTunnelTestRunnableFactory
            implements IpSecTunnelTestRunnableFactory {
        public IpSecTunnelTestRunnable getIpSecTunnelTestRunnable(
                IpSecTunnelTestRunnableConfig runnableConfig) throws Exception {
            return new IpSecTunnelTestRunnable() {
                @Override
                public int run(IpSecTunnelTestParams testParams) throws Exception {
                    // Build a socket and receive traffic
                    JavaUdpSocket socket = new JavaUdpSocket(runnableConfig.localInner);
                    testParams.ipsecNetwork.bindSocket(socket.mSocket);

                    // For Transport-In-Tunnel mode, apply transform to socket
                    if (runnableConfig.transportInTunnelMode) {
                        mISM.applyTransportModeTransform(
                                socket.mSocket,
                                IpSecManager.DIRECTION_IN,
                                runnableConfig.outTransportTransform);
                        mISM.applyTransportModeTransform(
                                socket.mSocket,
                                IpSecManager.DIRECTION_OUT,
                                runnableConfig.inTransportTransform);
                    }

                    byte[] pkt;
                    if (runnableConfig.transportInTunnelMode) {
                        pkt =
                                getTransportInTunnelModePacket(
                                        runnableConfig.spi,
                                        runnableConfig.spi,
                                        runnableConfig.remoteInner,
                                        runnableConfig.localInner,
                                        testParams.remoteOuter,
                                        testParams.localOuter,
                                        socket.getPort(),
                                        runnableConfig.encapPort,
                                        testParams.seqNum);
                    } else {
                        pkt =
                                getTunnelModePacket(
                                        runnableConfig.spi,
                                        runnableConfig.remoteInner,
                                        runnableConfig.localInner,
                                        testParams.remoteOuter,
                                        testParams.localOuter,
                                        socket.getPort(),
                                        runnableConfig.encapPort,
                                        testParams.seqNum);
                    }
                    testParams.tunUtils.injectPacket(pkt);

                    // Receive packet from socket, and validate
                    receiveAndValidatePacket(socket);

                    socket.close();

                    return 0;
                }
            };
        }
    }

    private class MigrateIpSecTunnelTestRunnableFactory implements IpSecTunnelTestRunnableFactory {
        private final IpSecTunnelTestRunnableFactory mTestRunnableFactory;

        MigrateIpSecTunnelTestRunnableFactory(boolean isOutputTest) {
            if (isOutputTest) {
                mTestRunnableFactory = new OutputIpSecTunnelTestRunnableFactory();
            } else {
                mTestRunnableFactory = new InputPacketGeneratorIpSecTunnelTestRunnableFactory();
            }
        }

        @Override
        public IpSecTunnelTestRunnable getIpSecTunnelTestRunnable(
                IpSecTunnelTestRunnableConfig runnableConfig) {
            return new IpSecTunnelTestRunnable() {
                @Override
                public int run(IpSecTunnelTestParams testParams) throws Exception {
                    mTestRunnableFactory.getIpSecTunnelTestRunnable(runnableConfig).run(testParams);

                    testParams.tunnelIface.setUnderlyingNetwork(sTunWrapperNew.network);

                    // Verify migrating to IPv4 and IPv6 addresses. It ensures that not only
                    // can IPsec tunnel migrate across interfaces, IPsec tunnel can also migrate to
                    // a different address on the same interface.
                    checkMigratedTunnel(
                            runnableConfig.localInner,
                            runnableConfig.remoteInner,
                            LOCAL_OUTER_4_NEW,
                            REMOTE_OUTER_4_NEW,
                            runnableConfig.encapPort != 0,
                            runnableConfig.transportInTunnelMode,
                            sTunWrapperNew.utils,
                            testParams.tunnelIface,
                            testParams.ipsecNetwork);
                    checkMigratedTunnel(
                            runnableConfig.localInner,
                            runnableConfig.remoteInner,
                            LOCAL_OUTER_6_NEW,
                            REMOTE_OUTER_6_NEW,
                            false, // IPv6 does not support UDP encapsulation
                            runnableConfig.transportInTunnelMode,
                            sTunWrapperNew.utils,
                            testParams.tunnelIface,
                            testParams.ipsecNetwork);

                    return 0;
                }
            };
        }

        private void checkMigratedTunnel(
                InetAddress localInner,
                InetAddress remoteInner,
                InetAddress localOuter,
                InetAddress remoteOuter,
                boolean useEncap,
                boolean transportInTunnelMode,
                TunUtils tunUtils,
                IpSecTunnelInterface tunnelIface,
                Network ipsecNetwork)
                throws Exception {
            int spi = getRandomSpi(localOuter, remoteOuter);
            try (IpSecTunnelTestRunnableConfig runnableConfig =
                    new IpSecTunnelTestRunnableConfig(
                            localInner, remoteInner, spi, useEncap, transportInTunnelMode)) {

                // Apply transform and check that traffic is properly encrypted
                try (IpSecTransform inTransform =
                                buildTunnelModeTransform(
                                        runnableConfig.encapSocket,
                                        runnableConfig.spi,
                                        remoteOuter,
                                        localOuter);
                        IpSecTransform outTransform =
                                buildTunnelModeTransform(
                                        runnableConfig.encapSocket,
                                        runnableConfig.spi,
                                        localOuter,
                                        remoteOuter)) {
                    mISM.applyTunnelModeTransform(
                            tunnelIface, IpSecManager.DIRECTION_IN, inTransform);
                    mISM.applyTunnelModeTransform(
                            tunnelIface, IpSecManager.DIRECTION_OUT, outTransform);

                    mTestRunnableFactory
                            .getIpSecTunnelTestRunnable(runnableConfig)
                            .run(
                                    new IpSecTunnelTestParams(
                                            ipsecNetwork,
                                            tunnelIface,
                                            tunUtils,
                                            inTransform,
                                            outTransform,
                                            localOuter,
                                            remoteOuter,
                                            1 /* seqNum */));
                }
            }
        }
    }

    private class MigrateTunnelModeIpSecTransformTestRunnableFactory
            implements IpSecTunnelTestRunnableFactory {
        private final IpSecTunnelTestRunnableFactory mTestRunnableFactory;

        MigrateTunnelModeIpSecTransformTestRunnableFactory(boolean isOutputTest) {
            if (isOutputTest) {
                mTestRunnableFactory = new OutputIpSecTunnelTestRunnableFactory();
            } else {
                mTestRunnableFactory = new InputPacketGeneratorIpSecTunnelTestRunnableFactory();
            }
        }

        @Override
        public IpSecTunnelTestRunnable getIpSecTunnelTestRunnable(
                IpSecTunnelTestRunnableConfig runnableConfig) {
            return new IpSecTunnelTestRunnable() {
                @Override
                public int run(IpSecTunnelTestParams testParams) throws Exception {
                    final IpSecTunnelTestRunnable testRunnable =
                            mTestRunnableFactory.getIpSecTunnelTestRunnable(runnableConfig);
                    testRunnable.run(testParams);

                    testParams.tunnelIface.setUnderlyingNetwork(sTunWrapperNew.network);

                    checkMigrateTunnelModeTransform(
                            testRunnable,
                            testParams.inTunnelTransform,
                            testParams.outTunnelTransform,
                            testParams.tunnelIface,
                            testParams.ipsecNetwork,
                            sTunWrapperNew.utils,
                            LOCAL_OUTER_4_NEW,
                            REMOTE_OUTER_4_NEW,
                            2 /* seqNum */);

                    // Only test migration to IPv6 in non-UDP Encapsulation case
                    if (runnableConfig.encapPort == 0) {
                        checkMigrateTunnelModeTransform(
                                testRunnable,
                                testParams.inTunnelTransform,
                                testParams.outTunnelTransform,
                                testParams.tunnelIface,
                                testParams.ipsecNetwork,
                                sTunWrapperNew.utils,
                                LOCAL_OUTER_6_NEW,
                                REMOTE_OUTER_6_NEW,
                                3 /* seqNum */);
                    }

                    return 0;
                }
            };
        }

        private void checkMigrateTunnelModeTransform(
                IpSecTunnelTestRunnable testRunnable,
                IpSecTransform inTunnelTransform,
                IpSecTransform outTunnelTransform,
                IpSecTunnelInterface tunnelIface,
                Network ipsecNetwork,
                TunUtils tunUtils,
                InetAddress newLocalOuter,
                InetAddress newRemoteOuter,
                int seqNum)
                throws Exception {
            mISM.startTunnelModeTransformMigration(
                    inTunnelTransform, newRemoteOuter, newLocalOuter);
            mISM.startTunnelModeTransformMigration(
                    outTunnelTransform, newLocalOuter, newRemoteOuter);

            mISM.applyTunnelModeTransform(
                    tunnelIface, IpSecManager.DIRECTION_IN, inTunnelTransform);
            mISM.applyTunnelModeTransform(
                    tunnelIface, IpSecManager.DIRECTION_OUT, outTunnelTransform);

            testRunnable.run(
                    new IpSecTunnelTestParams(
                            ipsecNetwork,
                            tunnelIface,
                            tunUtils,
                            inTunnelTransform,
                            outTunnelTransform,
                            newLocalOuter,
                            newRemoteOuter,
                            seqNum));
        }
    }

    private void checkTunnelOutput(
            int innerFamily, int outerFamily, boolean useEncap, boolean transportInTunnelMode)
            throws Exception {
        checkTunnel(
                innerFamily,
                outerFamily,
                useEncap,
                transportInTunnelMode,
                new OutputIpSecTunnelTestRunnableFactory());
    }

    private void checkTunnelInput(
            int innerFamily, int outerFamily, boolean useEncap, boolean transportInTunnelMode)
            throws Exception {
        checkTunnel(
                innerFamily,
                outerFamily,
                useEncap,
                transportInTunnelMode,
                new InputPacketGeneratorIpSecTunnelTestRunnableFactory());
    }

    private void checkMigrateTunnelOutput(
            int innerFamily, int outerFamily, boolean useEncap, boolean transportInTunnelMode)
            throws Exception {
        checkTunnel(
                innerFamily,
                outerFamily,
                useEncap,
                transportInTunnelMode,
                new MigrateIpSecTunnelTestRunnableFactory(true));
    }

    private void checkMigrateTunnelInput(
            int innerFamily, int outerFamily, boolean useEncap, boolean transportInTunnelMode)
            throws Exception {
        checkTunnel(
                innerFamily,
                outerFamily,
                useEncap,
                transportInTunnelMode,
                new MigrateIpSecTunnelTestRunnableFactory(false));
    }

    private void checkMigrateTunnelModeTransformOutput(
            int innerFamily, int outerFamily, boolean useEncap, boolean transportInTunnelMode)
            throws Exception {
        checkTunnel(
                innerFamily,
                outerFamily,
                useEncap,
                transportInTunnelMode,
                new MigrateTunnelModeIpSecTransformTestRunnableFactory(true /* isOutputTest */));
    }

    private void checkMigrateTunnelModeTransformInput(
            int innerFamily, int outerFamily, boolean useEncap, boolean transportInTunnelMode)
            throws Exception {
        checkTunnel(
                innerFamily,
                outerFamily,
                useEncap,
                transportInTunnelMode,
                new MigrateTunnelModeIpSecTransformTestRunnableFactory(false /* isOutputTest */));
    }

    /**
     * Validates that the kernel can talk to itself.
     *
     * <p>This test takes an outbound IPsec packet, reflects it (by flipping IP src/dst), and
     * injects it back into the TUN. This test then verifies that a packet with the correct payload
     * is found on the specified socket/port.
     */
    public void checkTunnelReflected(
            int innerFamily, int outerFamily, boolean useEncap, boolean transportInTunnelMode)
            throws Exception {
        InetAddress localOuter = outerFamily == AF_INET ? LOCAL_OUTER_4 : LOCAL_OUTER_6;
        InetAddress remoteOuter = outerFamily == AF_INET ? REMOTE_OUTER_4 : REMOTE_OUTER_6;
        int spi = getRandomSpi(localOuter, remoteOuter);
        try (IpSecTunnelTestRunnableConfig runnableConfig =
                new IpSecTunnelTestRunnableConfig(
                        innerFamily, spi, useEncap, transportInTunnelMode)) {
            // Run output direction tests
            IpSecTunnelTestRunnable outputIpSecTunnelTestRunnable =
                    new OutputIpSecTunnelTestRunnableFactory()
                            .getIpSecTunnelTestRunnable(runnableConfig);
            int innerSocketPort =
                    buildTunnelNetworkAndRunTests(
                            localOuter, remoteOuter, runnableConfig, outputIpSecTunnelTestRunnable);

            // Input direction tests, with matching inner socket ports.
            final IpSecTunnelTestRunnableConfig inputRunnableConfig =
                    runnableConfig.getCopy(
                            runnableConfig.remoteInner, runnableConfig.localInner, innerSocketPort);
            IpSecTunnelTestRunnable inputIpSecTunnelTestRunnable =
                    new InputReflectedIpSecTunnelTestRunnableFactory()
                            .getIpSecTunnelTestRunnable(inputRunnableConfig);
            buildTunnelNetworkAndRunTests(
                    localOuter, remoteOuter, inputRunnableConfig, inputIpSecTunnelTestRunnable);
        }
    }

    public void checkTunnel(
            int innerFamily,
            int outerFamily,
            boolean useEncap,
            boolean transportInTunnelMode,
            IpSecTunnelTestRunnableFactory factory)
            throws Exception {

        InetAddress localOuter = outerFamily == AF_INET ? LOCAL_OUTER_4 : LOCAL_OUTER_6;
        InetAddress remoteOuter = outerFamily == AF_INET ? REMOTE_OUTER_4 : REMOTE_OUTER_6;

        // Preselect both SPI and encap port, to be used for both inbound and outbound tunnels.
        // Re-uses the same SPI to ensure that even in cases of symmetric SPIs shared across
        // tunnel and transport mode, packets are encrypted/decrypted properly based on the
        // src/dst.
        int spi = getRandomSpi(localOuter, remoteOuter);
        try (IpSecTunnelTestRunnableConfig runnableConfig =
                new IpSecTunnelTestRunnableConfig(
                        innerFamily, spi, useEncap, transportInTunnelMode)) {

            buildTunnelNetworkAndRunTests(
                    localOuter,
                    remoteOuter,
                    runnableConfig,
                    factory.getIpSecTunnelTestRunnable(runnableConfig));
        }
    }

    private int buildTunnelNetworkAndRunTests(
            InetAddress localOuter,
            InetAddress remoteOuter,
            IpSecTunnelTestRunnableConfig runnableConfig,
            IpSecTunnelTestRunnable test)
            throws Exception {
        InetAddress localInner = runnableConfig.localInner;
        int spi = runnableConfig.spi;

        int innerPrefixLen = localInner instanceof Inet6Address ? IP6_PREFIX_LEN : IP4_PREFIX_LEN;
        TestNetworkCallback testNetworkCb = null;
        int innerSocketPort;

        try (IpSecManager.IpSecTunnelInterface tunnelIface =
                mISM.createIpSecTunnelInterface(localOuter, remoteOuter, sTunWrapper.network)) {
            // Build the test network
            tunnelIface.addAddress(localInner, innerPrefixLen);
            testNetworkCb = mCtsNetUtils.setupAndGetTestNetwork(tunnelIface.getInterfaceName());
            testNetworkCb.waitForAvailable();
            Network testNetwork = testNetworkCb.currentNetwork;

            // Check interface was created
            assertNotNull(NetworkInterface.getByName(tunnelIface.getInterfaceName()));

            // Verify address was added
            final NetworkInterface netIface = NetworkInterface.getByInetAddress(localInner);
            assertNotNull(netIface);
            assertEquals(tunnelIface.getInterfaceName(), netIface.getDisplayName());

            // Apply transform and check that traffic is properly encrypted
            try (IpSecTransform inTransform =
                            buildTunnelModeTransform(
                                    runnableConfig.encapSocket,
                                    runnableConfig.spi,
                                    remoteOuter,
                                    localOuter);
                    IpSecTransform outTransform =
                            buildTunnelModeTransform(
                                    runnableConfig.encapSocket,
                                    runnableConfig.spi,
                                    localOuter,
                                    remoteOuter)) {
                mISM.applyTunnelModeTransform(tunnelIface, IpSecManager.DIRECTION_IN, inTransform);
                mISM.applyTunnelModeTransform(
                        tunnelIface, IpSecManager.DIRECTION_OUT, outTransform);

                innerSocketPort =
                        test.run(
                                new IpSecTunnelTestParams(
                                        testNetwork,
                                        tunnelIface,
                                        sTunWrapper.utils,
                                        inTransform,
                                        outTransform,
                                        localOuter,
                                        remoteOuter,
                                        1 /* seqNum */));
            }

            // Teardown the test network
            sTNM.teardownTestNetwork(testNetwork);

            // Remove addresses and check that interface is still present, but fails lookup-by-addr
            tunnelIface.removeAddress(localInner, innerPrefixLen);
            assertNotNull(NetworkInterface.getByName(tunnelIface.getInterfaceName()));
            assertNull(NetworkInterface.getByInetAddress(localInner));

            // Check interface was cleaned up
            tunnelIface.close();
            assertNull(NetworkInterface.getByName(tunnelIface.getInterfaceName()));
        } finally {
            if (testNetworkCb != null) {
                sCM.unregisterNetworkCallback(testNetworkCb);
            }
        }

        return innerSocketPort;
    }

    private static void receiveAndValidatePacket(JavaUdpSocket socket) throws Exception {
        byte[] socketResponseBytes = socket.receive();
        assertArrayEquals(TEST_DATA, socketResponseBytes);
    }

    private int getRandomSpi(InetAddress localOuter, InetAddress remoteOuter) throws Exception {
        // Try to allocate both in and out SPIs using the same requested SPI value.
        try (IpSecManager.SecurityParameterIndex inSpi =
                        mISM.allocateSecurityParameterIndex(localOuter);
                IpSecManager.SecurityParameterIndex outSpi =
                        mISM.allocateSecurityParameterIndex(remoteOuter, inSpi.getSpi()); ) {
            return inSpi.getSpi();
        }
    }

    private EspHeader buildTransportModeEspPacket(
            int spi, int seqNum, InetAddress src, InetAddress dst, Payload payload)
            throws Exception {
        IpHeader preEspIpHeader = getIpHeader(payload.getProtocolId(), src, dst, payload);

        return new EspHeader(
                payload.getProtocolId(),
                spi,
                seqNum,
                CRYPT_KEY, // Same key for auth and crypt
                payload.getPacketBytes(preEspIpHeader));
    }

    private EspHeader buildTunnelModeEspPacket(
            int spi,
            InetAddress srcInner,
            InetAddress dstInner,
            InetAddress srcOuter,
            InetAddress dstOuter,
            int port,
            int encapPort,
            int seqNum,
            Payload payload)
            throws Exception {
        IpHeader innerIp = getIpHeader(payload.getProtocolId(), srcInner, dstInner, payload);
        return new EspHeader(
                innerIp.getProtocolId(),
                spi,
                seqNum, // sequence number
                CRYPT_KEY, // Same key for auth and crypt
                innerIp.getPacketBytes());
    }

    private IpHeader maybeEncapPacket(
            InetAddress src, InetAddress dst, int encapPort, EspHeader espPayload)
            throws Exception {

        Payload payload = espPayload;
        if (encapPort != 0) {
            payload = new UdpHeader(encapPort, encapPort, espPayload);
        }

        return getIpHeader(payload.getProtocolId(), src, dst, payload);
    }

    private byte[] getTunnelModePacket(
            int spi,
            InetAddress srcInner,
            InetAddress dstInner,
            InetAddress srcOuter,
            InetAddress dstOuter,
            int port,
            int encapPort,
            int seqNum)
            throws Exception {
        UdpHeader udp = new UdpHeader(port, port, new BytePayload(TEST_DATA));

        EspHeader espPayload =
                buildTunnelModeEspPacket(
                        spi, srcInner, dstInner, srcOuter, dstOuter, port, encapPort, seqNum, udp);
        return maybeEncapPacket(srcOuter, dstOuter, encapPort, espPayload).getPacketBytes();
    }

    private byte[] getTransportInTunnelModePacket(
            int spiInner,
            int spiOuter,
            InetAddress srcInner,
            InetAddress dstInner,
            InetAddress srcOuter,
            InetAddress dstOuter,
            int port,
            int encapPort,
            int seqNum)
            throws Exception {
        UdpHeader udp = new UdpHeader(port, port, new BytePayload(TEST_DATA));

        EspHeader espPayload =
                buildTransportModeEspPacket(spiInner, seqNum, srcInner, dstInner, udp);
        espPayload =
                buildTunnelModeEspPacket(
                        spiOuter,
                        srcInner,
                        dstInner,
                        srcOuter,
                        dstOuter,
                        port,
                        encapPort,
                        seqNum,
                        espPayload);
        return maybeEncapPacket(srcOuter, dstOuter, encapPort, espPayload).getPacketBytes();
    }

    private void doTestMigrateTunnel(
            int innerFamily, int outerFamily, boolean useEncap, boolean transportInTunnelMode)
            throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());
        checkMigrateTunnelOutput(innerFamily, outerFamily, useEncap, transportInTunnelMode);
        checkMigrateTunnelInput(innerFamily, outerFamily, useEncap, transportInTunnelMode);
    }

    private void doTestMigrateTunnelModeTransform(
            int innerFamily, int outerFamily, boolean useEncap, boolean transportInTunnelMode)
            throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());
        assumeTrue(mCtsNetUtils.hasIpsecTunnelMigrateFeature());
        checkMigrateTunnelModeTransformOutput(
                innerFamily, outerFamily, useEncap, transportInTunnelMode);
        checkMigrateTunnelModeTransformInput(
                innerFamily, outerFamily, useEncap, transportInTunnelMode);
    }

    @IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
    @Test
    public void testHasIpSecTunnelMigrateFeature() throws Exception {
        // FEATURE_IPSEC_TUNNEL_MIGRATION is required when VSR API is U/U+
        if (getVsrApiLevel() > Build.VERSION_CODES.TIRAMISU) {
            assertTrue(mCtsNetUtils.hasIpsecTunnelMigrateFeature());
        }
    }

    // Transport-in-Tunnel mode tests
    @Test
    public void testTransportInTunnelModeV4InV4() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());
        checkTunnelOutput(AF_INET, AF_INET, false, true);
        checkTunnelInput(AF_INET, AF_INET, false, true);
    }

    @IgnoreUpTo(Build.VERSION_CODES.R)
    @Test
    public void testMigrateTransportInTunnelModeV4InV4() throws Exception {
        doTestMigrateTunnel(AF_INET, AF_INET, false, true);
    }

    @IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
    @Test
    public void testMigrateTransformTransportInTunnelModeV4InV4() throws Exception {
        doTestMigrateTunnelModeTransform(AF_INET, AF_INET, false, true);
    }

    @Test
    public void testTransportInTunnelModeV4InV4Reflected() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());
        checkTunnelReflected(AF_INET, AF_INET, false, true);
    }

    @Test
    public void testTransportInTunnelModeV4InV4UdpEncap() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());
        checkTunnelOutput(AF_INET, AF_INET, true, true);
        checkTunnelInput(AF_INET, AF_INET, true, true);
    }

    @IgnoreUpTo(Build.VERSION_CODES.R)
    @Test
    public void testMigrateTransportInTunnelModeV4InV4UdpEncap() throws Exception {
        doTestMigrateTunnel(AF_INET, AF_INET, true, true);
    }

    @IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
    @Test
    public void testMigrateTransformTransportInTunnelModeV4InV4UdpEncap() throws Exception {
        doTestMigrateTunnelModeTransform(AF_INET, AF_INET, true, true);
    }

    @Test
    public void testTransportInTunnelModeV4InV4UdpEncapReflected() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());
        checkTunnelReflected(AF_INET, AF_INET, false, true);
    }

    @Test
    public void testTransportInTunnelModeV4InV6() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());
        checkTunnelOutput(AF_INET, AF_INET6, false, true);
        checkTunnelInput(AF_INET, AF_INET6, false, true);
    }

    @IgnoreUpTo(Build.VERSION_CODES.R)
    @Test
    public void testMigrateTransportInTunnelModeV4InV6() throws Exception {
        doTestMigrateTunnel(AF_INET, AF_INET6, false, true);
    }

    @IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
    @Test
    public void testMigrateTransformTransportInTunnelModeV4InV6() throws Exception {
        doTestMigrateTunnelModeTransform(AF_INET, AF_INET6, false, true);
    }

    @Test
    public void testTransportInTunnelModeV4InV6Reflected() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());
        checkTunnelReflected(AF_INET, AF_INET, false, true);
    }

    @Test
    public void testTransportInTunnelModeV6InV4() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());
        checkTunnelOutput(AF_INET6, AF_INET, false, true);
        checkTunnelInput(AF_INET6, AF_INET, false, true);
    }

    @IgnoreUpTo(Build.VERSION_CODES.R)
    @Test
    public void testMigrateTransportInTunnelModeV6InV4() throws Exception {
        doTestMigrateTunnel(AF_INET6, AF_INET, false, true);
    }

    @IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
    @Test
    public void testMigrateTransformTransportInTunnelModeV6InV4() throws Exception {
        doTestMigrateTunnelModeTransform(AF_INET6, AF_INET, false, true);
    }

    @Test
    public void testTransportInTunnelModeV6InV4Reflected() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());
        checkTunnelReflected(AF_INET, AF_INET, false, true);
    }

    @Test
    public void testTransportInTunnelModeV6InV4UdpEncap() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());
        checkTunnelOutput(AF_INET6, AF_INET, true, true);
        checkTunnelInput(AF_INET6, AF_INET, true, true);
    }

    @IgnoreUpTo(Build.VERSION_CODES.R)
    @Test
    public void testMigrateTransportInTunnelModeV6InV4UdpEncap() throws Exception {
        doTestMigrateTunnel(AF_INET6, AF_INET, true, true);
    }

    @IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
    @Test
    public void testMigrateTransformTransportInTunnelModeV6InV4UdpEncap() throws Exception {
        doTestMigrateTunnelModeTransform(AF_INET6, AF_INET, true, true);
    }

    @Test
    public void testTransportInTunnelModeV6InV4UdpEncapReflected() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());
        checkTunnelReflected(AF_INET, AF_INET, false, true);
    }

    @Test
    public void testTransportInTunnelModeV6InV6() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());
        checkTunnelOutput(AF_INET, AF_INET6, false, true);
        checkTunnelInput(AF_INET, AF_INET6, false, true);
    }

    @IgnoreUpTo(Build.VERSION_CODES.R)
    @Test
    public void testMigrateTransportInTunnelModeV6InV6() throws Exception {
        doTestMigrateTunnel(AF_INET, AF_INET6, false, true);
    }

    @IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
    @Test
    public void testMigrateTransformTransportInTunnelModeV6InV6() throws Exception {
        doTestMigrateTunnelModeTransform(AF_INET, AF_INET6, false, true);
    }

    @Test
    public void testTransportInTunnelModeV6InV6Reflected() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());
        checkTunnelReflected(AF_INET, AF_INET, false, true);
    }

    // Tunnel mode tests
    @Test
    public void testTunnelV4InV4() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());
        checkTunnelOutput(AF_INET, AF_INET, false, false);
        checkTunnelInput(AF_INET, AF_INET, false, false);
    }

    @IgnoreUpTo(Build.VERSION_CODES.R)
    @Test
    public void testMigrateTunnelV4InV4() throws Exception {
        doTestMigrateTunnel(AF_INET, AF_INET, false, false);
    }

    @IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
    @Test
    public void testMigrateTransformTunnelV4InV4() throws Exception {
        doTestMigrateTunnelModeTransform(AF_INET, AF_INET, false, false);
    }

    @Test
    public void testTunnelV4InV4Reflected() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());
        checkTunnelReflected(AF_INET, AF_INET, false, false);
    }

    @Test
    public void testTunnelV4InV4UdpEncap() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());
        checkTunnelOutput(AF_INET, AF_INET, true, false);
        checkTunnelInput(AF_INET, AF_INET, true, false);
    }

    @IgnoreUpTo(Build.VERSION_CODES.R)
    @Test
    public void testMigrateTunnelV4InV4UdpEncap() throws Exception {
        doTestMigrateTunnel(AF_INET, AF_INET, true, false);
    }

    @IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
    @Test
    public void testMigrateTransformTunnelV4InV4UdpEncap() throws Exception {
        doTestMigrateTunnelModeTransform(AF_INET, AF_INET, true, false);
    }

    @Test
    public void testTunnelV4InV4UdpEncapReflected() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());
        checkTunnelReflected(AF_INET, AF_INET, true, false);
    }

    @Test
    public void testTunnelV4InV6() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());
        checkTunnelOutput(AF_INET, AF_INET6, false, false);
        checkTunnelInput(AF_INET, AF_INET6, false, false);
    }

    @IgnoreUpTo(Build.VERSION_CODES.R)
    @Test
    public void testMigrateTunnelV4InV6() throws Exception {
        doTestMigrateTunnel(AF_INET, AF_INET6, false, false);
    }

    @IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
    @Test
    public void testMigrateTransformTunnelV4InV6() throws Exception {
        doTestMigrateTunnelModeTransform(AF_INET, AF_INET6, false, false);
    }

    @Test
    public void testTunnelV4InV6Reflected() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());
        checkTunnelReflected(AF_INET, AF_INET6, false, false);
    }

    @Test
    public void testTunnelV6InV4() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());
        checkTunnelOutput(AF_INET6, AF_INET, false, false);
        checkTunnelInput(AF_INET6, AF_INET, false, false);
    }

    @IgnoreUpTo(Build.VERSION_CODES.R)
    @Test
    public void testMigrateTunnelV6InV4() throws Exception {
        doTestMigrateTunnel(AF_INET6, AF_INET, false, false);
    }

    @IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
    @Test
    public void testMigrateTransformTunnelV6InV4() throws Exception {
        doTestMigrateTunnelModeTransform(AF_INET6, AF_INET, false, false);
    }

    @Test
    public void testTunnelV6InV4Reflected() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());
        checkTunnelReflected(AF_INET6, AF_INET, false, false);
    }

    @Test
    public void testTunnelV6InV4UdpEncap() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());
        checkTunnelOutput(AF_INET6, AF_INET, true, false);
        checkTunnelInput(AF_INET6, AF_INET, true, false);
    }

    @IgnoreUpTo(Build.VERSION_CODES.R)
    @Test
    public void testMigrateTunnelV6InV4UdpEncap() throws Exception {
        doTestMigrateTunnel(AF_INET6, AF_INET, true, false);
    }

    @IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
    @Test
    public void testMigrateTransformTunnelV6InV4UdpEncap() throws Exception {
        doTestMigrateTunnelModeTransform(AF_INET6, AF_INET, true, false);
    }

    @Test
    public void testTunnelV6InV4UdpEncapReflected() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());
        checkTunnelReflected(AF_INET6, AF_INET, true, false);
    }

    @Test
    public void testTunnelV6InV6() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());
        checkTunnelOutput(AF_INET6, AF_INET6, false, false);
        checkTunnelInput(AF_INET6, AF_INET6, false, false);
    }

    @IgnoreUpTo(Build.VERSION_CODES.R)
    @Test
    public void testMigrateTunnelV6InV6() throws Exception {
        doTestMigrateTunnel(AF_INET6, AF_INET6, false, false);
    }

    @IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
    @Test
    public void testMigrateTransformTunnelV6InV6() throws Exception {
        doTestMigrateTunnelModeTransform(AF_INET6, AF_INET6, false, false);
    }

    @Test
    public void testTunnelV6InV6Reflected() throws Exception {
        assumeTrue(mCtsNetUtils.hasIpsecTunnelsFeature());
        checkTunnelReflected(AF_INET6, AF_INET6, false, false);
    }
}
