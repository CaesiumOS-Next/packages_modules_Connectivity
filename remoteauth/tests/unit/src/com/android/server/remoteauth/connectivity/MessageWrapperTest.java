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

package com.android.server.remoteauth.connectivity;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import android.util.Pair;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.server.remoteauth.connectivity.MessageWrapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Unit tests for {@link MessageWrapper}. */
@RunWith(AndroidJUnit4.class)
public class MessageWrapperTest {
    private static final String RAW_BUFFER = "Raw buffer";
    private static final byte[] HEADER = new byte[Long.BYTES];
    private static final int WRAPPED_BUFFER_SIZE = RAW_BUFFER.length() + HEADER.length;

    @Before
    public void setUp() {}

    /** Helper method to create a wrapped buffer */
    private byte[] createWrappedBuffer(long messageId, byte[] buffer) {
        return ByteBuffer.allocate(HEADER.length + buffer.length)
                .putLong(messageId)
                .put(buffer)
                .array();
    }

    @Test
    public void testWrapMessage_succeeds() {
        byte[] wrappedBuffer =
                MessageWrapper.wrapMessage(
                        /* messageId= */ 1, RAW_BUFFER.getBytes(StandardCharsets.UTF_8));

        assertEquals(wrappedBuffer.length, WRAPPED_BUFFER_SIZE);
    }

    @Test
    public void testWrapEmptyMessage_succeeds() {
        byte[] wrappedBuffer = MessageWrapper.wrapMessage(/* messageId= */ 1, new byte[] {});

        // Since an empty buffer was wrapped, the wrapped buffer should be only be the header.
        assertEquals(wrappedBuffer.length, HEADER.length);
    }

    @Test
    public void testUnwrapMessage_succeeds() {
        long messageId = 1;
        byte[] rawBuffer = RAW_BUFFER.getBytes(StandardCharsets.UTF_8);
        byte[] wrappedBuffer = createWrappedBuffer(messageId, rawBuffer);

        Pair<Long, byte[]> pair = MessageWrapper.unwrapMessage(wrappedBuffer);

        assertEquals(messageId, pair.first.longValue());
        assertArrayEquals(rawBuffer, pair.second);
    }

    @Test
    public void testUnwrapEmptyMessage_succeeds() {
        long messageId = 1;
        byte[] rawBuffer = new byte[] {};
        byte[] wrappedBuffer = createWrappedBuffer(messageId, rawBuffer);

        Pair<Long, byte[]> pair = MessageWrapper.unwrapMessage(wrappedBuffer);

        assertEquals(messageId, pair.first.longValue());
        assertArrayEquals(rawBuffer, pair.second);
    }
}
