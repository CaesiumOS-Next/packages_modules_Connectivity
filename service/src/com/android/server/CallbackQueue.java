/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server;

import android.annotation.NonNull;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;

import java.util.Arrays;
import java.util.function.IntPredicate;

/**
 * A utility class to add/remove {@link NetworkCallback}s from a queue.
 *
 * <p>This is intended to be used as a temporary builder to create/modify callbacks stored in an int
 * array for memory efficiency.
 *
 * <p>This class is not thread-safe.
 */
public class CallbackQueue {
    /**
     * List of callbacks being modified.
     *
     * <p>The first 16 bits of each int is the netId; the last 16 bits are the callback index.
     */
    private int[] mCallbacks;
    private int mLength;
    public CallbackQueue(int[] initialCallbacks) {
        mCallbacks = initialCallbacks;
        mLength = initialCallbacks.length;
    }

    private static int getCallbackInt(int netId, int callbackId) {
        return netId << 16 | callbackId & 0xffff;
    }

    /**
     * A consumer interface for {@link #forEach(CallbackConsumer)}.
     */
    public interface CallbackConsumer {
        /**
         * Method called on each callback in the queue.
         */
        void accept(int netId, int callbackId);
    }

    /**
     * Iterate over all callbacks in the queue.
     */
    public void forEach(@NonNull CallbackConsumer consumer) {
        for (int i = 0; i < mLength; i++) {
            final int cbInt = mCallbacks[i];
            final int netId = cbInt >>> 16;
            final int callbackId = cbInt & 0xffff;
            consumer.accept(netId, callbackId);
        }
    }

    /**
     * Indicates whether the queue contains a callback for the given (netId, callbackId).
     */
    public boolean hasCallback(int netId, int callbackId) {
        int match = getCallbackInt(netId, callbackId);
        for (int i = 0; i < mLength; i++) {
            if (mCallbacks[i] == match) {
                return true;
            }
        }
        return false;
    }

    /**
     * Remove all callbacks for the given netId.
     *
     * @return true if at least one callback was removed.
     */
    public boolean removeCallbacksForNetId(int netId) {
        return removeCallbacks(cb -> (cb >>> 16) == netId);
    }

    /**
     * Remove all callbacks for the given netId and callbackId.
     * @return true if at least one callback was removed.
     */
    public boolean removeCallbacks(int netId, int callbackId) {
        final int cbInt = getCallbackInt(netId, callbackId);
        return removeCallbacks(cb -> cb == cbInt);
    }

    private boolean removeCallbacks(@NonNull IntPredicate predicate) {
        int newQueueLength = 0;
        for (int i = 0; i < mLength; i++) {
            final int cb = mCallbacks[i];
            mCallbacks[newQueueLength] = cb;
            if (!predicate.test(cb)) {
                newQueueLength++;
            }
        }
        if (mLength != newQueueLength) {
            mLength = newQueueLength;
            return true;
        }
        return false;
    }

    /**
     * Ensure the queue can hold at least the given number of callbacks without reallocating.
     */
    public void ensureHasCapacity(int capacity) {
        if (mCallbacks.length >= mLength + capacity) {
            return;
        }
        mCallbacks = Arrays.copyOf(mCallbacks, mLength + capacity);
    }

    /**
     * Add a callback at the end of the queue.
     */
    public void addCallback(int netId, int callbackId) {
        ensureHasCapacity(1);
        mCallbacks[mLength] = getCallbackInt(netId, callbackId);
        mLength++;
    }

    /**
     * Remove all callbacks from the queue.
     */
    public void clear() {
        mLength = 0;
    }

    /**
     * Get a memory-efficient copy which can be used to recreate the {@link CallbackQueue}.
     */
    public int[] toArray() {
        if (mCallbacks.length != mLength) {
            return Arrays.copyOf(mCallbacks, mLength);
        }
        return mCallbacks;
    }

    @NonNull
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("[");
        forEach((netId, callbackId) -> {
            sb.append(ConnectivityManager.getCallbackName(callbackId));
            sb.append('(').append(netId).append("),");
        });
        // Remove the last comma, except for an empty list which would just be "[" at this point
        if (sb.length() > 1) {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.append(']').toString();
    }
}
