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

import android.net.IpPrefix;
import android.net.MacAddress;
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
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
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

    /**
     * A class that launches and controls a simulation Full Thread Device (FTD).
     *
     * <p>This class launches an `ot-cli-ftd` process and communicates with it via command line
     * input and output. See <a
     * href="https://github.com/openthread/openthread/blob/main/src/cli/README.md">this page</a> for
     * available commands.
     */
    public static final class FullThreadDevice {
        private Process mProcess;
        private final BufferedReader mReader;
        private final BufferedWriter mWriter;

        private ActiveOperationalDataset mActiveOperationalDataset;

        /**
         * Constructs a {@link FullThreadDevice} for the given node ID.
         *
         * <p>It launches an `ot-cli-ftd` process using the given node ID. The node ID is an integer
         * in range [1, OPENTHREAD_SIMULATION_MAX_NETWORK_SIZE].
         *
         * @param nodeId the node ID for the simulation Full Thread Device.
         * @throws AssertionError the node ID is already occupied by another simulation Thread
         *     device.
         */
        public FullThreadDevice(int nodeId) {
            try {
                mProcess = Runtime.getRuntime().exec("/system/bin/ot-cli-ftd " + nodeId);
            } catch (SecurityException
                    | IOException
                    | NullPointerException
                    | IllegalArgumentException e) {
                fail("Failed to start FTD: " + e);
            }
            mReader = new BufferedReader(new InputStreamReader(mProcess.getInputStream()));
            mWriter = new BufferedWriter(new OutputStreamWriter(mProcess.getOutputStream()));
            mActiveOperationalDataset = null;
        }

        /**
         * Gets an OMR (Off-Mesh-Routable) address on this device if any.
         *
         * <p>This methods goes through all unicast addresses on the device and returns the first
         * address which is neither link-local nor mesh-local.
         *
         * @return An OMR address on the device if there is. Otherwise return {@code null}.
         */
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
                } catch (UnknownHostException | SecurityException e) {
                    Log.w(TAG, "failed to parse the IPv6 address " + address, e);
                }
            }
            return null;
        }

        /**
         * Joins the Thread network using the given {@link ActiveOperationalDataset}.
         *
         * @param dataset the Active Operational Dataset
         */
        public void joinNetwork(ActiveOperationalDataset dataset) {
            mActiveOperationalDataset = dataset;
            executeCommand(
                    "dataset set active " + base16().lowerCase().encode(dataset.toThreadTlvs()));
            executeCommand("ifconfig up");
            executeCommand("thread start");
        }

        /** Stops the Thread network radio. */
        public void stopThreadRadio() {
            executeCommand("thread stop");
            executeCommand("ifconfig down");
        }

        /**
         * Waits for the Thread device to enter the any state of the given {@link List<String>}.
         *
         * @param states the list of states to wait for. Valid states are "disabled", "detached",
         *     "child", "router" and "leader".
         * @param timeoutSeconds the number of seconds to wait for.
         * @throws Exception when the timeout expires.
         */
        public void waitForStateAnyOf(List<String> states, int timeoutSeconds) throws Exception {
            waitFor(() -> states.contains(getState()), timeoutSeconds);
        }

        /**
         * Gets the state of the Thread device.
         *
         * @return a string representing the state.
         */
        public String getState() {
            return executeCommand("state").get(0);
        }

        /**
         * Runs the "factoryreset" command on the device.
         *
         * @throws Exception
         */
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
            } catch (IOException e) {
                Log.w(TAG, "Failed to read ot-cli-ftd output", e);
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
                        fail("ot-cli-ftd reported an error: " + line);
                    }
                    if (!line.startsWith("> ")) {
                        result.add(line);
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed to read ot-cli-ftd output", e);
            }
            return result;
        }
    }

    /**
     * A class that simulates a device on the infrastructure network.
     *
     * <p>This class directly interacts with the TUN interface of the test network to pretend
     * there's a device on the infrastructure network.
     */
    public static final class InfraNetworkDevice {
        // The MAC address of this device.
        public final MacAddress macAddr;
        // The packet reader of the TUN interface of the test network.
        public final TapPacketReader packetReader;

        // The IPv6 address generated by SLAAC for the device.
        public Inet6Address ipv6Addr;

        /**
         * Constructs an InfraNetworkDevice with the given {@link MAC address} and {@link
         * TapPacketReader}.
         *
         * @param macAddr the MAC address of the device
         * @param packetReader the packet reader of the TUN interface of the test network.
         */
        public InfraNetworkDevice(MacAddress macAddr, TapPacketReader packetReader) {
            this.macAddr = macAddr;
            this.packetReader = packetReader;
        }

        /**
         * Sends an ICMPv6 echo request message to the given {@link Inet6Address}.
         *
         * @param dstAddr the destination address of the packet.
         * @throws Exception
         */
        public void sendEchoRequest(Inet6Address dstAddr) throws Exception {
            ByteBuffer icmp6Packet = Ipv6Utils.buildEchoRequestPacket(ipv6Addr, dstAddr);
            packetReader.sendResponse(icmp6Packet);
        }

        /**
         * Sends an ICMPv6 Router Solicitation (RS) message to all routers on the network.
         *
         * @throws Exception
         */
        public void sendRsPacket() throws IOException {
            ByteBuffer slla = LlaOption.build((byte) ICMPV6_ND_OPTION_SLLA, macAddr);
            ByteBuffer rs =
                    Ipv6Utils.buildRsPacket(
                            (Inet6Address) Inet6Address.getByName("fe80::1"),
                            IPV6_ADDR_ALL_ROUTERS_MULTICAST,
                            slla);
            packetReader.sendResponse(rs);
        }

        /**
         * Runs SLAAC to generate an IPv6 address for the device.
         *
         * <p>The devices sends an RS message, processes the received RA messages and generates an
         * IPv6 address if there's any available Prefix Information Option (PIO). For now it only
         * generates one address in total and doesn't track the expiration.
         *
         * @param timeoutSeconds the number of seconds to wait for.
         * @throws Exception
         */
        public void runSlaac(int timeoutSeconds) throws Exception {
            waitFor(() -> (ipv6Addr = runSlaac()) != null, timeoutSeconds, 5 /* intervalSeconds */);
        }

        private Inet6Address runSlaac() {
            try {
                sendRsPacket();

                final byte[] raPacket = readPacketFrom(packetReader, p -> !getRaPios(p).isEmpty());

                final List<PrefixInformationOption> options = getRaPios(raPacket);

                for (PrefixInformationOption pio : options) {
                    if (pio.validLifetime > 0) {
                        final byte[] addressBytes = pio.prefix;
                        addressBytes[addressBytes.length - 1] = (byte) (new Random()).nextInt();
                        addressBytes[addressBytes.length - 2] = (byte) (new Random()).nextInt();
                        return (Inet6Address) InetAddress.getByAddress(addressBytes);
                    }
                }
            } catch (IOException ignored) {
            }
            return null;
        }
    }

    /**
     * Waits for the given {@link Supplier} to be true until given timeout.
     *
     * <p>It checks the condition once every second.
     *
     * @param condition the condition to check.
     * @param timeoutSeconds the number of seconds to wait for.
     * @throws Exception if an exception is thrown when running the condition or the timeout
     *     expires.
     */
    public static void waitFor(Supplier<Boolean> condition, int timeoutSeconds) throws Exception {
        waitFor(condition, timeoutSeconds, 1);
    }

    /**
     * Waits for the given {@link Supplier} to be true until given timeout.
     *
     * <p>It checks the condition once every {@code intervalSeconds}.
     *
     * @param condition the condition to check.
     * @param timeoutSeconds the number of seconds to wait for.
     * @param intervalSeconds the period to check the {@code condition}.
     * @throws Exception if an exception is thrown when checking the condition or the timeout
     *     expires.
     */
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

    /**
     * Creates a {@link TapPacketReader} given the {@link TestNetworkInterface} and {@link Handler}.
     *
     * @param testNetworkInterface the TUN interface of the test network.
     * @param handler the handler to process the packets.
     * @return the {@link TapPacketReader}.
     */
    public static TapPacketReader newPacketReader(
            TestNetworkInterface testNetworkInterface, Handler handler) {
        FileDescriptor fd = testNetworkInterface.getFileDescriptor().getFileDescriptor();
        final TapPacketReader reader =
                new TapPacketReader(handler, fd, testNetworkInterface.getMtu());
        handler.post(() -> reader.start());
        HandlerUtils.waitForIdle(handler, 5000 /* timeout in milliseconds */);
        return reader;
    }

    /**
     * Waits for the Thread module to enter any state of the given {@code deviceRoles}.
     *
     * @param controller the {@link ThreadNetworkController}.
     * @param deviceRoles the desired device roles. See also {@link
     *     ThreadNetworkController.DeviceRole}.
     * @param timeoutSeconds the number of seconds ot wait for.
     * @return the device role after waiting {@link ThreadNetworkController.DeviceRole}.
     * @throws Exception when the timeout expires.
     */
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

    /**
     * Reads a packet from a given {@link TapPacketReader} that satisfies the {@code filter}.
     *
     * @param packetReader a TUN packet reader.
     * @param filter the filter to be applied on the packet.
     * @return the first IPv6 packet that satisfies the {@code filter}. If it has waited for more
     *     than 500ms to read the next packet, the method will return null.
     */
    public static byte[] readPacketFrom(TapPacketReader packetReader, Predicate<byte[]> filter) {
        byte[] packet;
        while ((packet = packetReader.poll(500 /* timeoutMs */)) != null) {
            if (filter.test(packet)) return packet;
        }

        return null;
    }

    /**
     * Returns if the given packet is an ICMPv6 packet of given type.
     *
     * @param packet the packet to be checked.
     * @param type the expected ICMPv6 packet type.
     * @return whether the packet is an ICMPv6 packet of {@code type}.
     */
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
        } catch (IllegalArgumentException ignored) {
        }
        return false;
    }

    private static List<PrefixInformationOption> getRaPios(byte[] packet) {
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
