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

import android.net.IpPrefix;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.net.module.util.Struct;
import com.android.net.module.util.structs.PrefixInformationOption;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * The Prefix Information Option. RFC 4861.
 *
 * 0                   1                   2                   3
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |     Type      |    Length     | Prefix Length |L|A|R|P| Rsvd1 |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                         Valid Lifetime                        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                       Preferred Lifetime                      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                           Reserved2                           |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                                                               |
 * +                                                               +
 * |                                                               |
 * +                            Prefix                             +
 * |                                                               |
 * +                                                               +
 * |                                                               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
public class StructNdOptPio extends NdOption {
    private static final String TAG = StructNdOptPio.class.getSimpleName();
    public static final int TYPE = 3;
    public static final byte LENGTH = 4; // Length in 8-byte units

    public final byte flags;
    public final long preferred;
    public final long valid;
    @NonNull
    public final IpPrefix prefix;

    public StructNdOptPio(byte flags, long preferred, long valid, @NonNull final IpPrefix prefix) {
        super((byte) TYPE, LENGTH);
        Objects.requireNonNull(prefix, "prefix must not be null");
        this.flags = flags;
        this.preferred = preferred;
        this.valid = valid;
        this.prefix = prefix;
    }

    /**
     * Parses a PrefixInformation option from a {@link ByteBuffer}.
     *
     * @param buf The buffer from which to parse the option. The buffer's byte order must be
     *            {@link java.nio.ByteOrder#BIG_ENDIAN}.
     * @return the parsed option, or {@code null} if the option could not be parsed successfully.
     */
    public static StructNdOptPio parse(@NonNull ByteBuffer buf) {
        if (buf == null || buf.remaining() < LENGTH * 8) return null;
        try {
            final PrefixInformationOption pio = Struct.parse(PrefixInformationOption.class, buf);
            if (pio.type != TYPE) {
                throw new IllegalArgumentException("Invalid type " + pio.type);
            }
            if (pio.length != LENGTH) {
                throw new IllegalArgumentException("Invalid length " + pio.length);
            }

            final Inet6Address addr = (Inet6Address) InetAddress.getByAddress(pio.prefix);
            final IpPrefix prefix = new IpPrefix(addr, pio.length);
            return new StructNdOptPio(pio.flags, pio.preferredLifetime, pio.validLifetime, prefix);
        } catch (IllegalArgumentException | BufferUnderflowException | UnknownHostException e) {
            // Not great, but better than throwing an exception that might crash the caller.
            // Convention in this package is that null indicates that the option was truncated
            // or malformed, so callers must already handle it.
            Log.d(TAG, "Invalid PIO option: " + e);
            return null;
        }
    }

    protected void writeToByteBuffer(ByteBuffer buf) {
        final ByteBuffer pio =
                PrefixInformationOption.build(prefix, flags, valid, preferred);
        buf.put(pio);
    }

    /** Outputs the wire format of the option to a new big-endian ByteBuffer. */
    public ByteBuffer toByteBuffer() {
        final ByteBuffer buf = ByteBuffer.allocate(Struct.getSize(PrefixInformationOption.class));
        writeToByteBuffer(buf);
        buf.flip();
        return buf;
    }

    @Override
    @NonNull
    public String toString() {
        final StringJoiner sj = new StringJoiner(",", "[", "]");
        return String.format("NdOptPio(%s, %s, %s, %s)", flags, preferred, valid, prefix);
    }
}
