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

package com.android.net.module.util.netlink.ipsec;

import androidx.annotation.NonNull;

import com.android.net.module.util.netlink.NetlinkMessage;
import com.android.net.module.util.netlink.StructNlMsgHdr;

/** Base calss for XFRM netlink messages */
public abstract class IpSecXfrmNetlinkMessage extends NetlinkMessage {
    // TODO: b/308011229 Remove it when OsConstants.IPPROTO_ESP is exposed
    public static final int IPPROTO_ESP = 50;

    public IpSecXfrmNetlinkMessage(@NonNull StructNlMsgHdr header) {
        super(header);
    }

    // TODO: Add the support for parsing messages
}
