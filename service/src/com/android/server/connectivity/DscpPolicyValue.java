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

import android.util.Log;

import com.android.net.module.util.Struct;
import com.android.net.module.util.Struct.Field;
import com.android.net.module.util.Struct.Type;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Objects;

/** Value type for downstream & upstream IPv4 forwarding maps. */
public class DscpPolicyValue extends Struct {
    private static final String TAG = DscpPolicyValue.class.getSimpleName();

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
    public final short proto;

    @Field(order = 6, type = Type.U8)
    public final short dscp;

    @Field(order = 7, type = Type.U8, padding = 3)
    public final short mask;

    private static final int SRC_IP_MASK = 1;
    private static final int DST_IP_MASK = 2;
    private static final int SRC_PORT_MASK = 4;
    private static final int DST_PORT_MASK = 8;
    private static final int PROTO_MASK = 16;

    public DscpPolicyValue(final byte[] src46, final byte[] dst46, final int srcPort,
            final int dstPortStart, final int dstPortEnd, final short proto,
            final short dscp) {
        this.src46 = src46;
        this.dst46 = dst46;
        this.srcPort = srcPort;
        this.dstPortStart = dstPortStart;
        this.dstPortEnd = dstPortEnd;
        this.proto = proto;
        this.dscp = dscp;

        short mask = 0;
        if (!Arrays.equals(src46, new byte[src46.length])) {
            mask |= SRC_IP_MASK;
            Log.e(TAG, "set src ip: " + Arrays.toString(src46));
        }
        if (!Arrays.equals(dst46, new byte[dst46.length])) {
            mask |=  DST_IP_MASK;
            Log.e(TAG, "set dst ip: " + Arrays.toString(dst46));
        }
        if (srcPort != 0) {
            mask |=  SRC_PORT_MASK;
            Log.e(TAG, "set src port: " + srcPort);
        }
        if (dstPortStart != 0) {
            mask |=  DST_PORT_MASK;
            Log.e(TAG, "set dst port: " + dstPortStart);
        }
        if (proto != 0) {
            mask |=  PROTO_MASK;
            Log.e(TAG, "set proto: " + proto);
        }
        Log.e(TAG, "Mask: " + mask);
        this.mask = mask;
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