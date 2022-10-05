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
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;

import com.android.server.connectivity.mdns.MdnsRecordRepository.ReplyInfo;

import java.io.EOFException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.MulticastSocket;
import java.net.SocketAddress;
import java.util.Collections;

/**
 * A class that handles sending mDNS replies to a {@link MulticastSocket}, possibly queueing them
 * to be sent after some delay.
 *
 * TODO: implement sending after a delay, combining queued replies and duplicate answer suppression
 */
public class MdnsReplySender {
    private static final int FLAG_TRUNCATED = 0x400;

    @NonNull
    private final String mLogTag;
    @NonNull
    private final MulticastSocket mSocket;
    @NonNull
    private final Handler mHandler;
    @NonNull
    private final byte[] mPacketCreationBuffer;

    public MdnsReplySender(@NonNull String interfaceTag, @NonNull Looper looper,
            @NonNull MulticastSocket socket, @NonNull byte[] packetCreationBuffer) {
        mHandler = new SendHandler(looper);
        mLogTag = MdnsReplySender.class.getSimpleName() + "/" +  interfaceTag;
        mSocket = socket;
        mPacketCreationBuffer = packetCreationBuffer;
    }

    public void queueReply(@NonNull ReplyInfo reply) {
        // TODO: implement response aggregation (RFC 6762 6.4)
        mHandler.sendMessageDelayed(mHandler.obtainMessage(0, reply), reply.sendDelayMs);

        if (MdnsAdvertiser.DBG) {
            Log.v(mLogTag, "Scheduling " + reply);
        }
    }

    /**
     * Send a packet immediately.
     *
     * Must be called on the looper thread used by the {@link MdnsReplySender}.
     */
    public void sendNow(@NonNull MdnsPacket packet, @NonNull SocketAddress destination)
            throws IOException {
        if (Thread.currentThread() != mHandler.getLooper().getThread()) {
            throw new IllegalStateException("sendNow must be called in the handler thread");
        }

        // If a MdnsPacket can't fit in one actual packet, send multiple packets with the TC bit set
        MdnsPacket remainingPacket = packet;
        while (remainingPacket != null) {
            final MdnsPacketWriter writer = new MdnsPacketWriter(mPacketCreationBuffer);
            writer.writeUInt16(0); // Transaction ID (advertisement: 0)
            final int flagsPos = writer.getWritePosition();
            writer.writeUInt16(0); // Flags, written later

            writer.writeUInt16(0); // questions count, written later
            writer.writeUInt16(0); // answers count, written later
            writer.writeUInt16(0); // authority entries count, written later
            writer.writeUInt16(0); // additional records count, written later

            int writtenQuestions = 0;
            int writtenAnswers = 0;
            int writtenAuthority = 0;
            int writtenAdditional = 0;
            int lastValidPos = writer.getWritePosition();

            try {
                for (MdnsRecord record : remainingPacket.questions) {
                    // Questions do not have TTL or data
                    record.writeHeaderFields(writer);
                    writtenQuestions++;
                    lastValidPos = writer.getWritePosition();
                }
                for (MdnsRecord record : remainingPacket.answers) {
                    record.write(writer, 0L);
                    writtenAnswers++;
                    lastValidPos = writer.getWritePosition();
                }
                for (MdnsRecord record : remainingPacket.authorityRecords) {
                    record.write(writer, 0L);
                    writtenAuthority++;
                    lastValidPos = writer.getWritePosition();
                }
                // TODO: should not do TC / continuation for additional records ?
                for (MdnsRecord record : remainingPacket.additionalRecords) {
                    record.write(writer, 0L);
                    writtenAdditional++;
                    lastValidPos = writer.getWritePosition();
                }
                remainingPacket = null;
            } catch (EOFException e) {
                // Went over the packet limit; truncate and send the rest separately
                if (writtenQuestions == 0 && writtenAnswers == 0 && writtenAuthority == 0
                        && writtenAdditional == 0) {
                    // No space to write even one record: just throw (as subclass of IOException)
                    throw e;
                }

                // Set the last valid position as the final position (not as a rewind)
                writer.rewind(lastValidPos);
                writer.clearRewind();

                remainingPacket = new MdnsPacket(packet.flags,
                        remainingPacket.questions.subList(
                                writtenQuestions, remainingPacket.questions.size()),
                        remainingPacket.answers.subList(
                                writtenAnswers, remainingPacket.answers.size()),
                        remainingPacket.authorityRecords.subList(
                                writtenAuthority, remainingPacket.authorityRecords.size()),
                        remainingPacket.additionalRecords.subList(
                                writtenAdditional, remainingPacket.additionalRecords.size()));
            }

            final int len = writer.getWritePosition();
            writer.rewind(flagsPos);
            writer.writeUInt16(packet.flags | (remainingPacket == null ? 0 : FLAG_TRUNCATED));
            writer.writeUInt16(writtenQuestions);
            writer.writeUInt16(writtenAnswers);
            writer.writeUInt16(writtenAuthority);
            writer.writeUInt16(writtenAdditional);
            writer.unrewind();

            final byte[] outBuffer = new byte[len];
            System.arraycopy(mPacketCreationBuffer, 0, outBuffer, 0, len);

            mSocket.send(new DatagramPacket(outBuffer, 0, len, destination));
        }
    }

    private class SendHandler extends Handler {
        public SendHandler(@NonNull Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            final ReplyInfo replyInfo = (ReplyInfo) msg.obj;
            if (MdnsAdvertiser.DBG) Log.v(mLogTag, "Sending " + replyInfo);

            final MdnsPacket packet = new MdnsPacket(0x8400 /* flags */,
                    Collections.emptyList() /* questions */,
                    replyInfo.answers,
                    Collections.emptyList() /* authorityRecords */,
                    replyInfo.additionalAnswers);

            try {
                sendNow(packet, replyInfo.destination);
            } catch (IOException e) {
                Log.e(mLogTag, "Error sending MDNS response", e);
                // TODO: remove

                try {
                    Log.e(mLogTag, "Interface addresses: " + TextUtils.join(", ",
                            mSocket.getNetworkInterface().getInterfaceAddresses()));
                } catch (Exception ee) {
                    Log.e(mLogTag, "Error logging error: ", ee);
                }
            }
        }
    }
}
