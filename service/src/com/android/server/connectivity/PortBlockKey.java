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

package com.android.server.connectivity;


import android.annotation.NonNull;

import com.android.net.module.util.Struct;
import com.android.net.module.util.Struct.Field;
import com.android.net.module.util.Struct.Type;

import java.util.Objects;

/** Key type for port blocking maps. */
public class PortBlockKey extends Struct {
    @Field(order = 0, type = Type.UBE16)
    public final int port;

    @Field(order = 1, type = Type.U16)
    public final int ipType;

    @Field(order = 2, type = Type.UBE16)
    public final int proto;

    public PortBlockKey(final int port, final int ipType, final int proto) {
        this.port = port;
        this.ipType = ipType;
        this.proto = proto;
    }

    @Override
    public String toString() {
        try {
            return String.format(
                    "port: %d, ipType: %d, proto: %d",
                            port, ipType, proto);
        } catch (IllegalArgumentException e) {
            return String.format("Invalid IP address", e);
        }
    }
}