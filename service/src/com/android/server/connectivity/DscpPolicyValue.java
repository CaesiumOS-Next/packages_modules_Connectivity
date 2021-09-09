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
import android.util.Range;

import com.android.net.module.util.Struct;
import com.android.net.module.util.Struct.Field;
import com.android.net.module.util.Struct.Type;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Value type for downstream & upstream IPv4 forwarding maps. */
public class DscpPolicyValue extends Struct {
    private static final String TAG = DscpPolicyValue.class.getSimpleName();

    // TODO: add the interface index.
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

    private static final int SRC_IP_MASK = 0x1;
    private static final int DST_IP_MASK = 0x02;
    private static final int SRC_PORT_MASK = 0x4;
    private static final int DST_PORT_MASK = 0x8;
    private static final int PROTO_MASK = 0x10;

    private boolean ipEmpty(final byte[] ip) {
        for (int i = 0; i < ip.length; i++) {
            if (ip[i] != 0) return false;
        }
        return true;
    }

    private short makeMask(final byte[] src46, final byte[] dst46, final int srcPort,
            final int dstPortStart, final short proto, final short dscp) {
        short mask = 0;
        if (!ipEmpty(src46)) {
            mask |= SRC_IP_MASK;
        }
        if (!ipEmpty(dst46)) {
            mask |=  DST_IP_MASK;
        }
        if (srcPort != -1) {
            mask |=  SRC_PORT_MASK;
        }
        if (dstPortStart != -1 && dstPortEnd != -1) {
            mask |=  DST_PORT_MASK;
        }
        if (proto != -1) {
            mask |=  PROTO_MASK;
        }
        return mask;
    }

    // This constructor is necessary for BpfMap#getValue since all values must be
    // in the constructor.
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
        this.mask = makeMask(src46, dst46, srcPort, dstPortStart, proto, dscp);
    }

    public DscpPolicyValue(final byte[] src46, final byte[] dst46, final int srcPort,
            final Range<Integer> dstPort, final short proto,
            final short dscp) {
        this(src46, dst46, srcPort, dstPort != null ? dstPort.getLower() : -1,
                dstPort != null ? dstPort.getUpper() : -1, proto, dscp);
    }

    public static final DscpPolicyValue NONE = new DscpPolicyValue(
            new byte[16] /* src46 */, new byte[16] /* dst46 */, 0 /* srcPort */,
            0 /* dstPortStart */, 0 /* dstPortEnd */, (short) 0 /* proto */,
            (short) 0 /* dscp */);

    @Override
    public String toString() {
        String srcIpString = "empty";
        String dstIpString = "empty";

        // Separate try/catch for IP's so it's easier to debug.
        try {
            srcIpString = InetAddress.getByAddress(src46).getHostAddress();
        }  catch (UnknownHostException e) {
            Log.e(TAG, "Invalid SRC IP address", e);
        }

        try {
            srcIpString = InetAddress.getByAddress(src46).getHostAddress();
        }  catch (UnknownHostException e) {
            Log.e(TAG, "Invalid DST IP address", e);
        }

        try {
            return String.format(
                    "src46: %s, dst46: %s, srcPort: %d, dstPortStart: %d, dstPortEnd: %d,"
                    + " protocol: %d, dscp %s", srcIpString, dstIpString, srcPort, dstPortStart,
                    dstPortEnd, proto, dscp);
        } catch (IllegalArgumentException e) {
            return String.format("String format error: " + e);
        }
    }
}
