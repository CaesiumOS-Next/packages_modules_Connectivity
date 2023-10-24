/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.Manifest.permission.MANAGE_TEST_NETWORKS;
import static android.Manifest.permission.THREAD_NETWORK_PRIVILEGED;
import static android.net.thread.ThreadNetworkController.DEVICE_ROLE_LEADER;
import static android.system.OsConstants.IPPROTO_ICMPV6;
import static com.android.net.module.util.HexDump.dumpHexString;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ECHO_REPLY_TYPE;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ND_OPTION_PIO;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ND_OPTION_SLLA;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ROUTER_ADVERTISEMENT;
import static com.android.net.module.util.NetworkStackConstants.IPV6_ADDR_ALL_ROUTERS_MULTICAST;
import static com.android.testutils.TestNetworkTrackerKt.initTestNetwork;
import static com.android.testutils.TestPermissionUtil.runAsShell;
import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.net.thread.ActiveOperationalDataset;
import android.net.thread.ThreadNetworkController;
import android.net.thread.ThreadNetworkManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.net.module.util.Ipv6Utils;
import com.android.net.module.util.Struct;
import com.android.net.module.util.structs.Icmpv6Header;
import com.android.net.module.util.structs.Ipv6Header;
import com.android.net.module.util.structs.LlaOption;
import com.android.net.module.util.structs.PrefixInformationOption;
import com.android.net.module.util.structs.RaHeader;
import com.android.testutils.HandlerUtils;
import com.android.testutils.TapPacketReader;
import com.android.testutils.TestNetworkTracker;
import com.google.common.util.concurrent.SettableFuture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileDescriptor;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class ThreadBorderRouterTest {
    private static final String TAG = ThreadBorderRouterTest.class.getSimpleName();

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();

    private final ThreadNetworkManager mThreadNetworkManager =
            mContext.getSystemService(ThreadNetworkManager.class);
    private ThreadNetworkController mThreadNetworkController;
    private HandlerThread mHandlerThread;
    private Handler mHandler;

    private TestNetworkTracker mInfraNetworkTracker;

    private static final int TIMEOUT_MS = 5000;

    private static final byte[] DEFAULT_DATASET_TLVS =
            base16().decode(
                            "0E080000000000010000000300001335060004001FFFE002"
                                    + "08ACC214689BC40BDF0708FD64DB1225F47E0B0510F26B31"
                                    + "53760F519A63BAFDDFFC80D2AF030F4F70656E5468726561"
                                    + "642D643961300102D9A00410A245479C836D551B9CA557F7"
                                    + "B9D351B40C0402A0FFF8");

    private static final ActiveOperationalDataset DEFAULT_DATASET =
            ActiveOperationalDataset.fromThreadTlvs(DEFAULT_DATASET_TLVS);

    @Before
    public void setUp() throws Exception {
        mHandlerThread = new HandlerThread(getClass().getSimpleName());
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        var threadControllers = mThreadNetworkManager.getAllThreadNetworkControllers();
        assertEquals(threadControllers.size(), 1);
        mThreadNetworkController = threadControllers.get(0);
        mInfraNetworkTracker =
                runAsShell(
                        MANAGE_TEST_NETWORKS,
                        () -> initTestNetwork(mContext, new LinkProperties(), TIMEOUT_MS));
    }

    private static final class OtFtd {
        public OtFtd(int nodeId) {
            try {
                mProcess = Runtime.getRuntime().exec("/system/bin/ot-cli-ftd " + nodeId);
            } catch (Exception e) {
                fail("Failed to start FTD: " + e);
            }
            mReader = new BufferedReader(new InputStreamReader(mProcess.getInputStream()));
            mWriter = new BufferedWriter(new OutputStreamWriter(mProcess.getOutputStream()));
            mErrorReader = new BufferedReader(new InputStreamReader(mProcess.getErrorStream()));
            mActiveOperationalDataset = null;
        }

        public Inet6Address getOmrAddress() {
            ArrayList<String> addresses = executeCommand("ipaddr");
            IpPrefix meshLocalPrefix = mActiveOperationalDataset.getMeshLocalPrefix();
            for (String address : addresses) {
                if (address.startsWith("fe80:")) {
                    continue;
                }
                try {
                    Inet6Address addr = (Inet6Address) Inet6Address.getByName(address);
                    if (!meshLocalPrefix.contains(addr)) {
                        return addr;
                    }
                } catch (Exception ignored) {
                }
            }
            return null;
        }

        public void joinNetwork(ActiveOperationalDataset dataset) {
            mActiveOperationalDataset = dataset;
            executeCommand(
                    "dataset set active " + base16().lowerCase().encode(dataset.toThreadTlvs()));
            executeCommand("ifconfig up");
            executeCommand("thread start");
        }

        public void stopThreadRadio() {
            executeCommand("thread stop");
            executeCommand("ifconfig down");
        }

        public String waitForStateAnyOf(List<String> states, int timeoutSeconds) throws Exception {
            for (int i = 0; i < timeoutSeconds; ++i) {
                Thread.sleep(timeoutSeconds * 1000L);
                String state = getState();
                if (states.contains(state)) {
                    return state;
                }
            }
            throw new TimeoutException(
                    String.format(
                            "Failed to be in state %s in %d seconds", states, timeoutSeconds));
        }

        public String getState() {
            return executeCommand("state").get(0);
        }

        public void factoryReset() throws Exception {
            mWriter.write("factoryreset\n");
            mWriter.flush();
            // fill the input buffer to avoid truncating next command
            for (int i = 0; i < 1000; ++i) {
                mWriter.write("\n");
            }
            mWriter.flush();
        }

        private ArrayList<String> executeCommand(String command) {
            try {
                mWriter.write(command + "\n");
                mWriter.flush();
                return readUntilDone();
            } catch (Exception ignored) {
            }
            return new ArrayList<String>();
        }

        private ArrayList<String> readUntilDone() {
            ArrayList<String> result = new ArrayList<>();
            String line;
            try {
                while ((line = mReader.readLine()) != null) {
                    if (line.equals("Done")) {
                        break;
                    }
                    if (line.startsWith("Error:")) {
                        throw new Exception(line);
                    }
                    if (!line.startsWith("> ")) {
                        result.add(line);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to read ot-cli-ftd output", e);
            }
            return result;
        }

        private Process mProcess;
        private final BufferedReader mReader;
        private final BufferedReader mErrorReader;
        private final BufferedWriter mWriter;

        private ActiveOperationalDataset mActiveOperationalDataset;
    }

    private static byte[] readPacketsFrom(TapPacketReader packetReader, Predicate<byte[]> filter) {
        byte[] packet;
        while ((packet = packetReader.poll(500 /* timeoutMs */)) != null) {
            if (filter.test(packet)) return packet;
        }

        return null;
    }

    public static boolean isExpectedIcmpv6Packet(byte[] packet, int type) {
        if (packet == null) {
            return false;
        }
        ByteBuffer buf = ByteBuffer.wrap(packet);
        try {
            if (Struct.parse(Ipv6Header.class, buf).nextHeader != (byte) IPPROTO_ICMPV6) {
                return false;
            }
            return Struct.parse(Icmpv6Header.class, buf).type == (short) type;
        } catch (Exception exception) {
            Log.d(TAG, "Failed to parse the packet", exception);
        }
        return false;
    }

    private static List<PrefixInformationOption> getRaPrefixOptions(byte[] packet) {
        if (packet == null) {
            return List.of();
        }
        ByteBuffer buf = ByteBuffer.wrap(packet);

        if (Struct.parse(Ipv6Header.class, buf).nextHeader != (byte) IPPROTO_ICMPV6) {
            return List.of();
        }

        if (Struct.parse(Icmpv6Header.class, buf).type != (short) ICMPV6_ROUTER_ADVERTISEMENT) {
            return List.of();
        }

        Struct.parse(RaHeader.class, buf);
        final ArrayList<PrefixInformationOption> pioList = new ArrayList<>();
        while (buf.position() < packet.length) {
            final int currentPos = buf.position();
            final int type = Byte.toUnsignedInt(buf.get());
            final int length = Byte.toUnsignedInt(buf.get());
            if (type == ICMPV6_ND_OPTION_PIO) {
                final ByteBuffer pioBuf =
                        ByteBuffer.wrap(
                                buf.array(),
                                currentPos,
                                Struct.getSize(PrefixInformationOption.class));
                final PrefixInformationOption pio =
                        Struct.parse(PrefixInformationOption.class, pioBuf);
                pioList.add(pio);

                // Move ByteBuffer position to the next option.
                buf.position(currentPos + Struct.getSize(PrefixInformationOption.class));
            } else {
                // The length is in units of 8 octets.
                buf.position(currentPos + (length * 8));
            }
        }
        return pioList;
    }

    private static final class InfraNetworkDevice {
        public final MacAddress macAddr;
        public final TapPacketReader packetReader; // Actually a TUN reader & writer.
        public final Inet6Address ipv6Addr;

        private InfraNetworkDevice(MacAddress macAddr, TapPacketReader packetReader)
                throws Exception {
            this.macAddr = macAddr;
            this.packetReader = packetReader;
            this.ipv6Addr = runSlaac();
        }

        public void sendEchoRequest(Inet6Address dstAddr) throws Exception {
            ByteBuffer icmp6Packet = Ipv6Utils.buildEchoRequestPacket(ipv6Addr, dstAddr);
            packetReader.sendResponse(icmp6Packet);
        }

        public void sendRsPacket() throws Exception {
            ByteBuffer slla = LlaOption.build((byte) ICMPV6_ND_OPTION_SLLA, macAddr);
            ByteBuffer rs =
                    Ipv6Utils.buildRsPacket(
                            (Inet6Address) Inet6Address.getByName("fe80::1"),
                            IPV6_ADDR_ALL_ROUTERS_MULTICAST,
                            slla);
            packetReader.sendResponse(rs);
        }

        private Inet6Address runSlaac() throws Exception {
            sendRsPacket();

            final byte[] raPacket =
                    readPacketsFrom(packetReader, p -> !getRaPrefixOptions(p).isEmpty());

            final List<PrefixInformationOption> options = getRaPrefixOptions(raPacket);

            for (PrefixInformationOption pio : options) {
                if (pio.validLifetime > 0) {
                    final byte[] addressBytes = pio.prefix;
                    addressBytes[addressBytes.length - 1] = (byte) (new Random()).nextInt();
                    addressBytes[addressBytes.length - 2] = (byte) (new Random()).nextInt();
                    return (Inet6Address) InetAddress.getByAddress(addressBytes);
                }
            }
            return null;
        }
    }

    private TapPacketReader makePacketReader(TestNetworkInterface testNetworkInterface) {
        FileDescriptor fd = testNetworkInterface.getFileDescriptor().getFileDescriptor();
        final TapPacketReader reader =
                new TapPacketReader(mHandler, fd, testNetworkInterface.getMtu());
        mHandler.post(() -> reader.start());
        HandlerUtils.waitForIdle(mHandler, TIMEOUT_MS);
        return reader;
    }

    private int waitForStateAnyOf(
            ThreadNetworkController controller, List<Integer> deviceRoles, int timeoutSeconds)
            throws Exception {
        SettableFuture<Integer> future = SettableFuture.create();
        ThreadNetworkController.StateCallback callback =
                newRole -> {
                    if (deviceRoles.contains(newRole)) {
                        future.set(newRole);
                    }
                };
        controller.registerStateCallback(directExecutor(), callback);
        int role = future.get(timeoutSeconds, TimeUnit.SECONDS);
        controller.unregisterStateCallback(callback);
        return role;
    }

    @Test
    public void testIcmp6Echo_fromInfraNetworkHostToThreadEd() throws Exception {
        TapPacketReader infraNetworkReader = makePacketReader(mInfraNetworkTracker.getTestIface());

        runAsShell(
                THREAD_NETWORK_PRIVILEGED,
                () -> {
                    mThreadNetworkController.join(
                            DEFAULT_DATASET, mContext.getMainExecutor(), result -> {});
                });

        waitForStateAnyOf(
                mThreadNetworkController, List.of(DEVICE_ROLE_LEADER), 10 /* timeoutSeconds */);

        OtFtd ftd = new OtFtd(5 /* node ID */);

        ftd.joinNetwork(DEFAULT_DATASET);
        ftd.waitForStateAnyOf(List.of("router", "child"), 10);

        Thread.sleep(40000); // Wait for the border routing to be ready

        Inet6Address ftdOmr = ftd.getOmrAddress();
        assertNotNull(ftdOmr);
        InfraNetworkDevice infraDevice =
                new InfraNetworkDevice(MacAddress.fromString("1:2:3:4:5:6"), infraNetworkReader);
        assertNotNull(infraDevice.ipv6Addr);
        infraDevice.sendEchoRequest(ftdOmr);
        assertNotNull(
                readPacketsFrom(
                        infraNetworkReader,
                        p -> isExpectedIcmpv6Packet(p, ICMPV6_ECHO_REPLY_TYPE)));

        ftd.factoryReset();
    }

    @After
    public void tearDown() throws Exception {
        runAsShell(
                THREAD_NETWORK_PRIVILEGED,
                () -> {
                    mThreadNetworkController.leave(mContext.getMainExecutor(), result -> {});
                });
        runAsShell(MANAGE_TEST_NETWORKS, () -> mInfraNetworkTracker.teardown());

        mHandlerThread.quitSafely();
        mHandlerThread.join();
    }
}
