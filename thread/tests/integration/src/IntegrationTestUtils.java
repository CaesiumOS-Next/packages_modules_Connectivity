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
package android.net.thread;

import static android.system.OsConstants.IPPROTO_ICMPV6;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ND_OPTION_PIO;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ND_OPTION_SLLA;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ROUTER_ADVERTISEMENT;
import static com.android.net.module.util.NetworkStackConstants.IPV6_ADDR_ALL_ROUTERS_MULTICAST;
import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.junit.Assert.fail;

import android.net.MacAddress;
import android.net.IpPrefix;
import android.net.TestNetworkInterface;
import android.os.Handler;
import android.util.Log;

import com.android.net.module.util.Ipv6Utils;
import com.android.net.module.util.Struct;
import com.android.net.module.util.structs.Icmpv6Header;
import com.android.net.module.util.structs.Ipv6Header;
import com.android.net.module.util.structs.LlaOption;
import com.android.net.module.util.structs.PrefixInformationOption;
import com.android.net.module.util.structs.RaHeader;
import com.android.testutils.HandlerUtils;
import com.android.testutils.TapPacketReader;
import com.google.common.util.concurrent.SettableFuture;

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
import java.util.function.Supplier;

public class IntegrationTestUtils {
    private static final String TAG = IntegrationTestUtils.class.getSimpleName();

    public static final class OtFtd {
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
            List<String> addresses = executeCommand("ipaddr");
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

        public void waitForStateAnyOf(List<String> states, int timeoutSeconds) throws Exception {
            waitFor(() -> states.contains(getState()), timeoutSeconds);
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

        private List<String> executeCommand(String command) {
            try {
                mWriter.write(command + "\n");
                mWriter.flush();
                return readUntilDone();
            } catch (Exception ignored) {
            }
            return new ArrayList<String>();
        }

        private List<String> readUntilDone() {
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

    public static final class InfraNetworkDevice {
        public final MacAddress macAddr;
        public final TapPacketReader packetReader; // Actually a TUN reader & writer.
        public Inet6Address ipv6Addr;

        public InfraNetworkDevice(MacAddress macAddr, TapPacketReader packetReader)
                throws Exception {
            this.macAddr = macAddr;
            this.packetReader = packetReader;
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

        public void runSlaac(int timeoutSeconds) throws Exception {
            waitFor(() -> (ipv6Addr = runSlaac()) != null, timeoutSeconds, 5 /* intervalSeconds */);
        }

        private Inet6Address runSlaac() {
            try {
                sendRsPacket();

                final byte[] raPacket = readPacketsFrom(packetReader, p -> !getRaPios(p).isEmpty());

                final List<PrefixInformationOption> options = getRaPios(raPacket);

                for (PrefixInformationOption pio : options) {
                    if (pio.validLifetime > 0) {
                        final byte[] addressBytes = pio.prefix;
                        addressBytes[addressBytes.length - 1] = (byte) (new Random()).nextInt();
                        addressBytes[addressBytes.length - 2] = (byte) (new Random()).nextInt();
                        return (Inet6Address) InetAddress.getByAddress(addressBytes);
                    }
                }
            } catch (Exception ignored) {
            }
            return null;
        }
    }

    public static void waitFor(Supplier<Boolean> condition, int timeoutSeconds) throws Exception {
        waitFor(condition, timeoutSeconds, 1);
    }

    public static void waitFor(Supplier<Boolean> condition, int timeoutSeconds, int intervalSeconds)
            throws Exception {
        for (int i = 0; i <= timeoutSeconds; i += intervalSeconds) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(intervalSeconds * 1000L);
        }
        throw new TimeoutException(
                String.format(
                        "The condition failed to become true in %d seconds.", timeoutSeconds));
    }

    public static TapPacketReader makePacketReader(
            TestNetworkInterface testNetworkInterface, Handler handler) {
        FileDescriptor fd = testNetworkInterface.getFileDescriptor().getFileDescriptor();
        final TapPacketReader reader =
                new TapPacketReader(handler, fd, testNetworkInterface.getMtu());
        handler.post(() -> reader.start());
        HandlerUtils.waitForIdle(handler, 5000 /* timeout in milliseconds */);
        return reader;
    }

    public static int waitForStateAnyOf(
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

    public static byte[] readPacketsFrom(TapPacketReader packetReader, Predicate<byte[]> filter) {
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
        } catch (Exception ignored) {
        }
        return false;
    }

    public static List<PrefixInformationOption> getRaPios(byte[] packet) {
        final ArrayList<PrefixInformationOption> pioList = new ArrayList<>();

        if (packet == null) {
            return pioList;
        }
        final ByteBuffer buf = ByteBuffer.wrap(packet);
        final Ipv6Header ipv6Header = Struct.parse(Ipv6Header.class, buf);
        if (ipv6Header.nextHeader != (byte) IPPROTO_ICMPV6) {
            return pioList;
        }

        final Icmpv6Header icmpv6Header = Struct.parse(Icmpv6Header.class, buf);
        if (icmpv6Header.type != (short) ICMPV6_ROUTER_ADVERTISEMENT) {
            return pioList;
        }

        Struct.parse(RaHeader.class, buf);
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
}
