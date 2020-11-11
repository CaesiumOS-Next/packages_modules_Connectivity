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

import java.util.Arrays;

/** The value of BpfMap which is used for bpf offload. */
public class TetherIngressValue extends Struct {
    @Field(order = 0, type = Type.U32)
    public final long oif; // The output interface index.

    // The ethhdr struct which is defined in uapi/linux/if_ether.h
    @Field(order = 1, type = Type.ByteArray, arraysize = 6)
    public final byte[] ethDstMac; // The destination mac address.
    @Field(order = 2, type = Type.ByteArray, arraysize = 6)
    public final byte[] ethSrcMac; // The source mac address.
    @Field(order = 3, type = Type.UBE16)
    public final int ethProto; // Packet type ID field.

    @Field(order = 4, type = Type.U16)
    public final int pmtu; // The maximum L3 output path/route mtu.

    public TetherIngressValue(final long oif, final byte[] ethDstMac, final byte[] ethSrcMac,
            final int ethProto, final int pmtu) {
        this.oif = oif;
        this.ethDstMac = ethDstMac;
        this.ethSrcMac = ethSrcMac;
        this.ethProto = ethProto;
        this.pmtu = pmtu;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;

        if (!(obj instanceof TetherIngressValue)) return false;

        final TetherIngressValue that = (TetherIngressValue) obj;

        return oif == that.oif && Arrays.equals(ethDstMac, that.ethDstMac)
                && Arrays.equals(ethSrcMac, that.ethSrcMac) && ethProto == that.ethProto
                && pmtu == that.pmtu;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(oif) + Arrays.hashCode(ethDstMac) + Arrays.hashCode(ethSrcMac)
                + ethProto + pmtu;
    }

    @Override
    public String toString() {
        return String.format("oif: %d, dstMac: %s, srcMac: %s, proto: %d, pmtu: %d", oif,
                MacAddress.fromBytes(ethDstMac), MacAddress.fromBytes(ethSrcMac), ethProto, pmtu);
    }
}
