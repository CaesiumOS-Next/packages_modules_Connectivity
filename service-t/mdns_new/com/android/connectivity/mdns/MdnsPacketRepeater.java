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

package com.android.connectivity.mdns;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.MulticastSocket;

public abstract class MdnsPacketRepeater<T> {
    private final MulticastSocket mSocket;
    protected final Handler mHandler;
    @Nullable
    private final PacketRepeaterCallback<T> mCb;
    private final int mNumSends;

    public interface PacketRepeaterCallback<T> {
        void onFinished(T info);
    }

    protected abstract long getDelayMs(int nextIndex);

    /**
     * Get a set of packet to send for one iteration.
     */
    protected abstract DatagramPacket[] getPackets(int index, T request);
    protected abstract String getTag();

    private final class ProbeHandler extends Handler {
        ProbeHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            final int index = msg.arg1;
            final T request = (T) msg.obj;

            if (index >= mNumSends) {
                if (mCb != null) {
                    mCb.onFinished(request);
                }
                return;
            }

            for (DatagramPacket packet : getPackets(index, request)) {
                try {
                    mSocket.send(packet);
                } catch (IOException e) {
                    Log.e(getTag(), "Error sending packet", e);
                }
            }

            int nextIndex = index + 1;
            // No need to go through the last handler loop if there's no callback to call
            if (nextIndex >= mNumSends && mCb == null) return;
            // TODO: use exact scheduling
            sendMessageDelayed(obtainMessage(msg.what, nextIndex, 0, request),
                    getDelayMs(nextIndex));
        }
    }

    protected MdnsPacketRepeater(Looper looper, MulticastSocket socket,
            @Nullable PacketRepeaterCallback<T> cb, int numSends) {
        mHandler = new ProbeHandler(looper);
        mSocket = socket;
        mCb = cb;
        mNumSends = numSends;
    }

    protected void startSending(int id, T request, long initialDelayMs) {
        mHandler.sendMessageDelayed(mHandler.obtainMessage(id, 0, 0, request), initialDelayMs);
    }

    /**
     * Stop sending the packets for the specified ID
     * @return true if probing was in progress, false if this was a no-op
     */
    public boolean stop(int id) {
        if (mHandler.getLooper().getThread() != Thread.currentThread()) {
            throw new IllegalStateException("stop can only be called from the looper thread");
        }
        if (mHandler.hasMessages(id)) {
            mHandler.removeMessages(id);
            return true;
        }
        return false;
    }
}
