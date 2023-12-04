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

import static android.system.OsConstants.AF_INET6;

import com.android.net.module.util.Struct;
import java.net.Inet6Address;
import java.util.Set;

/*
 * Implements the mf6cctl structure which is used to add a multicast forwarding
 * cache, see /usr/include/linux/mroute6.h
 */
public class StructMf6cctl extends Struct {
    @Field(order = 0, type = Type.U16)
    public final int originFamily = AF_INET6; // AF_INET6
    @Field(order = 1, type = Type.U16)
    public final int originPort = 0; // Transport layer port # of origin
    @Field(order = 2, type = Type.U32)
    public final long originFlowinfo = 0; // IPv6 flow information
    @Field(order = 3, type = Type.ByteArray, arraysize = 16)
    public final byte[] originAddress; //the IPv6 address of origin
    @Field(order = 4, type = Type.U32)
    public final long originScopeId = 0; // scope id, not used
    @Field(order = 5, type = Type.U16)
    public final int groupFamily = AF_INET6; // AF_INET6
    @Field(order = 6, type = Type.U16)
    public final int groupPort = 0; // Transport layer port # of multicast group
    @Field(order = 7, type = Type.U32)
    public final long groupFlowinfo = 0; // IPv6 flow information
    @Field(order = 8, type = Type.ByteArray, arraysize = 16)
    public final byte[] groupAddress; //the IPv6 address of multicast group
    @Field(order = 9, type = Type.U32)
    public final long groupScopeId = 0; // scope id, not used
    @Field(order = 10, type = Type.U16, padding = 2)
    public final int mf6ccParent; // incoming interface
    @Field(order = 11, type = Type.ByteArray, arraysize = 32)
    public final byte[] mf6ccIfset; // outgoing interfaces

    public StructMf6cctl(final Inet6Address origin, final Inet6Address group,
            final int mf6ccParent, final Set<Integer> oifs) {
        this.originAddress = origin.getAddress();
        this.groupAddress = group.getAddress();
        this.mf6ccParent = mf6ccParent;
        this.mf6ccIfset = new byte[32];
        for (int oif : oifs) {
            int idx = oif / 8;
            int offset = oif % 8;
            this.mf6ccIfset[idx] |= (byte) (1 << offset);
        }
    }
}

