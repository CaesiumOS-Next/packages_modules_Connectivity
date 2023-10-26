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
package com.android.net.module.util.netlink;

import com.android.net.module.util.Struct;
import com.android.net.module.util.Struct.Field;
import com.android.net.module.util.Struct.Type;

import java.net.InetAddress;
import java.nio.ByteBuffer;

public class IpSecStructXfrmAddressT extends Struct {
    public static final int STRUCT_SIZE = 16;

    @Field(order = 0, type = Type.ByteArray, arraysize = 16)
    public final byte[] address;

    // Constructor that allows Strutc.parse(Class<T>, ByteBuffer) to work
    public IpSecStructXfrmAddressT(byte[] address) {
        this.address = address;
    }

    // Constructor for message sender to build this class
    public IpSecStructXfrmAddressT(InetAddress destAddress) {
        this.address = new byte[16];
        final byte[] arrdessBytes = destAddress.getAddress();
        System.arraycopy(arrdessBytes, 0, address, 0, arrdessBytes.length);
    }

    public void pack(ByteBuffer byteBuffer) {
        this.writeToByteBuffer(byteBuffer);
    }

    public static InetAddress getAddress(byte[] address, int family) throws Exception {
        if (family == 10) {
            return InetAddress.getByAddress(address);
        }

        final byte[] v4Address = new byte[4];
        System.arraycopy(address, 0, v4Address, 0, 4);
        return InetAddress.getByAddress(v4Address);
    }
}
