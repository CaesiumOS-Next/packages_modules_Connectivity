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

package com.android.server.connectivity.mdns;

import android.annotation.NonNull;
import android.text.TextUtils;
import android.util.Pair;

import com.android.server.connectivity.mdns.util.MdnsLogger;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.DatagramPacket;
import java.net.InetAddress;
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
// TODO(b/177655645): Resolve nullness suppression.
@SuppressWarnings("nullness")
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

    private final WeakReference<MdnsSocketClient> mWeakRequestSender;
    private final MdnsPacketWriter mPacketWriter;
    private final String[] mServiceTypeLabels;
    private final List<String> mSubtypes;
    private final boolean mExpectUnicastResponse;
    private final int mTransactionId;

    EnqueueMdnsQueryCallable(
            @NonNull MdnsSocketClient requestSender,
            @NonNull MdnsPacketWriter packetWriter,
            @NonNull String serviceType,
            @NonNull Collection<String> subtypes,
            boolean expectUnicastResponse,
            int transactionId) {
        mWeakRequestSender = new WeakReference<>(requestSender);
        mPacketWriter = packetWriter;
        mServiceTypeLabels = TextUtils.split(serviceType, "\\.");
        mSubtypes = new ArrayList<>(subtypes);
        mExpectUnicastResponse = expectUnicastResponse;
        mTransactionId = transactionId;
    }

    @Override
    public Pair<Integer, List<String>> call() {
        try {
            MdnsSocketClient requestSender = mWeakRequestSender.get();
            if (requestSender == null) {
                return null;
            }

            int numQuestions = 1;
            if (!mSubtypes.isEmpty()) {
                numQuestions += mSubtypes.size();
            }

            // Header.
            mPacketWriter.writeUInt16(mTransactionId); // transaction ID
            mPacketWriter.writeUInt16(MdnsConstants.FLAGS_QUERY); // flags
            mPacketWriter.writeUInt16(numQuestions); // number of questions
            mPacketWriter.writeUInt16(0); // number of answers(not yet known; will be written later)
            mPacketWriter.writeUInt16(0); // number of authority entries
            mPacketWriter.writeUInt16(0); // number of additional records

            // Question(s). There will be one question for each (fqdn+subtype, recordType)
          // combination,
            // as well as one for each (fqdn, recordType) combination.

            for (String subtype : mSubtypes) {
                String[] labels = new String[mServiceTypeLabels.length + 2];
                labels[0] = MdnsConstants.SUBTYPE_PREFIX + subtype;
                labels[1] = MdnsConstants.SUBTYPE_LABEL;
                System.arraycopy(mServiceTypeLabels, 0, labels, 2, mServiceTypeLabels.length);

                mPacketWriter.writeLabels(labels);
                mPacketWriter.writeUInt16(MdnsRecord.TYPE_PTR);
                mPacketWriter.writeUInt16(
                        MdnsConstants.QCLASS_INTERNET
                                | (mExpectUnicastResponse ? MdnsConstants.QCLASS_UNICAST : 0));
            }

            mPacketWriter.writeLabels(mServiceTypeLabels);
            mPacketWriter.writeUInt16(MdnsRecord.TYPE_PTR);
            mPacketWriter.writeUInt16(
                    MdnsConstants.QCLASS_INTERNET
                            | (mExpectUnicastResponse ? MdnsConstants.QCLASS_UNICAST : 0));

            InetAddress mdnsAddress = MdnsConstants.getMdnsIPv4Address();
            if (requestSender.isOnIPv6OnlyNetwork()) {
                mdnsAddress = MdnsConstants.getMdnsIPv6Address();
            }

            sendPacketTo(requestSender,
                    new InetSocketAddress(mdnsAddress, MdnsConstants.MDNS_PORT));
            for (Integer emulatorPort : castShellEmulatorMdnsPorts) {
                sendPacketTo(requestSender, new InetSocketAddress(mdnsAddress, emulatorPort));
            }
            return Pair.create(mTransactionId, mSubtypes);
        } catch (IOException e) {
            LOGGER.e(String.format("Failed to create mDNS packet for subtype: %s.",
                    TextUtils.join(",", mSubtypes)), e);
            return null;
        }
    }

    private void sendPacketTo(MdnsSocketClient requestSender, InetSocketAddress address)
            throws IOException {
        final DatagramPacket packet = mPacketWriter.getPacket(address);
        if (mExpectUnicastResponse) {
            requestSender.sendUnicastPacket(packet);
        } else {
            requestSender.sendMulticastPacket(packet);
        }
    }
}
