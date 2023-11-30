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

import static com.android.server.connectivity.mdns.util.MdnsUtils.ensureRunningOnHandlerThread;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresApi;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.ArrayMap;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.SharedLog;
import com.android.server.connectivity.mdns.util.MdnsUtils;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A class that handles sending mDNS replies to a {@link MulticastSocket}, possibly queueing them
 * to be sent after some delay.
 *
 * TODO: implement sending after a delay, combining queued replies and duplicate answer suppression
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public class MdnsReplySender {
    private static final int MSG_SEND = 1;
    private static final int PACKET_NOT_SENT = 0;
    private static final int PACKET_SENT = 1;

    @NonNull
    private final MdnsInterfaceSocket mSocket;
    @NonNull
    private final Handler mHandler;
    @NonNull
    private final byte[] mPacketCreationBuffer;
    @NonNull
    private final SharedLog mSharedLog;
    private final boolean mEnableDebugLog;
    private final Dependencies mDependencies;
    // RFC6762 15.2. Multipacket Known-Answer lists
    // Multicast DNS responders associate the initial truncated query with its
    // continuation packets by examining the source IP address in each packet.
    private final Map<InetSocketAddress, KnownAnswerInfo> mSrcKnownAnswers = new ArrayMap<>();

    private class KnownAnswerInfo {
        final List<MdnsRecord> mKnownAnswers = new ArrayList<>();
        boolean mHasRemainingPackets = false;
    }

    /**
     * Dependencies of MdnsReplySender, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        /**
         * @see Handler#sendMessageDelayed(Message, long)
         */
        public void sendMessageDelayed(@NonNull Handler handler, @NonNull Message message,
                long delayMillis) {
            handler.sendMessageDelayed(message, delayMillis);
        }

        /**
         * @see Handler#removeMessages(int)
         */
        public void removeMessages(@NonNull Handler handler, int what) {
            handler.removeMessages(what);
        }
    }

    public MdnsReplySender(@NonNull Looper looper, @NonNull MdnsInterfaceSocket socket,
            @NonNull byte[] packetCreationBuffer, @NonNull SharedLog sharedLog,
            boolean enableDebugLog) {
        this(looper, socket, packetCreationBuffer, sharedLog, enableDebugLog, new Dependencies());
    }

    @VisibleForTesting
    public MdnsReplySender(@NonNull Looper looper, @NonNull MdnsInterfaceSocket socket,
            @NonNull byte[] packetCreationBuffer, @NonNull SharedLog sharedLog,
            boolean enableDebugLog, @NonNull Dependencies dependencies) {
        mHandler = new SendHandler(looper);
        mSocket = socket;
        mPacketCreationBuffer = packetCreationBuffer;
        mSharedLog = sharedLog;
        mEnableDebugLog = enableDebugLog;
        mDependencies = dependencies;
    }

    /**
     * Queue a reply to be sent when its send delay expires.
     */
    public void queueReply(@NonNull MdnsReplyInfo reply, @Nullable InetSocketAddress source) {
        ensureRunningOnHandlerThread(mHandler);
        // TODO: implement response aggregation (RFC 6762 6.4)
        mDependencies.sendMessageDelayed(
                mHandler, mHandler.obtainMessage(MSG_SEND, reply), reply.sendDelayMs);

        if (mEnableDebugLog) {
            mSharedLog.v("Scheduling " + reply);
        }

        if (source != null) {
            mSrcKnownAnswers.put(source, new KnownAnswerInfo());
        }
    }

    /**
     * Send a packet immediately.
     *
     * Must be called on the looper thread used by the {@link MdnsReplySender}.
     */
    public int sendNow(@NonNull MdnsPacket packet, @NonNull InetSocketAddress destination)
            throws IOException {
        ensureRunningOnHandlerThread(mHandler);
        if (!((destination.getAddress() instanceof Inet6Address && mSocket.hasJoinedIpv6())
                || (destination.getAddress() instanceof Inet4Address && mSocket.hasJoinedIpv4()))) {
            // Skip sending if the socket has not joined the v4/v6 group (there was no address)
            return PACKET_NOT_SENT;
        }
        final byte[] outBuffer = MdnsUtils.createRawDnsPacket(mPacketCreationBuffer, packet);
        mSocket.send(new DatagramPacket(outBuffer, 0, outBuffer.length, destination));
        return PACKET_SENT;
    }

    /**
     * Cancel all pending sends.
     */
    public void cancelAll() {
        ensureRunningOnHandlerThread(mHandler);
        mDependencies.removeMessages(mHandler, MSG_SEND);
    }

    /**
     * Add known answers for given source address and indicate if there are still remaining packets.
     */
    public void updateKnownAnswers(InetSocketAddress src, List<MdnsRecord> knownAnswers,
            boolean hasRemainingPackets) {
        ensureRunningOnHandlerThread(mHandler);
        final KnownAnswerInfo info = mSrcKnownAnswers.get(src);
        if (info != null) {
            info.mKnownAnswers.addAll(knownAnswers);
            info.mHasRemainingPackets = hasRemainingPackets;
        }
    }

    private class SendHandler extends Handler {
        SendHandler(@NonNull Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            final MdnsReplyInfo replyInfo = (MdnsReplyInfo) msg.obj;
            // RFC6762 7.2. Multipacket Known-Answer Suppression
            // If the responder sees any of its answers listed in the Known-Answer
            // lists of subsequent packets from the querying host, it MUST delete
            // that answer from the list of answers it is planning to give.
            final KnownAnswerInfo info = mSrcKnownAnswers.get(replyInfo.source);
            if (info != null) {
                // RFC6762 7.2. Multipacket Known-Answer Suppression
                // If the responder receives additional Known-Answer packets with the TC
                // bit set, it SHOULD extend the delay as necessary to ensure a pause of
                // 400-500 ms after the last such packet before it sends its answer.
                if (info.mHasRemainingPackets) {
                    info.mHasRemainingPackets = false;
                    mDependencies.sendMessageDelayed(mHandler,
                            mHandler.obtainMessage(MSG_SEND, replyInfo), replyInfo.sendDelayMs);
                    return;
                }

                // Remove duplication by comparing the known answers to the replied answers.
                final Iterator<MdnsRecord> iterator = replyInfo.answers.iterator();
                while (iterator.hasNext()) {
                    final MdnsRecord answer = iterator.next();
                    for (MdnsRecord knownAnswer : info.mKnownAnswers) {
                        if (knownAnswer.equals(answer)
                                && (knownAnswer.getTtl() > (answer.getTtl() / 2))) {
                            iterator.remove();
                        }
                    }
                }

                // Clear the known-answer list after examine.
                mSrcKnownAnswers.remove(replyInfo.source);

                // Evaluate the remaining answers to determine if a reply should be transmitted.
                if (replyInfo.answers.isEmpty()) {
                    mSharedLog.log("Replied answers are all known answers. Ignore the reply.");
                    return;
                }
            }

            if (mEnableDebugLog) mSharedLog.v("Sending " + replyInfo);

            final int flags = 0x8400; // Response, authoritative (rfc6762 18.4)
            final MdnsPacket packet = new MdnsPacket(flags,
                    Collections.emptyList() /* questions */,
                    replyInfo.answers,
                    Collections.emptyList() /* authorityRecords */,
                    replyInfo.additionalAnswers);

            try {
                sendNow(packet, replyInfo.destination);
            } catch (IOException e) {
                mSharedLog.e("Error sending MDNS response", e);
            }
        }
    }
}
