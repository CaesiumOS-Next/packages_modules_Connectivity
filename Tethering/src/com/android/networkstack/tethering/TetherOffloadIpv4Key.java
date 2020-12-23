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

import java.net.Inet4Address;
import java.net.UnknownHostException;
import java.util.Arrays;

/** The key of BpfMap which is used for IPv4 bpf offload. */
public class TetherOffloadIpv4Key extends Struct {
    // TODO: Considering the padding for the alignment.

    @Field(order = 0, type = Type.U32)
    public final long iif;

    @Field(order = 1, type = Type.ByteArray, arraysize = 6)
    public final byte[] inDstMac;

    @Field(order = 2, type = Type.U8, padding = 1)
    public final short l4proto;

    @Field(order = 3, type = Type.ByteArray, arraysize = 4)
    public final byte[] inSrcIp4;

    @Field(order = 4, type = Type.ByteArray, arraysize = 4)
    public final byte[] inDstIp4;

    @Field(order = 5, type = Type.U16)
    public final short inSrcPort;

    @Field(order = 6, type = Type.U16)
    public final short inDstPort;

    public TetherOffloadIpv4Key(final long iif, final byte[] inDstMac, final short l4proto,
            final byte[] inSrcIp4, final byte[] inDstIp4, final short inSrcPort,
            final short inDstPort) {
        this.iif = iif;
        this.inDstMac = inDstMac;
        this.l4proto = l4proto;
        this.inSrcIp4 = inSrcIp4;
        this.inDstIp4 = inDstIp4;
        this.inSrcPort = inSrcPort;
        this.inDstPort = inDstPort;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;

        if (!(obj instanceof TetherOffloadIpv4Key)) return false;

        final TetherOffloadIpv4Key that = (TetherOffloadIpv4Key) obj;

        return iif == that.iif && Arrays.equals(inDstMac, that.inDstMac)
                && l4proto == that.l4proto && Arrays.equals(inSrcIp4, that.inSrcIp4)
                && Arrays.equals(inDstIp4, that.inDstIp4) && inSrcPort == that.inSrcPort
                && inDstPort == that.inDstPort;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(iif) ^ Arrays.hashCode(inDstMac) ^ Short.hashCode(l4proto)
                ^ Arrays.hashCode(inSrcIp4) ^ Arrays.hashCode(inDstIp4) ^ Short.hashCode(
                inSrcPort) ^ Short.hashCode(inDstPort);
    }

    @Override
    public String toString() {
        try {
            return String.format(
                    "iif: %d, inDstMac: %s, l4proto: %d, inSrcIp4: %s, inDstIp4: %s, "
                            + "inSrcPort: %d, inDstPort: %d",
                    iif, MacAddress.fromBytes(inDstMac), l4proto,
                    Inet4Address.getByAddress(inSrcIp4), Inet4Address.getByAddress(inDstIp4),
                    Short.toUnsignedInt(inSrcPort), Short.toUnsignedInt(inDstPort));
        } catch (UnknownHostException | IllegalArgumentException e) {
            return String.format("Invalid IP or Mac address", e);
        }
    }
}
