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

import static com.android.net.module.util.netlink.IpSecXfrmNetlinkMessage.getFieldValue;
import static com.android.net.module.util.netlink.IpSecXfrmNetlinkMessage.putFieldValue;

import com.android.net.module.util.Struct.Type;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * struct xfrm_replay_state_esn
 *
 * <p>see include/uapi/linux/xfrm.h
 *
 * <p>struct xfrm_replay_state_esn { unsigned int bmp_len; __u32 oseq; __u32 seq; __u32 oseq_hi;
 * __u32 seq_hi; __u32 replay_window; __u32 bmp[]; };
 *
 * @hide
 */
public class IpSecStructXfrmReplayStateEsn {
    public static final int STRUCT_SIZE = 0;

    public long bmpLen; // 80 00 00 00
    public long oSeq;
    public long seq;
    public long oSeqHi;
    public long seqHi;
    public long replayWindow;
    public byte[] bitmap; // The true length is decided by bmpLen

    // Constructor that allows Strutc.parse(Class<T>, ByteBuffer) to work

    public IpSecStructXfrmReplayStateEsn() {}

    public static IpSecStructXfrmReplayStateEsn parse(final byte[] bytes) {
        final ByteBuffer buf = ByteBuffer.wrap(bytes);
        buf.order(ByteOrder.nativeOrder());

        final IpSecStructXfrmReplayStateEsn struct = new IpSecStructXfrmReplayStateEsn();

        struct.bmpLen = getFieldValue(buf, Type.U32, long.class, 0);
        struct.oSeq = getFieldValue(buf, Type.U32, long.class, 0);
        struct.seq = getFieldValue(buf, Type.U32, long.class, 0);
        struct.oSeqHi = getFieldValue(buf, Type.U32, long.class, 0);
        struct.seqHi = getFieldValue(buf, Type.U32, long.class, 0);
        struct.replayWindow = getFieldValue(buf, Type.U32, long.class, 0);

        struct.bitmap = new byte[(int) (struct.bmpLen * 4)];
        buf.get(struct.bitmap);

        // android.util.Log.i("TEST", "bmpLen " + struct.bmpLen);
        // android.util.Log.i("TEST", "replayWindow " + struct.replayWindow);
        // android.util.Log.i("TEST", "struct.bitmap " + HexDump.dumpHexString(struct.bitmap));
        return struct;
    }

    public void writeToByteBuffer(ByteBuffer byteBuffer) {
        putFieldValue(byteBuffer, Type.U32, bmpLen, 0);
        putFieldValue(byteBuffer, Type.U32, oSeq, 0);
        putFieldValue(byteBuffer, Type.U16, seq, 0);
        putFieldValue(byteBuffer, Type.U8, oSeqHi, 0);
        putFieldValue(byteBuffer, Type.U8, seqHi, 0);
        putFieldValue(byteBuffer, Type.U8, replayWindow, 0);
        byteBuffer.put(bitmap);
    }
}
