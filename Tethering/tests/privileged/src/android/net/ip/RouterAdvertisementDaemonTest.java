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

package android.net.ip;

import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ND_OPTION_SLLA;
import static com.android.net.module.util.NetworkStackConstants.IPV6_ADDR_ALL_NODES_MULTICAST;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Instrumentation;
import android.content.Context;
import android.net.INetd;
import android.net.IpPrefix;
import android.net.MacAddress;
import android.net.ip.RouterAdvertisementDaemon.RaParams;
import android.net.util.InterfaceParams;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.net.module.util.Ipv6Utils;
import com.android.net.module.util.structs.LlaOption;
import com.android.testutils.TapPacketReader;
import com.android.testutils.TapPacketReaderRule;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.HashSet;

@RunWith(AndroidJUnit4.class)
@SmallTest
public final class RouterAdvertisementDaemonTest {
    private static final String TAG = RouterAdvertisementDaemonTest.class.getSimpleName();
    private static final int DATA_BUFFER_LEN = 4096;
    private static final int PACKET_TIMEOUT_MS = 5_000;

    @Rule
    public final TapPacketReaderRule mTetheredReader = new TapPacketReaderRule(
            DATA_BUFFER_LEN, false /* autoStart */);

    private InterfaceParams mTetheredParams;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private TapPacketReader mTetheredPacketReader;
    private RouterAdvertisementDaemon mRaDaemon;

    private static INetd sNetd;

    @BeforeClass
    public static void setupOnce() {
        System.loadLibrary("tetherutilsjni");

        final Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        final IBinder netdIBinder =
                (IBinder) inst.getContext().getSystemService(Context.NETD_SERVICE);
        sNetd = INetd.Stub.asInterface(netdIBinder);
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mHandlerThread = new HandlerThread(getClass().getSimpleName());
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        setupTapInterfaces();

        // Looper must be prepared here since AndroidJUnitRunner runs tests on separate threads.
        if (Looper.myLooper() == null) Looper.prepare();

        mRaDaemon = new RouterAdvertisementDaemon(mTetheredParams);
        sNetd.networkAddInterface(INetd.LOCAL_NET_ID, mTetheredParams.name);
    }

    @After
    public void tearDown() throws Exception {
        mTetheredReader.stop();
        if (mHandlerThread != null) {
            mHandlerThread.quitSafely();
            mHandlerThread.join(PACKET_TIMEOUT_MS);
        }

        if (mTetheredParams != null) {
            sNetd.networkRemoveInterface(INetd.LOCAL_NET_ID, mTetheredParams.name);
        }
    }

    private void setupTapInterfaces() {
        // Create tethered test iface.
        mTetheredReader.start(mHandler);
        mTetheredParams = InterfaceParams.getByName(mTetheredReader.iface.getInterfaceName());
        assertNotNull(mTetheredParams);
        mTetheredPacketReader = mTetheredReader.getReader();
        mHandler.post(mTetheredPacketReader::start);
    }

    private static final int IPV6_HEADER_LEN = 40;
    private static final int ETH_HEADER_LEN = 14;
    private static final int RA_HEADER_LEN = 16;
    private static final int RS_HEADER_LEN = 16;
    private static final int ETHER_TYPE_IPV6 = 0x86dd;
    private static final int ICMPV6_ND_ROUTER_ADVERT = 134;
    private static final int ICMPV6_ND_ROUTER_SOLICIT = 133;
    private static final int ND_OPTION_PIO = 3;
    private static final int ND_OPTION_MTU = 5;
    private static final int ND_OPTION_RDNSS = 25;

    private class TestRaPacket {
        final RaParams mNewParams, mOldParams;

        TestRaPacket(final RaParams oldParams, final RaParams newParams) {
            mOldParams = oldParams;
            mNewParams = newParams;
        }

