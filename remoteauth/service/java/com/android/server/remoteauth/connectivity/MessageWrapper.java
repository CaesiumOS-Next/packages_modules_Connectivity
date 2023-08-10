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

package com.google.android.server.remoteauth.connectivity;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Log;
import android.util.Pair;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.Arrays;

/**
 * Class that wraps and unwraps a given byte buffer. Wrapping refers to adding a message header
 * to the given buffer and unwrapping refers to removing the header.
 */
public final class MessageWrapper {
    private static final String TAG = "MessageWrapper";
    private static final int HEADER_SIZE = Long.BYTES;

    /**
     * Splits the buffer into message id and payload.
     *
     * @return pair of message id and buffer without the header or null.
     */
    @Nullable
    public static Pair<Long, byte[]> unwrapMessage(@NonNull byte[] buffer) {
        if (buffer.length < HEADER_SIZE) {
            Log.e(TAG, "Buffer has insufficient length");
            return null;
        }

        long messageId = ByteBuffer.wrap(buffer).getLong();
        return new Pair<>(messageId, Arrays.copyOfRange(buffer, HEADER_SIZE, buffer.length));
    }

    /**
     * Prepends the contents in buffer with message id.
     *
     * @param messageId id of the message.
     * @param buffer payload to be sent to the client.
     * @return wrapped message as byte array or null.
     */
    @Nullable
    public static byte[] wrapMessage(long messageId, @NonNull byte[] buffer) {
        byte[] combined = new byte[HEADER_SIZE + buffer.length];
        ByteBuffer combinedByteBuffer = ByteBuffer.wrap(combined);

        try {
            combinedByteBuffer.put(ByteBuffer.allocate(HEADER_SIZE).putLong(messageId).array());
            combinedByteBuffer.put(buffer);
            return combinedByteBuffer.array();
        } catch (BufferOverflowException | ReadOnlyBufferException e) {
            Log.e(TAG, "Error while wrapping message, " + e);
        }
        return null;
    }

    private MessageWrapper() {}
}
