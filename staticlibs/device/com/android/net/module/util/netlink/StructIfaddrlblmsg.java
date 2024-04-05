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

package com.android.net.module.util.netlink;

import static android.system.OsConstants.AF_INET6;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.net.module.util.Struct;
import com.android.net.module.util.Struct.Field;
import com.android.net.module.util.Struct.Type;

import java.nio.ByteBuffer;

/**
 * struct ifaddrlblmsg
 *
 * see also:
 *
 *     include/uapi/linux/if_addrlabel.h
 *
 * @hide
 */
public class StructIfaddrlblmsg extends Struct {
    public static final int STRUCT_SIZE = Struct.getSize(StructIfaddrlblmsg.class);

    @Field(order = 0, type = Type.U8, padding = 1)
    public final short family;    // Address family.
    @Field(order = 1, type = Type.U8, padding = 1)
    public final short prefixlen;  // Prefix length.
    // The kernel structure has a u8 flags field here, but it's never used.
    @Field(order = 2, type = Type.S32)
    public final int index;       // Interface index
    @Field(order = 3, type = Type.S32)
    public final int seq;         // Sequence number.

    public StructIfaddrlblmsg(short family, short prefixlen, int index, int seq) {
        this.family = family;
        this.prefixlen = prefixlen;
        this.index = index;
        this.seq = seq;
    }

    public StructIfaddrlblmsg(int prefixlen, int ifindex, int seq) {
        this((short) AF_INET6, (short) prefixlen, ifindex, seq);
    }

    public StructIfaddrlblmsg(int prefixlen) {
        this((short) AF_INET6, (short) prefixlen, 0, 0);
    }

    /**
     * Parse a StructIfaddrlblmsg struct from a {@link ByteBuffer}.
     *
     * @param byteBuffer The buffer from which to parse the StructIfaddrlblmsg struct.
     * @return the parsed StructIfaddrlblmsg struct, or {@code null} if the StructIfaddrlblmsg
     *         struct could not be parsed successfully (for example, if it was truncated).
     */
    @Nullable
    public static StructIfaddrlblmsg parse(@NonNull final ByteBuffer byteBuffer) {
        if (byteBuffer.remaining() < Struct.getSize(StructIfaddrlblmsg.class)) return null;

        // The ByteOrder must already have been set to native order.
        return Struct.parse(StructIfaddrlblmsg.class, byteBuffer);
    }

    /**
     * Write the StructIfaddrlblmsg struct to {@link ByteBuffer}.
     */
    public void pack(@NonNull final ByteBuffer byteBuffer) {
        // The ByteOrder must already have been set to native order.
        this.writeToByteBuffer(byteBuffer);
    }
}
