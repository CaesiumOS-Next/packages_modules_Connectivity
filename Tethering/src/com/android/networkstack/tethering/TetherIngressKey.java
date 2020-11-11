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

import android.net.util.Struct;
import android.net.util.Struct.Field;
import android.net.util.Struct.Type;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Objects;

public class TetherIngressKey extends Struct {
    @Field(order = 0, type = Type.U32)
    public final long iif;
    @Field(order = 1, type = Type.ByteArray, arraysize = 16)
    public final byte[] neigh6;

    public TetherIngressKey(final long iif, final byte[] neigh6) {
        this.iif = iif;
        this.neigh6 = neigh6;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;

        if (!(obj instanceof TetherIngressKey)) return false;

        final TetherIngressKey that = (TetherIngressKey) obj;

        return iif == that.iif && Arrays.equals(neigh6, that.neigh6);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(iif) ^ Arrays.hashCode(neigh6);
    }



    @Override
    public String toString() {
        try {
            return String.format("iif: %d, neigh: %s", iif, InetAddress.getByAddress(neigh6));
        } catch (UnknownHostException e) {
            return String.format("iif: %d, Wrong IP address");
        }
    }
}
