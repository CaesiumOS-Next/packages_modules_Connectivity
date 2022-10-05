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

package com.android.connectivity.mdns;

import android.net.DnsResolver;

import com.android.server.connectivity.mdns.MdnsPacketWriter;

import java.io.IOException;

public class MdnsQuestion {
    public static final int TYPE_ALL = 255;

    private final String[] mName;
    private final int mType;

    public MdnsQuestion(String[] name, int type) {
        mName = name;
        mType = type;
    }

    public void write(MdnsPacketWriter writer) throws IOException {
        writer.writeLabels(mName);
        writer.writeUInt16(mType);
        writer.writeUInt16(0x8000 /* QU (question) flag */ | DnsResolver.CLASS_IN);
    }
}
