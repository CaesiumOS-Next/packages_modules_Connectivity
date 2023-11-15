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

import android.system.OsConstants;

import androidx.annotation.NonNull;

import com.android.net.module.util.Struct;
import com.android.net.module.util.Struct.Computed;
import com.android.net.module.util.Struct.Field;
import com.android.net.module.util.Struct.Type;

import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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
public class IpSecStructXfrmUsersaInfo extends Struct {
    private static final int NESTED_STRUCTS_SIZE =
            IpSecStructXfrmSelector.STRUCT_SIZE
                    + IpSecStructXfrmId.STRUCT_SIZE
                    + IpSecStructXfrmAddressT.STRUCT_SIZE
                    + IpSecStructXfrmLifetimeCfg.STRUCT_SIZE
                    + IpSecStructXfrmLifetimeCur.STRUCT_SIZE
                    + IpSecStructXfrmStats.STRUCT_SIZE;

    public static final int STRUCT_SIZE = NESTED_STRUCTS_SIZE + 20;

    @Computed private final IpSecStructXfrmSelector mSel;
    @Computed private final IpSecStructXfrmId mId;
    @Computed private final IpSecStructXfrmAddressT mSAddr;
    @Computed private final IpSecStructXfrmLifetimeCfg mLft;
    @Computed private final IpSecStructXfrmLifetimeCur mCurlft;
    @Computed private final IpSecStructXfrmStats mStats;

    @Field(order = 0, type = Type.ByteArray, arraysize = NESTED_STRUCTS_SIZE)
    public final byte[] nestedStructs;

    @Field(order = 1, type = Type.U32)
    public final long seq;

    @Field(order = 2, type = Type.U32)
    public final long reqId;

    @Field(order = 3, type = Type.U16)
    public final int family;

    @Field(order = 4, type = Type.U8)
    public final short mode;

    @Field(order = 5, type = Type.U8)
    public final short replayWindowLegacy;

    @Field(order = 6, type = Type.U8, padding = 7)
    public final short flags;

    // Constructor that allows Strutc.parse(Class<T>, ByteBuffer) to work
    public IpSecStructXfrmUsersaInfo(
            @NonNull final byte[] nestedStructs,
            long seq,
            long reqId,
            int family,
            short mode,
            short replayWindowLegacy,
            short flags) {
        this.nestedStructs = nestedStructs.clone();
        this.seq = seq;
        this.reqId = reqId;
        this.family = family;
        this.mode = mode;
        this.replayWindowLegacy = replayWindowLegacy;
        this.flags = flags;

        final ByteBuffer nestedStructsBuff = ByteBuffer.wrap(nestedStructs);
        nestedStructsBuff.order(ByteOrder.nativeOrder());

        mSel = Struct.parse(IpSecStructXfrmSelector.class, nestedStructsBuff);
        mId = Struct.parse(IpSecStructXfrmId.class, nestedStructsBuff);
        mSAddr = Struct.parse(IpSecStructXfrmAddressT.class, nestedStructsBuff);
        mLft = Struct.parse(IpSecStructXfrmLifetimeCfg.class, nestedStructsBuff);
        mCurlft = Struct.parse(IpSecStructXfrmLifetimeCur.class, nestedStructsBuff);
        mStats = Struct.parse(IpSecStructXfrmStats.class, nestedStructsBuff);
    }

    // Constructor to build a new message for TESTING
    IpSecStructXfrmUsersaInfo(
            @NonNull final InetAddress dAddr,
            @NonNull final InetAddress sAddr,
            @NonNull final BigInteger addTime,
            int selectorFamily,
            int family,
            long spi,
            long seq,
            long reqId,
            short proto,
            short mode,
            short replayWindowLegacy,
            short flags) {
        this.seq = seq;
        this.reqId = reqId;
        this.family = family;
        this.mode = mode;
        this.replayWindowLegacy = replayWindowLegacy;
        this.flags = flags;

        mSel = new IpSecStructXfrmSelector(selectorFamily);
        mId = new IpSecStructXfrmId(dAddr, spi, proto);
        mSAddr = new IpSecStructXfrmAddressT(sAddr);
        mLft = new IpSecStructXfrmLifetimeCfg();
        mCurlft =
                new IpSecStructXfrmLifetimeCur(
                        BigInteger.ZERO, BigInteger.ZERO, addTime, BigInteger.ZERO);
        mStats = new IpSecStructXfrmStats();

        final ByteBuffer nestedStructsBuff = ByteBuffer.allocate(NESTED_STRUCTS_SIZE);
        nestedStructsBuff.order(ByteOrder.nativeOrder());

        mSel.writeToByteBuffer(nestedStructsBuff);
        mId.writeToByteBuffer(nestedStructsBuff);
        mSAddr.writeToByteBuffer(nestedStructsBuff);
        mLft.writeToByteBuffer(nestedStructsBuff);
        mCurlft.writeToByteBuffer(nestedStructsBuff);
        mStats.writeToByteBuffer(nestedStructsBuff);

        this.nestedStructs = nestedStructsBuff.array();
    }

    // Constructor to build a new message
    public IpSecStructXfrmUsersaInfo(
            @NonNull final InetAddress dAddr,
            @NonNull final InetAddress sAddr,
            int family,
            long spi,
            long seq,
            long reqId,
            short proto,
            short mode,
            short replayWindowLegacy,
            short flags) {
        // Use AF_UNSPEC for all SAs selectors. In transport mode, kernel picks selector family
        // based on usersa->family, while in tunnel mode, the XFRM_STATE_AF_UNSPEC flag allows
        // dual-stack SAs.
        this(
                dAddr,
                sAddr,
                BigInteger.ZERO,
                OsConstants.AF_UNSPEC,
                family,
                spi,
                seq,
                reqId,
                proto,
                mode,
                replayWindowLegacy,
                flags);
    }

    /** Return the destination address */
    public InetAddress getDestAddress() {
        return mId.getDestAddress(family);
    }

    /** Return the source address */
    public InetAddress getSrcAddress() {
        return mSAddr.getAddress(family);
    }

    /** Return the SPI */
    public long getSpi() {
        return mId.spi;
    }

    public IpSecStructXfrmLifetimeCur getXfrmLifetimeCurrent() {
        return mCurlft;
    }
}
