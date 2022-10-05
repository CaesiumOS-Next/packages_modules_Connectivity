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

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.MulticastSocket;

public class MdnsReplySender {
    @NonNull
    private final String mLogTag;
    @NonNull
    private final Handler mHandler;
    @NonNull
    private final MulticastSocket mSocket;
    @NonNull
    private final byte[] mPacketCreationBuffer;

    public MdnsReplySender(@NonNull String interfaceTag, @NonNull Looper looper,
            @NonNull MulticastSocket socket, @NonNull byte[] packetCreationBuffer) {
        mLogTag = MdnsReplySender.class.getSimpleName() + "/" +  interfaceTag;
        mHandler = new SendHandler(looper);
        mSocket = socket;
        mPacketCreationBuffer = packetCreationBuffer;
    }

    public void queueReply(ReplyInfo reply) {
        // TODO: implement response aggregation (RFC 6762 6.4)
        mHandler.sendMessageDelayed(mHandler.obtainMessage(0, reply), reply.sendDelayMs);

        if (MdnsAdvertiser.DBG) {
            Log.v(mLogTag, "Scheduling " + reply);
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

            // TODO: support packets over size
            // TODO: consider factoring out with ProbingInfo, MdnsAnnouncer
            final MdnsPacketWriter writer = new MdnsPacketWriter(mPacketCreationBuffer);

            try {
                writer.writeUInt16(0); // Transaction ID (advertisement: 0)
                writer.writeUInt16(0x8400); // Response, authoritative (rfc6762 18.4)
                writer.writeUInt16(0); // questions count
                writer.writeUInt16(replyInfo.answers.size()); // answers count
                writer.writeUInt16(0); // authority entries count
                writer.writeUInt16(replyInfo.additionalAnswers.size()); // additional records count

                for (MdnsRecord record : replyInfo.answers) {
                    record.write(writer, 0L);
                }

                for (MdnsRecord record : replyInfo.additionalAnswers) {
                    record.write(writer, 0L);
                }

                final int len = writer.getWritePosition();
                final byte[] outBuffer = new byte[len];
                System.arraycopy(mPacketCreationBuffer, 0, outBuffer, 0, len);

                mSocket.send(new DatagramPacket(outBuffer, 0, len, replyInfo.destination));
            } catch (IOException e) {
                Log.e(mLogTag, "Error sending MDNS response", e);
                // TODO: remove

                try {
                    Log.e(mLogTag, "Interface addresses: " + TextUtils.join(", ", mSocket.getNetworkInterface().getInterfaceAddresses()));
                } catch (Exception ee) {
                    Log.e(mLogTag, "Error logging error: ", ee);
                }
            }
        }
    }
}
