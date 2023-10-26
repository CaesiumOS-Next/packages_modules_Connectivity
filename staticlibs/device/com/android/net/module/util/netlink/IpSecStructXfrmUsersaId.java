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

import com.android.net.module.util.Struct;
import com.android.net.module.util.Struct.Field;
import com.android.net.module.util.Struct.Type;

import java.net.InetAddress;
import java.nio.ByteBuffer;

/**
 * struct xfrm_usersa_id
 *
 * <p>see also:
 *
 * <p>include/uapi/linux/xfrm.h
 *
 * @hide
 */
public class IpSecStructXfrmUsersaId extends Struct {
    // Already aligned.
    public static final int STRUCT_SIZE = 24;

    /*
     * c0000201000000000000000000000000 77684400 0200 3200
     * C0000201000000000000000000000000 77684400 0002 3200
     * C00002010000000000000000000000007768440002003200
     *
     * struct xfrm_usersa_id {
    	xfrm_address_t			daddr;  !16s 16 bytes c0000201000000000000000000000000
    	__be32				spi; I 4 bytes 77684400
    	__u16				family; Bx 1 byte 0200
    	__u8				proto; Bx 1 byte 3200
    };

    !16sIBxBx

        typedef union {
            __be32		a4;
            __be32		a6[4];
            struct in6_addr	in6;
        } xfrm_address_t;
     */
    @Field(order = 0, type = Type.ByteArray, arraysize = 16)
    public byte[] mDestAddress; // xfrm_address_t

    @Field(order = 1, type = Type.UBE32)
    public long mSpi;

    @Field(order = 2, type = Type.U16)
    public int mFamily;

    @Field(order = 3, type = Type.U8, padding = 1)
    public short mProto;

    // Constructor that allows Strutc.parse(Class<T>, ByteBuffer) to work
    public IpSecStructXfrmUsersaId(byte[] destAddress, long spi, int family, short proto) {
        mDestAddress = destAddress;
        mSpi = spi;
        mFamily = family;
        mProto = proto;
    }

    // Constructor for message sender to build this class
    public IpSecStructXfrmUsersaId(InetAddress destAddress, long spi, int family, short proto) {
        mDestAddress = new IpSecStructXfrmAddressT(destAddress).writeToBytes();
        mSpi = spi;
        mFamily = family;
        mProto = proto;
    }

    public void pack(ByteBuffer byteBuffer) {
        this.writeToByteBuffer(byteBuffer);
    }

    public InetAddress getDestAddress() throws Exception {
        return IpSecStructXfrmAddressT.getAddress(mDestAddress, mFamily);
    }
}
