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

package com.android.server.connectivity;

import com.android.net.module.util.Struct;
import java.net.Inet6Address;

public class StructMrt6Msg extends Struct {
    public static final int STRUCT_SIZE = 40;
    public static final byte MRT6MSG_NOCACHE = 1;

    @Field(order = 0, type = Type.S8)
    public final byte mbz; // must be zero
    @Field(order = 1, type = Type.S8)
    public final byte msgType; // message type
    @Field(order = 2, type = Type.UBE16)
    public final int mif; // mif received on
    @Field(order = 3, type = Type.U32)
    public final long padding;
    @Field(order = 4, type = Type.Ipv6Address)
    public final Inet6Address src;
    @Field(order = 5, type = Type.Ipv6Address)
    public final Inet6Address dst;

    public StructMrt6Msg(final byte mbz, final byte msgType, final int mif, final long pad,
                  final Inet6Address source, final Inet6Address destination) {
        this.mbz = mbz;
        this.msgType = msgType;
        this.mif = (int) Short.reverseBytes(((short) mif));
        this.padding = pad;
        this.src = source;
        this.dst = destination;
    }
}

