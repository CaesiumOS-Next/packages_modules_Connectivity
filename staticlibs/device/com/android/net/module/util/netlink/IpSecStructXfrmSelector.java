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

/**
 * struct xfrm_selector
 *
 * <p>see also:
 *
 * <p>include/uapi/linux/xfrm.h
 *
 * <p>struct xfrm_selector { xfrm_address_t daddr; xfrm_address_t saddr; __be16 dport; __be16
 * dport_mask; __be16 sport; __be16 sport_mask; __u16 family; __u8 prefixlen_d; __u8 prefixlen_s;
 * __u8 proto; int ifindex; __kernel_uid32_t user; };
 *
 * @hide
 */
public class IpSecStructXfrmSelector extends Struct {

    /**
     * 0x00000010 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 ................ 0x00000020 00 00
     * 00 00 00 00 00 00 00 00 00 00 00 00 00 00 ................ 0x00000030 00 00 00 00 00 00 00 00
     * 02 00 00 00 00 00 00 00 ................ 0x00000040 00 00 00 00 00 00 00 00
     */
    // Already aligned.
    public static final int STRUCT_SIZE = 56;

    @Field(order = 0, type = Type.ByteArray, arraysize = 16)
    public final byte[] dAddress;

    @Field(order = 1, type = Type.ByteArray, arraysize = 16)
    public final byte[] sAddress; // xfrm_address_t

    @Field(order = 2, type = Type.UBE16)
    public final int dPort;

    @Field(order = 3, type = Type.UBE16)
    public final int dPortMask;

    @Field(order = 4, type = Type.UBE16)
    public final int sPort;

    @Field(order = 5, type = Type.UBE16)
    public final int sPortMask;

    @Field(order = 6, type = Type.U16)
    public final int family;

    @Field(order = 7, type = Type.U8, padding = 1)
    public final short prefixlenD;

    @Field(order = 8, type = Type.U8, padding = 1)
    public final short prefixlenS;

    @Field(order = 9, type = Type.U8, padding = 1)
    public final short proto;

    @Field(order = 10, type = Type.S32)
    public final int ifIndex;

    @Field(order = 11, type = Type.S32)
    public final int user;

    // Constructor that allows Strutc.parse(Class<T>, ByteBuffer) to work
    public IpSecStructXfrmSelector(
            byte[] dAddress,
            byte[] sAddress,
            int dPort,
            int dPortMask,
            int sPort,
            int sPortMask,
            int family,
            short prefixlenD,
            short prefixlenS,
            short proto,
            int ifIndex,
            int user) {
        this.dAddress = dAddress;
        this.sAddress = sAddress;
        this.dPort = dPort;
        this.dPortMask = dPortMask;
        this.sPort = sPort;
        this.sPortMask = sPortMask;
        this.family = family;
        this.prefixlenD = prefixlenD;
        this.prefixlenS = prefixlenS;
        this.proto = proto;
        this.ifIndex = ifIndex;
        this.user = user;
    }

    // Constructor for message sender to build this class
    public IpSecStructXfrmSelector(int family) {
        this.dAddress = new byte[16];
        this.sAddress = new byte[16];
        this.dPort = 0;
        this.dPortMask = 0;
        this.sPort = 0;
        this.sPortMask = 0;
        this.family = family;
        this.prefixlenD = 0;
        this.prefixlenS = 0;
        this.proto = 0;
        this.ifIndex = 0;
        this.user = 0;
    }

    public void pack(ByteBuffer byteBuffer) {
        this.writeToByteBuffer(byteBuffer);
    }
}
