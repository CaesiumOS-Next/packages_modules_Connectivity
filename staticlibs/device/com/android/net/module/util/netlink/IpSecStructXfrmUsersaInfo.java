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

import static com.android.net.module.util.netlink.IpSecXfrmNetlinkMessageUtils.getFieldValue;
import static com.android.net.module.util.netlink.IpSecXfrmNetlinkMessageUtils.putFieldValue;

import com.android.net.module.util.Struct.Type;

import java.net.InetAddress;
import java.nio.ByteBuffer;

/**
 * struct xfrm_usersa_info
 *
 * <p>see also:
 *
 * <p>include/uapi/linux/xfrm.h
 *
 * <p>struct xfrm_usersa_info { struct xfrm_selector sel; struct xfrm_id id; xfrm_address_t saddr;
 * struct xfrm_lifetime_cfg lft; struct xfrm_lifetime_cur curlft; struct xfrm_stats stats; __u32
 * seq; __u32 reqid; __u16 family; __u8 mode; __u8 replay_window; __u8 flags; };
 *
 * @hide
 */
public class IpSecStructXfrmUsersaInfo {
    // Already aligned.
    public static final int STRUCT_SIZE =
            IpSecStructXfrmSelector.STRUCT_SIZE
                    + IpSecStructXfrmId.STRUCT_SIZE
                    + IpSecStructXfrmAddressT.STRUCT_SIZE
                    + IpSecStructXfrmLifetimeCfg.STRUCT_SIZE
                    + IpSecStructXfrmLifetimeCur.STRUCT_SIZE
                    + IpSecStructXfrmStats.STRUCT_SIZE
                    + 20;

    private IpSecStructXfrmSelector mSel;
    private IpSecStructXfrmId mId;
    private IpSecStructXfrmAddressT mSAddr;
    private IpSecStructXfrmLifetimeCfg mLft;
    private IpSecStructXfrmLifetimeCur mCurlft;
    private IpSecStructXfrmStats mStats;
    private long mSeq; // U32
    private long mReqId; // U32
    private int mFamily; // U16
    private short mMode; // U8
    private short mReplayWindow; // U8
    private short mFlags; // U8

    public IpSecStructXfrmUsersaInfo() {}

    public void pack(ByteBuffer byteBuffer) {
        mSel.pack(byteBuffer);
        mId.pack(byteBuffer);
        mSAddr.pack(byteBuffer);
        mLft.pack(byteBuffer);
        mCurlft.pack(byteBuffer);
        mStats.pack(byteBuffer);

        putFieldValue(byteBuffer, Type.U32, mSeq, 0);
        putFieldValue(byteBuffer, Type.U32, mReqId, 0);
        putFieldValue(byteBuffer, Type.U16, mFamily, 0);
        putFieldValue(byteBuffer, Type.U8, mMode, 0);
        putFieldValue(byteBuffer, Type.U8, mReplayWindow, 0);
        putFieldValue(byteBuffer, Type.U8, mFlags, 7);
    }

    public static IpSecStructXfrmUsersaInfo parse(ByteBuffer buf) {
        final IpSecStructXfrmUsersaInfo struct = new IpSecStructXfrmUsersaInfo();

        android.util.Log.i("TEST", "parse mSel at buf.position() " + buf.position());
        struct.mSel = IpSecStructXfrmSelector.parse(IpSecStructXfrmSelector.class, buf);

        android.util.Log.i("TEST", "parse mId at buf.position() " + buf.position());
        struct.mId = IpSecStructXfrmId.parse(IpSecStructXfrmId.class, buf);

        android.util.Log.i("TEST", "parse mSAddr at buf.position() " + buf.position());
        struct.mSAddr = IpSecStructXfrmAddressT.parse(IpSecStructXfrmAddressT.class, buf);

        android.util.Log.i("TEST", "parse mLft at buf.position() " + buf.position());
        struct.mLft = IpSecStructXfrmLifetimeCfg.parse(IpSecStructXfrmLifetimeCfg.class, buf);

        android.util.Log.i("TEST", "parse mCurlft at buf.position() " + buf.position());
        struct.mCurlft = IpSecStructXfrmLifetimeCur.parse(IpSecStructXfrmLifetimeCur.class, buf);

        android.util.Log.i("TEST", "parse mStats at buf.position() " + buf.position());
        struct.mStats = IpSecStructXfrmStats.parse(IpSecStructXfrmStats.class, buf);

        android.util.Log.i("TEST", "parse mSeq at buf.position() " + buf.position());
        struct.mSeq = getFieldValue(buf, Type.U32, long.class, 0);

        android.util.Log.i("TEST", "parse mReqId at buf.position() " + buf.position());
        struct.mReqId = getFieldValue(buf, Type.U32, long.class, 0);
        struct.mFamily = getFieldValue(buf, Type.U16, int.class, 0);
        struct.mMode = getFieldValue(buf, Type.U8, short.class, 0);
        struct.mReplayWindow = getFieldValue(buf, Type.U8, short.class, 0);
        struct.mFlags = getFieldValue(buf, Type.U8, short.class, 7);

        android.util.Log.i("TEST", "parse done buf.position() " + buf.position());

        android.util.Log.i("TEST", "mReplayWindow " + struct.mReplayWindow);
        android.util.Log.i("TEST", "getDestAddress " + struct.getDestAddress());
        android.util.Log.i("TEST", "getSrcAddress " + struct.getSrcAddress());
        android.util.Log.i("TEST", "getSpi " + String.format("0x%08X", struct.getSpi()));

        return struct;
    }

    public InetAddress getDestAddress() {
        try {
            return IpSecStructXfrmAddressT.getAddress(mId.dAddress, mFamily);
        } catch (Exception e) {
            return null;
        }
    }

    public InetAddress getSrcAddress() {
        try {
            return IpSecStructXfrmAddressT.getAddress(mSAddr.address, mFamily);
        } catch (Exception e) {
            return null;
        }
    }

    public long getSpi() {
        return mId.spi;
    }
}
