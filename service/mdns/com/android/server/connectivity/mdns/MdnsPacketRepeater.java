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
import android.annotation.Nullable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.net.SocketAddress;

/**
 * A class used to send several packets at given time intervals.
 * @param <T> The type of the request providing packet repeating parameters.
 */
public abstract class MdnsPacketRepeater<T extends MdnsPacketRepeater.Request> {
    @NonNull
    private final MdnsReplySender mReplySender;
    @NonNull
    protected final Handler mHandler;
    @Nullable
    private final PacketRepeaterCallback<T> mCb;
    private final int mNumSends;

    /**
     * Status callback from {@link MdnsPacketRepeater}.
     *
     * Callbacks are called on the {@link MdnsPacketRepeater} handler thread.
     * @param <T> The type of the request providing packet repeating parameters.
     */
    public interface PacketRepeaterCallback<T extends MdnsPacketRepeater.Request> {
        /**
         * Called when the {@link MdnsPacketRepeater} is done sending packets.
         */
        void onFinished(@NonNull T info);
    }

    /**
     * A request to repeat packets.
     *
     * All methods are called in the looper thread.
     */
    public interface Request {
        /**
         * Get a packet to send for one iteration.
         */
        @NonNull
        MdnsPacket getPacket(int index);

        /**
         * Get a set of destinations for the packet for one iteration.
         */
        @NonNull
        Iterable<SocketAddress> getDestinations(int index);
    }

    protected abstract long getDelayMs(int nextIndex);

    /**
     * Get the logging tag to use.
     */
    @NonNull
    protected abstract String getTag();

    private final class ProbeHandler extends Handler {
        ProbeHandler(@NonNull Looper looper) {
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

            final MdnsPacket packet = request.getPacket(index);
            final Iterable<SocketAddress> destinations = request.getDestinations(index);
            if (MdnsAdvertiser.DBG) {
                Log.v(getTag(), "Sending packets to " + destinations + " for iteration "
                        + index + " out of " + mNumSends);
            }
            for (SocketAddress destination : destinations) {
                try {
                    mReplySender.sendNow(packet, destination);
                } catch (IOException e) {
                    Log.e(getTag(), "Error sending packet to " + destination, e);
                }
            }

            int nextIndex = index + 1;
            // No need to go through the last handler loop if there's no callback to call
            if (nextIndex >= mNumSends && mCb == null) return;
            // TODO: consider using AlarmManager / WakeupMessage to avoid missing sending during
            // deep sleep; but this would affect battery life, and discovered services are likely
            // not to be available since the device is in deep sleep anyway.
            final long delay = getDelayMs(nextIndex);
            sendMessageDelayed(obtainMessage(msg.what, nextIndex, 0, request), delay);
            if (MdnsAdvertiser.DBG) Log.v(getTag(), "Scheduled next packet in " + delay + "ms");
        }
    }

    protected MdnsPacketRepeater(@NonNull Looper looper, @NonNull MdnsReplySender replySender,
            @Nullable PacketRepeaterCallback<T> cb, int numSends) {
        mHandler = new ProbeHandler(looper);
        mReplySender = replySender;
        mCb = cb;
        mNumSends = numSends;
    }

    protected void startSending(int id, @NonNull T request, long initialDelayMs) {
        if (MdnsAdvertiser.DBG) {
            Log.v(getTag(), "Starting send with id " + id + ", request "
                    + request.getClass().getSimpleName() + ", delay " + initialDelayMs);
        }
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
        if (MdnsAdvertiser.DBG) {
            Log.v(getTag(), "Stopping send on id " + id);
        }
        if (mHandler.hasMessages(id)) {
            mHandler.removeMessages(id);
            return true;
        }
        return false;
    }
}
