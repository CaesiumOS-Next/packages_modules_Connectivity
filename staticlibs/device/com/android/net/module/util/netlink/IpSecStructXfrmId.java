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

import java.net.InetAddress;
import java.nio.ByteBuffer;

/**
 * struct xfrm_id
 *
 * <p>see also:
 *
 * <p>include/uapi/linux/xfrm.h
 *
 * <p>struct xfrm_id { xfrm_address_t daddr; __be32 spi; __u8 proto; };
 *
 * @hide
 */
public class IpSecStructXfrmId extends Struct {
    // Already aligned.
    /**
     * C0 A8 D3 CB 00 00 00 00 ................ 0x00000050 00 00 00 00 00 00 00 00 53 FA 0F DD 32 00
     * 00 00
     */
    public static final int STRUCT_SIZE = 24;

    @Field(order = 0, type = Type.ByteArray, arraysize = 16)
    public final byte[] dAddress;

    @Field(order = 1, type = Type.UBE32)
    public final long spi;

    @Field(order = 2, type = Type.U8, padding = 3)
    public final short proto;

    // Constructor that allows Strutc.parse(Class<T>, ByteBuffer) to work
    public IpSecStructXfrmId(byte[] dAddress, long spi, short proto) {
        this.dAddress = dAddress;
        this.spi = spi;
        this.proto = proto;
    }

    // Constructor for message sender to build this class
    public IpSecStructXfrmId(InetAddress dAddress, long spi, short proto) {
        this.dAddress = new IpSecStructXfrmAddressT(dAddress).writeToBytes();
        this.spi = spi;
        this.proto = proto;
    }

    public void pack(ByteBuffer byteBuffer) {
        this.writeToByteBuffer(byteBuffer);
    }
}
