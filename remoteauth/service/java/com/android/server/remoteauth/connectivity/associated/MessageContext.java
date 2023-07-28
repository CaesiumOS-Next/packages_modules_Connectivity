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

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Class encapsulating the byte[] message to the peer device. Callers will use this class to set the
 * buffer contents only. It is used by connectivity layer to wrap the message with a messageId and
 * connection information.
 */
public final class MessageContext {
    private static final String TAG = "MessageContext";

    private final long mMessageId;
    private final ConnectionInfo mConnectionInfo;

    private byte[] mBuffer;

    MessageContext(long messageId, ConnectionInfo connectionInfo) {
        mMessageId = messageId;
        mConnectionInfo = connectionInfo;
    }

    public ConnectionInfo getConnectionInfo() {
        return mConnectionInfo;
    }

    /**
     * Method to allow callers to set the buffer.
     * @param buffer
     */
    public void setBuffer(byte[] buffer) {
        mBuffer = ByteBuffer.allocate(buffer.length).put(buffer).array();
    }

    /**
     * Method to get the wrapped byte array including the header which has the message id.
     *
     * @return byte array with the header.
     */
    public byte[] getByteArrayWithHeader() {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] header =
                ByteBuffer.allocate(Long.SIZE / Byte.SIZE).putLong(mMessageId).array();
        try {
            outputStream.write(header);
            outputStream.write(mBuffer);
        } catch (java.io.IOException e) {
            Log.e(TAG, "Failed to write to output stream: " + e);
        }
        return outputStream.toByteArray();
    }
}
