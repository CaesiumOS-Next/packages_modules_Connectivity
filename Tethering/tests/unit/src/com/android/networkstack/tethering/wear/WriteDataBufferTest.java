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

package com.android.networkstack.tethering.wear;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.networkstack.tethering.wear.WearableConnection.WriteDataBuffer;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class WriteDataBufferTest {

    private final byte[] mTestBytes8 = {
        0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
    };
    private final byte[] mTestBytesL = new byte[4096];
    WriteDataBuffer mBuffer;

    @Test
    public void onBufferAvailable() {
        mBuffer = new WriteDataBuffer(mTestBytesL, 0, 2674, 2674);
        AtomicReference<WriteDataBuffer> captureBuffer = new AtomicReference<>();
        AtomicInteger captureAvailable = new AtomicInteger();

        mBuffer.setOnBufferAvailable(
                1522,
                (b, len) -> {
                    captureBuffer.set(b);
                    captureAvailable.set(len);
                });
        mBuffer.advance(10);
        assertNull(captureBuffer.get());

        mBuffer.advance(100);

        assertSame(mBuffer, captureBuffer.get());
        assertEquals(1532, captureAvailable.get());
    }

    @Test
    public void remaining() {
        mBuffer = new WriteDataBuffer(mTestBytes8, 0, 7, 7);

        assertEquals(7, mBuffer.remaining());
        mBuffer.advance(7);
        assertEquals(0, mBuffer.remaining());
        mBuffer.put(new byte[5], 0, 5);
        assertEquals(5, mBuffer.remaining());
    }

    @Test
    public void remainingWrite() {
        // Tun to know if can keep reading
        mBuffer = new WriteDataBuffer(mTestBytes8, 0, 7, 7);

        assertEquals(1, mBuffer.remainingWrite());
    }

    @Test
    public void get() {
        byte[] bytes = new byte[8];
        Arrays.fill(bytes, (byte) -3);
        mBuffer = new WriteDataBuffer(mTestBytes8, 0, 7, 7);

        mBuffer.get(2, bytes, 1, 4);
        assertArrayEquals(new byte[] {-3, 0x12, 0x13, 0x14, 0x15, -3, -3, -3}, bytes);

        mBuffer = new WriteDataBuffer(mTestBytes8, 6, 6, 8);
        Arrays.fill(bytes, (byte) -3);
        mBuffer.get(1, bytes, 3, 4);
        assertArrayEquals(new byte[] {-3, -3, -3, 0x17, 0x10, 0x11, 0x12, -3}, bytes);

        mBuffer = new WriteDataBuffer(mTestBytes8, 6, 5, 7);
        Arrays.fill(bytes, (byte) -3);
        mBuffer.get(3, bytes, 3, 4);
        assertArrayEquals(new byte[] {-3, -3, -3, 0x11, 0x12, 0x13, 0x14, -3}, bytes);
    }

    @Test
    public void put() {
        byte[] bytes = Arrays.copyOf(mTestBytes8, 8);
        mBuffer = new WriteDataBuffer(bytes, 0, 0, 0);
        mBuffer.put(new byte[] {-1, -2, -3}, 0, 2);
        assertEquals("{c=8,s=2,r=0,w=2}", mBuffer.toString());
        assertArrayEquals(
                new byte[] {
                    -1, -2, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
                },
                bytes);

        mBuffer.put(new byte[] {-10, -12, -13}, 0, 3);
        assertEquals("{c=8,s=5,r=0,w=5}", mBuffer.toString());
        assertArrayEquals(
                new byte[] {
                    -1, -2, -10, -12, -13, 0x15, 0x16, 0x17,
                },
                bytes);

        mBuffer = new WriteDataBuffer(mTestBytes8, 4, 5, 1);
        mBuffer.put(new byte[] {-11, -12, -13, -14}, 0, 4);
        assertEquals("{c=8,s=5,r=4,w=1}", mBuffer.toString());
        assertArrayEquals(
                new byte[] {
                    -14, 0x11, 0x12, 0x13, 0x14, -11, -12, -13,
                },
                mTestBytes8);
    }

    @Test
    public void testToString() {
        mBuffer = new WriteDataBuffer(mTestBytes8, 6, 5, 7);
        assertEquals("{c=8,s=7,r=6,w=5}", mBuffer.toString());
    }
}
