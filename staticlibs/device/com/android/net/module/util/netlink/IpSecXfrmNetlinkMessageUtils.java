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
package com.android.net.module.util.netlink;

import static com.android.net.module.util.Struct.Type;
import static com.android.net.module.util.Struct.Type.U16;
import static com.android.net.module.util.Struct.Type.U32;
import static com.android.net.module.util.Struct.Type.U8;

import com.android.net.module.util.Struct.Type;

import java.math.BigInteger;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

public class IpSecXfrmNetlinkMessageUtils {

    public static final int XFRMA_REPLAY_VAL = 10;
    public static final int XFRMA_REPLAY_ESN_VAL = 23;
    public static final BigInteger XFRM_INF = new BigInteger("FFFFFFFFFFFFFFFF", 16);

    public static void putFieldValue(
            final ByteBuffer output, final Type annotionType, final Object value, final int padding)
            throws BufferUnderflowException {
        switch (annotionType) {
            case U8:
                output.put((byte) (((short) value) & 0xFF));
                break;
            case U16:
                output.putShort((short) (((int) value) & 0xFFFF));
                break;
            case U32:
                output.putInt((int) (((long) value) & 0xFFFFFFFFL));
                break;
            default:
                throw new IllegalArgumentException("Unknown type:" + annotionType);
        }

        // padding zero after field value for alignment.
        for (int i = 0; i < padding; i++) output.put((byte) 0);
    }

    public static <T> T getFieldValue(
            final ByteBuffer buf, final Type annotionType, final Class<T> clazz, final int padding)
            throws BufferUnderflowException {
        final Object value;
        switch (annotionType) {
            case U8:
                value = (short) (buf.get() & 0xFF);
                break;
            case U16:
                value = (int) (buf.getShort() & 0xFFFF);
                break;
            case U32:
                value = (long) (buf.getInt() & 0xFFFFFFFFL);
                break;
            default:
                throw new IllegalArgumentException("Unknown type:" + annotionType);
        }

        // Skip the padding data for alignment if any.
        if (padding > 0) {
            buf.position(buf.position() + padding);
        }
        return (T) value;
    }
}
