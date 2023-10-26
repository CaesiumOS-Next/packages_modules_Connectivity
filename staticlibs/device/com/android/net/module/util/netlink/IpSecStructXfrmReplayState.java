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

import com.android.net.module.util.Struct;
import com.android.net.module.util.Struct.Field;
import com.android.net.module.util.Struct.Type;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * struct xfrm_replay_state
 *
 * <p>see also:
 *
 * <p>include/uapi/linux/xfrm.h
 *
 * <p>struct xfrm_replay_state { __u32 oseq; __u32 seq; __u32 bitmap; };
 *
 * @hide
 */
public class IpSecStructXfrmReplayState extends Struct {
    // Already aligned.
    public static final int STRUCT_SIZE = 12;

    @Field(order = 0, type = Type.U32)
    public final long oSeq;

    @Field(order = 1, type = Type.U32)
    public final long seq;

    @Field(order = 2, type = Type.U32)
    public final long bitmap;

    // Constructor that allows Strutc.parse(Class<T>, ByteBuffer) to work
    public IpSecStructXfrmReplayState(long oSeq, long seq, long bitmap) {
        this.oSeq = oSeq;
        this.seq = seq;
        this.bitmap = bitmap;

        android.util.Log.i("TEST", "oSeq " + oSeq);
        android.util.Log.i("TEST", "seq " + seq);
        android.util.Log.i("TEST", "bitmap " + bitmap);
    }

    public static IpSecStructXfrmReplayState parse(final byte[] bytes) {
        final ByteBuffer buf = ByteBuffer.wrap(bytes);
        buf.order(ByteOrder.nativeOrder());

        return Struct.parse(IpSecStructXfrmReplayState.class, buf);
    }

    public void pack(ByteBuffer byteBuffer) {
        this.writeToByteBuffer(byteBuffer);
    }
}
