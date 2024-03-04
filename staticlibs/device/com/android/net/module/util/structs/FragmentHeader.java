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

package com.android.net.module.util.structs;

import com.android.net.module.util.Struct;
import com.android.net.module.util.Struct.Field;
import com.android.net.module.util.Struct.Type;

/**
 * IPv6 Fragment Extension header, as per https://tools.ietf.org/html/rfc2460.
 *
 * 0                   1                   2                   3
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |  Next Header  |   Reserved    |      Fragment Offset    |Res|M|
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                         Identification                        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
public class FragmentHeader extends Struct {
    @Field(order = 0, type = Type.S8)
    public final byte nextHeader;
    @Field(order = 1, type = Type.S8)
    public final byte reserved;
    @Field(order = 2, type = Type.S16)
    public final short fragmentOffset;
    @Field(order = 3, type = Type.S32)
    public final int identification;

    public FragmentHeader(final byte nextHeader, final byte reserved, final short fragmentOffset,
            final int identification) {
        this.nextHeader = nextHeader;
        this.reserved = reserved;
        this.fragmentOffset = fragmentOffset;
        this.identification = identification;
    }
}
