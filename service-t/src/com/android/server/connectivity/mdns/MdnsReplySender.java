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
import android.annotation.RequiresApi;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import com.android.net.module.util.SharedLog;
import com.android.server.connectivity.mdns.MdnsRecordRepository.ReplyInfo;

import java.io.IOException;
import java.net.MulticastSocket;
import java.util.Collections;

/**
 * A class that handles sending mDNS replies to a {@link MulticastSocket}, possibly queueing them
 * to be sent after some delay.
 *
 * TODO: implement sending after a delay, combining queued replies and duplicate answer suppression
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public class MdnsReplySender extends MdnsPacketSender {
    private static final int MSG_SEND = 1;

    @NonNull
    private final Handler mHandler;

    public MdnsReplySender(@NonNull Looper looper, @NonNull MdnsInterfaceSocket socket,
            @NonNull byte[] packetCreationBuffer, @NonNull SharedLog sharedLog) {
        super(looper, socket, packetCreationBuffer, sharedLog);
        mHandler = new SendHandler(looper);
    }

    /**
     * Queue a reply to be sent when its send delay expires.
     */
    public void queueReply(@NonNull ReplyInfo reply) {
        ensureRunningOnHandlerThread(mHandler);
        // TODO: implement response aggregation (RFC 6762 6.4)
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_SEND, reply), reply.sendDelayMs);

        if (DBG) {
            mSharedLog.v("Scheduling " + reply);
        }
    }

    /**
     * Cancel all pending sends.
     */
    public void cancelAll() {
        ensureRunningOnHandlerThread(mHandler);
        mHandler.removeMessages(MSG_SEND);
    }

    private class SendHandler extends Handler {
        SendHandler(@NonNull Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            final ReplyInfo replyInfo = (ReplyInfo) msg.obj;
            if (DBG) mSharedLog.v("Sending " + replyInfo);

            final int flags = 0x8400; // Response, authoritative (rfc6762 18.4)
            final MdnsPacket packet = new MdnsPacket(flags,
                    Collections.emptyList() /* questions */,
                    replyInfo.answers,
                    Collections.emptyList() /* authorityRecords */,
                    replyInfo.additionalAnswers);

            try {
                sendNow(packet, replyInfo.destination, false /* onlyUseIpv6OnIpv6OnlyNetworks */,
                        false /* expectUnicastResponse */);
            } catch (IOException e) {
                mSharedLog.e("Error sending MDNS response", e);
            }
        }
    }
}
