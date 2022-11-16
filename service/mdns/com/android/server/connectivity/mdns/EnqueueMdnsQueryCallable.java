/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.connectivity.mdns;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.android.server.connectivity.mdns.util.MdnsLogger;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * A {@link Callable} that builds and enqueues a mDNS query to send over the multicast socket. If a
 * query is built and enqueued successfully, then call to {@link #call()} returns the transaction ID
 * and the list of the subtypes in the query as a {@link Pair}. If a query is failed to build, or if
 * it can not be enqueued, then call to {@link #call()} returns {@code null}.
 */
public class EnqueueMdnsQueryCallable implements Callable<Pair<Integer, List<String>>> {

    private static final String TAG = "MdnsQueryCallable";
    private static final MdnsLogger LOGGER = new MdnsLogger(TAG);
    private static final List<Integer> castShellEmulatorMdnsPorts;

    static {
        castShellEmulatorMdnsPorts = new ArrayList<>();
        String[] stringPorts = MdnsConfigs.castShellEmulatorMdnsPorts();

        for (String port : stringPorts) {
            try {
                castShellEmulatorMdnsPorts.add(Integer.parseInt(port));
            } catch (NumberFormatException e) {
                // Ignore.
            }
        }
    }

    private final WeakReference<MdnsSocketManager> weakRequestSender;
    private final MdnsPacketWriter packetWriter;
    private final String[] serviceTypeLabels;
    private final List<String> subtypes;
    private final boolean expectUnicastResponse;
    private final int transactionId;

    EnqueueMdnsQueryCallable(
            @NonNull MdnsSocketManager requestSender,
            @NonNull MdnsPacketWriter packetWriter,
            @NonNull String serviceType,
            @NonNull Collection<String> subtypes,
            boolean expectUnicastResponse,
            int transactionId) {
        weakRequestSender = new WeakReference<>(requestSender);
        this.packetWriter = packetWriter;
        serviceTypeLabels = TextUtils.split(serviceType, "\\.");
        this.subtypes = new ArrayList<>(subtypes);
        this.expectUnicastResponse = expectUnicastResponse;
        this.transactionId = transactionId;
    }

    // Incompatible return type for override of Callable#call().
    @SuppressWarnings("nullness:override.return.invalid")
    @Override
    @Nullable
    public Pair<Integer, List<String>> call() {
        try {
            MdnsSocketManager requestSender = weakRequestSender.get();
            if (requestSender == null) {
                return null;
            }

            int numQuestions = 1;
            if (!subtypes.isEmpty()) {
                numQuestions += subtypes.size();
            }

            // Header.
            packetWriter.writeUInt16(transactionId); // transaction ID
            packetWriter.writeUInt16(MdnsConstants.FLAGS_QUERY); // flags
            packetWriter.writeUInt16(numQuestions); // number of questions
            packetWriter.writeUInt16(0); // number of answers (not yet known; will be written later)
            packetWriter.writeUInt16(0); // number of authority entries
            packetWriter.writeUInt16(0); // number of additional records

            // Question(s). There will be one question for each (fqdn+subtype, recordType)
          // combination,
            // as well as one for each (fqdn, recordType) combination.

            for (String subtype : subtypes) {
                String[] labels = new String[serviceTypeLabels.length + 2];
                labels[0] = MdnsConstants.SUBTYPE_PREFIX + subtype;
                labels[1] = MdnsConstants.SUBTYPE_LABEL;
                System.arraycopy(serviceTypeLabels, 0, labels, 2, serviceTypeLabels.length);

                packetWriter.writeLabels(labels);
                packetWriter.writeUInt16(MdnsRecord.TYPE_PTR);
                packetWriter.writeUInt16(
                        MdnsConstants.QCLASS_INTERNET
                                | (expectUnicastResponse ? MdnsConstants.QCLASS_UNICAST : 0));
            }

            packetWriter.writeLabels(serviceTypeLabels);
            packetWriter.writeUInt16(MdnsRecord.TYPE_PTR);
            packetWriter.writeUInt16(
                    MdnsConstants.QCLASS_INTERNET
                            | (expectUnicastResponse ? MdnsConstants.QCLASS_UNICAST : 0));

            if (sendPacket(requestSender, MdnsConstants.MDNS_PORT)) {
                return null;
            }

            for (Integer emulatorPort : castShellEmulatorMdnsPorts) {
                if (sendPacket(requestSender, emulatorPort)) {
                    return null;
                }
            }
            return Pair.create(transactionId, subtypes);
        } catch (IOException e) {
            LOGGER.e(String.format("Failed to create mDNS packet for subtype: %s.",
                    TextUtils.join(",", subtypes)), e);
            return null;
        }
    }

    private boolean sendPacket(MdnsSocketManager requestSender, int port) {
        // Send packets to both IPv4 and IPv6.
        final boolean sendToIPv4Success = sendPacketToIpv4(requestSender, new InetSocketAddress(
                MdnsConstants.getMdnsIPv4Address(), port));
        final boolean sendToIPv6Success = sendPacketToIpv6(requestSender, new InetSocketAddress(
                MdnsConstants.getMdnsIPv6Address(), port));
        return sendToIPv4Success || sendToIPv6Success;
    }

    private boolean sendPacketToIpv4(MdnsSocketManager requestSender, InetSocketAddress address) {
        try {
            DatagramPacket packet = packetWriter.getPacket(address);
            requestSender.sendPacketToIpv4(packet, expectUnicastResponse);
        } catch (IOException e) {
            Log.e(TAG, "Can't send packet to IPv4");
            return false;
        }
        return true;
    }

    private boolean sendPacketToIpv6(MdnsSocketManager requestSender, InetSocketAddress address) {
        try {
            DatagramPacket packet = packetWriter.getPacket(address);
            requestSender.sendPacketToIpv6(packet, expectUnicastResponse);
        } catch (IOException e) {
            Log.e(TAG, "Can't send packet to IPv6");
            return false;
        }
        return true;
    }
}