        public boolean isPacketMatched(final byte[] pkt) throws Exception {
            if (pkt.length < (ETH_HEADER_LEN + IPV6_HEADER_LEN + RA_HEADER_LEN)) return false;

            byte[] rawAddress = new byte[16];
            final ByteBuffer buf = ByteBuffer.wrap(pkt);

            /** Parsing Ethernet header */
            buf.position(12); // Shift src and dst mac size.

            if (Short.toUnsignedInt(buf.getShort()) != ETHER_TYPE_IPV6) return false;

            /** Parsing Ip header */
            assertEquals((buf.get() >> 4), 6 /* ip version*/);
            // Skip traffic class and flow label
            buf.position(buf.position() + 3);

            final int payLoadLength = pkt.length - ETH_HEADER_LEN - IPV6_HEADER_LEN;
            assertEquals(payLoadLength, Short.toUnsignedInt(buf.getShort()));
            // Skip following ip header field.
            buf.position(buf.position() + 34);

            /** Parsing icmpv6 */
            final int icmpv6Type = Byte.toUnsignedInt(buf.get());
            if (icmpv6Type != ICMPV6_ND_ROUTER_ADVERT) return false;

            // Skip Code and Checksum.
            buf.position(buf.position() + 3);

            assertEquals(Byte.toUnsignedInt(mNewParams.hopLimit),
                    Byte.toUnsignedInt(buf.get()));

            // Skip flags, life time, reachable time and retrans timer.
            buf.position(buf.position() + 11);

            while (buf.position() < pkt.length) {
                final int type = Byte.toUnsignedInt(buf.get());
                final int length = Byte.toUnsignedInt(buf.get());
                switch (type) {
                    case ND_OPTION_PIO :
                        assertEquals(4, length);
                        final int prefixLength = Byte.toUnsignedInt(buf.get());
                        assertEquals(0xc0, Byte.toUnsignedInt(buf.get())); // L & A set
                        final int validTime = buf.getInt();
                        final int preferredTime = buf.getInt();
                        buf.getInt(); // Reserved.
                        buf.get(rawAddress);
                        final InetAddress address = InetAddress.getByAddress(rawAddress);
                        final IpPrefix prefix = new IpPrefix(address, prefixLength);
                        Log.d(TAG, "validTime: " + validTime + ", preferredTime" + preferredTime);
                        Log.d(TAG, "pio option: " + prefix);
                        if (mNewParams.prefixes.contains(prefix)) {
                            assertTrue(validTime > 0);
                            assertTrue(preferredTime > 0);
                        } else if (mOldParams != null && mOldParams.prefixes.contains(prefix)) {
                            assertEquals(0, validTime);
                            assertEquals(0, preferredTime);
                        } else {
                            fail("Unepxected prefix: " + prefix);
                        }
                        break;
                    case ND_OPTION_MTU :
                        assertEquals(1, length);
                        buf.getShort(); // Reserved
                        assertEquals(mNewParams.mtu, buf.getInt());
                        break;
                    case ND_OPTION_RDNSS :
                        final int numOfDnses = (length - 1) / 2;
                        buf.getShort(); // Reserved
                        final int lifeTime = buf.getInt();
                        Log.d(TAG, "lifeTime: " + lifeTime);
                        final String msg = lifeTime > 0 ? "Unknown dns" : "Unknown deprecated dns";
                        HashSet<Inet6Address> dnses =
                                lifeTime > 0 ? mNewParams.dnses : mOldParams.dnses;
                        assertNotNull(msg, dnses);
                        for (int i = 0; i < numOfDnses; i++) {
                            Log.d(TAG, "rdnss option: " + dnses);
                            buf.get(rawAddress);
                            final Inet6Address dns =
                                    (Inet6Address) InetAddress.getByAddress(rawAddress);
                            if (!dnses.contains(dns)) fail("Unexpected dns: " + dns);
                        }
                        break;
                    default:
                        buf.position(buf.position() + (length * 8) - 2);
                }
            }

            return true;
        }
    }

    private RaParams createRaParams(final String ipv6Address) throws Exception {
        final RaParams params = new RaParams();
        final Inet6Address address = (Inet6Address) InetAddress.getByName(ipv6Address);
        params.dnses.add(address);
        params.prefixes.add(new IpPrefix(address, 64));

        return params;
    }

    private boolean assertRaPacket(final TestRaPacket testRa)
            throws Exception {
        byte[] packet;
        while ((packet = mTetheredPacketReader.popPacket(PACKET_TIMEOUT_MS)) != null) {
            if (testRa.isPacketMatched(packet)) return true;
        }
        return false;
    }

    private ByteBuffer createRsPacket(final String srcIp) throws Exception {
        final MacAddress dstMac = MacAddress.fromString("33:33:03:04:05:06");
        final MacAddress srcMac = mTetheredParams.macAddr;
        final ByteBuffer slla = LlaOption.build((byte) ICMPV6_ND_OPTION_SLLA, srcMac);

        return Ipv6Utils.buildRsPacket(srcMac, dstMac, (Inet6Address) InetAddress.getByName(srcIp),
                IPV6_ADDR_ALL_NODES_MULTICAST, slla);
    }

    @Test
    public void testUnSolicitRouterAdvertisement() throws Exception {
        assertTrue(mRaDaemon.start());
        final RaParams params1 = createRaParams("2001:1122:3344::5566");
        mRaDaemon.buildNewRa(null, params1);
        assertRaPacket(new TestRaPacket(null, params1));

        final RaParams params2 = createRaParams("2006:3344:5566::7788");
        mRaDaemon.buildNewRa(params1, params2);
        assertRaPacket(new TestRaPacket(params1, params2));
    }

    @Test
    public void testSolicitRouterAdvertisement() throws Exception {
        assertTrue(mRaDaemon.start());
        final RaParams params1 = createRaParams("2001:1122:3344::5566");
        mRaDaemon.buildNewRa(null, params1);
        assertRaPacket(new TestRaPacket(null, params1));

        final ByteBuffer rs = createRsPacket("fe80::1122:3344:5566:7788");
        mTetheredPacketReader.sendResponse(rs);
        assertRaPacket(new TestRaPacket(null, params1));
    }
}
