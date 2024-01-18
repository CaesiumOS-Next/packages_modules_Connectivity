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

package com.android.net.module.util.netlink.xfrm;

import androidx.annotation.NonNull;

import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.ByteBuffer;

/**
 * Struct xfrm_usersa_info
 *
 * <p>see include/uapi/linux/xfrm.h
 *
 * <pre>
 * struct xfrm_usersa_info {
 *      struct xfrm_selector sel;
 *      struct xfrm_id id;
 *      xfrm_address_t saddr;
 *      struct xfrm_lifetime_cfg lft;
 *      struct xfrm_lifetime_cur curlft;
 *      struct xfrm_stats stats;
 *      __u32 seq;
 *      __u32 reqid;
 *      __u16 family;
 *      __u8 mode;
 *      __u8 replay_window;
 *      __u8 flags;
 * };
 * </pre>
 *
 * @hide
 */
public class StructXfrmUsersaInfo {
    private static final int KERNEL_64_EXTRA_PAD_SIZE = 4;

    private final StructXfrmUsersaInfo32 mXfrmUsersaInfo32;
    private final boolean mIsKernel64Bit;

    public static StructXfrmUsersaInfo parse(
            @NonNull final ByteBuffer byteBuffer, boolean isKernel64Bit) {
        final StructXfrmUsersaInfo32 xfrmUsersaInfo32 =
                StructXfrmUsersaInfo32.parse(StructXfrmUsersaInfo32.class, byteBuffer);

        if (isKernel64Bit) {
            // Cosume extra paddings
            byteBuffer.get(new byte[KERNEL_64_EXTRA_PAD_SIZE]);
        }

        return new StructXfrmUsersaInfo(xfrmUsersaInfo32, isKernel64Bit);
    }

    private StructXfrmUsersaInfo(
            @NonNull final StructXfrmUsersaInfo32 xfrmUsersaInfo32, boolean isKernel64Bit) {
        mXfrmUsersaInfo32 = xfrmUsersaInfo32;
        mIsKernel64Bit = isKernel64Bit;
    }

    // Constructor to build a new message for TESTING
    public StructXfrmUsersaInfo(
            @NonNull final InetAddress dAddr,
            @NonNull final InetAddress sAddr,
            @NonNull final BigInteger addTime,
            int selectorFamily,
            long spi,
            long seq,
            long reqId,
            short proto,
            short mode,
            short replayWindowLegacy,
            short flags,
            boolean isKernel64Bit) {
        mXfrmUsersaInfo32 =
                new StructXfrmUsersaInfo32(
                        dAddr,
                        sAddr,
                        addTime,
                        selectorFamily,
                        spi,
                        seq,
                        reqId,
                        proto,
                        mode,
                        replayWindowLegacy,
                        flags);
        mIsKernel64Bit = isKernel64Bit;
    }

    public StructXfrmUsersaInfo(
            @NonNull final InetAddress dAddr,
            @NonNull final InetAddress sAddr,
            long spi,
            long seq,
            long reqId,
            short proto,
            short mode,
            short replayWindowLegacy,
            short flags,
            boolean isKernel64Bit) {
        mXfrmUsersaInfo32 =
                new StructXfrmUsersaInfo32(
                        dAddr, sAddr, spi, seq, reqId, proto, mode, replayWindowLegacy, flags);
        mIsKernel64Bit = isKernel64Bit;
    }

    /** Convert the parsed object to ByteBuffer. */
    public void writeToByteBuffer(@NonNull final ByteBuffer buf) {
        mXfrmUsersaInfo32.writeToByteBuffer(buf);
        if (mIsKernel64Bit) {
            buf.put(new byte[KERNEL_64_EXTRA_PAD_SIZE]);
        }
    }

    /** Return the struct size */
    public int getStructSize() {
        return StructXfrmUsersaInfo32.STRUCT_SIZE + (mIsKernel64Bit ? KERNEL_64_EXTRA_PAD_SIZE : 0);
    }

    /** Return the destination address */
    public InetAddress getDestAddress() {
        return mXfrmUsersaInfo32.getDestAddress();
    }

    /** Return the source address */
    public InetAddress getSrcAddress() {
        return mXfrmUsersaInfo32.getSrcAddress();
    }

    /** Return the SPI */
    public long getSpi() {
        return mXfrmUsersaInfo32.getSpi();
    }

    /** Return the current lifetime */
    public StructXfrmLifetimeCur getCurrentLifetime() {
        return mXfrmUsersaInfo32.getCurrentLifetime();
    }
}
