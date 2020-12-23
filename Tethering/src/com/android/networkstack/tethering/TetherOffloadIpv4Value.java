/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.networkstack.tethering;

import android.net.MacAddress;

import com.android.net.module.util.Struct;
import com.android.net.module.util.Struct.Field;
import com.android.net.module.util.Struct.Type;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/** The value of BpfMap which is used for IPv4 bpf offload. */
public class TetherOffloadIpv4Value extends Struct {
    // TODO: Considering the padding for the alignment.

    @Field(order = 0, type = Type.U32)
    public final long oif;

    @Field(order = 1, type = Type.ByteArray, arraysize = 6)
    public final byte[] outDstMac;

    @Field(order = 2, type = Type.ByteArray, arraysize = 6)
    public final byte[] outSrcMac;

    @Field(order = 3, type = Type.ByteArray, arraysize = 16)
    public final byte[] outSrcIp46;

    @Field(order = 4, type = Type.ByteArray, arraysize = 16)
    public final byte[] outDstIp46;

    @Field(order = 5, type = Type.U16)
    public final short outSrcPort;

    @Field(order = 6, type = Type.U16)
    public final short outDstPort;

    @Field(order = 7, type = Type.U8)
    public final byte isEthernet;

    @Field(order = 8, type = Type.U8)
    public final byte isIpv6;

    @Field(order = 9, type = Type.U8, padding = 1)
    public final short direction;

    @Field(order = 10, type = Type.U63)
    public final long lastUsed;

    public TetherOffloadIpv4Value(final long oif, final byte[] outDstMac,
            final byte[] outSrcMac, final byte[] outSrcIp46, final byte[] outDstIp46,
            final short outSrcPort, final short outDstPort, final byte isEthernet,
            final byte isIpv6, final short direction, final long lastUsed) {
        this.oif = oif;
        this.outDstMac = outDstMac;
        this.outSrcMac = outSrcMac;
        this.outSrcIp46 = outSrcIp46;
        this.outDstIp46 = outDstIp46;
        this.outSrcPort = outSrcPort;
        this.outDstPort = outDstPort;
        this.isEthernet = isEthernet;
        this.isIpv6 = isIpv6;
        this.direction = direction;
        this.lastUsed = lastUsed;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;

        if (!(obj instanceof TetherOffloadIpv4Value)) return false;

        final TetherOffloadIpv4Value that = (TetherOffloadIpv4Value) obj;

        return oif == that.oif && Arrays.equals(outDstMac, that.outDstMac)
                && Arrays.equals(outSrcMac, that.outSrcMac) && Arrays.equals(outSrcIp46,
                that.outSrcIp46) && Arrays.equals(outDstIp46, that.outDstIp46)
                && outSrcPort == that.outSrcPort && outDstPort == that.outDstPort
                && isEthernet == that.isEthernet && isIpv6 == that.isIpv6
                && direction == that.direction && lastUsed == that.lastUsed;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(oif) ^ Arrays.hashCode(outDstMac) ^ Arrays.hashCode(outSrcMac)
                ^ Arrays.hashCode(outSrcIp46) ^ Arrays.hashCode(outDstIp46)
                ^ Short.hashCode(outSrcPort) ^ Short.hashCode(outDstPort)
                ^ Byte.hashCode(isEthernet) ^ Byte.hashCode(isIpv6) ^ Short.hashCode(direction)
                ^ Long.hashCode(lastUsed);
    }

    @Override
    public String toString() {
        try {
            return String.format(
                    "oif: %d, outDstMac: %s, outSrcMac: %s, outSrcIp46: %s, outDstIp46: %s, "
                            + "outSrcPort: %d, outDstPort: %d, isEthernet: %d, isIpv6: %d, "
                            + "direction: %d, lastUsed: %d",
                    oif, MacAddress.fromBytes(outDstMac), MacAddress.fromBytes(outSrcMac),
                    InetAddress.getByAddress(outSrcIp46), InetAddress.getByAddress(outDstIp46),
                    Short.toUnsignedInt(outSrcPort), Short.toUnsignedInt(outDstPort), isEthernet,
                    isIpv6, direction, lastUsed);
        } catch (UnknownHostException | IllegalArgumentException e) {
            return String.format("Invalid IP or Mac address", e);
        }
    }
}
