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

package com.android.server.connectivity;

import com.android.net.module.util.Struct;
import com.android.net.module.util.Struct.Field;
import com.android.net.module.util.Struct.Type;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;

/** Value type for downstream & upstream IPv4 forwarding maps. */
public class DscpPolicyValue extends Struct {
    @Field(order = 0, type = Type.ByteArray, arraysize = 16)
    public final byte[] src46;

    @Field(order = 1, type = Type.ByteArray, arraysize = 16)
    public final byte[] dst46;

    @Field(order = 2, type = Type.UBE16)
    public final int srcPort;

    @Field(order = 3, type = Type.UBE16)
    public final int dstPortStart;

    @Field(order = 4, type = Type.UBE16)
    public final int dstPortEnd;

    @Field(order = 5, type = Type.U8)
    public final int proto;

    @Field(order = 6, type = Type.U8)
    public final int dscp;

    public DscpPolicyValue(final byte[] src46, final byte[] dst46, final int srcPort,
            final int dstPortStart, final int dstPortEnd, final int proto,
            final int dscp) {
        this.src46 = src46;
        this.dst46 = dst46;
        this.srcPort = srcPort;
        this.dstPortStart = dstPortStart;
        this.dstPortEnd = dstPortEnd;
        this.proto = proto;
        this.dscp = dscp;

        // TODO: set masks from constructor?
    }

    @Override
    public String toString() {
        try {
            return String.format(
                    "src46: %s, dst46: %s, srcPort: %d, dstPortStart: %d, dstPortEnd: %d, "
                            + "protocolo: %d, dscp %s",
                    InetAddress.getByAddress(src46), InetAddress.getByAddress(dst46),
                    Short.toUnsignedInt((short) srcPort), Short.toUnsignedInt((short) dstPortStart),
                    Short.toUnsignedInt((short) dstPortEnd), proto, dscp);
        } catch (UnknownHostException | IllegalArgumentException e) {
            return String.format("Invalid IP address", e);
        }
    }
}