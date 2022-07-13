/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.net;

import static android.system.OsConstants.NETLINK_INET_DIAG;

import android.os.Handler;
import android.system.ErrnoException;

import com.android.net.module.util.IBpfMap;
import com.android.net.module.util.SharedLog;
import com.android.net.module.util.ip.NetlinkMonitor;
import com.android.net.module.util.netlink.InetDiagMessage;
import com.android.net.module.util.netlink.NetlinkMessage;
import com.android.net.module.util.netlink.StructInetDiagSockId;

/**
 * Monitor socket destroy and delete entry from cookie tag bpf map.
 */
public class SkDestroyListener extends NetlinkMonitor {
    private static final int SKNLGRP_INET_TCP_DESTROY = 1;
    private static final int SKNLGRP_INET_UDP_DESTROY = 2;
    private static final int SKNLGRP_INET6_TCP_DESTROY = 3;
    private static final int SKNLGRP_INET6_UDP_DESTROY = 4;

    private final IBpfMap<CookieTagMapKey, CookieTagMapValue> mCookieTagMap;

    SkDestroyListener(final Handler handler, final SharedLog log,
            IBpfMap<CookieTagMapKey, CookieTagMapValue> cookieTagMap) {
        super(handler, log, "SkDestroyListener", NETLINK_INET_DIAG,
                1 << (SKNLGRP_INET_TCP_DESTROY - 1)
                        | 1 << (SKNLGRP_INET_UDP_DESTROY - 1)
                        | 1 << (SKNLGRP_INET6_TCP_DESTROY - 1)
                        | 1 << (SKNLGRP_INET6_UDP_DESTROY - 1),
                512 * 1024 /* sockRcvbufSize */);
        mCookieTagMap = cookieTagMap;
    }

    @Override
    public void processNetlinkMessage(NetlinkMessage nlMsg, final long whenMs) {
        if (!(nlMsg instanceof InetDiagMessage)) {
            mLog.e("Received non inet diag message");
            return;
        }
        final StructInetDiagSockId sockId = ((InetDiagMessage) nlMsg).mStructInetDiagMsg.id;
        if (sockId == null) {
            mLog.e("Received InetDiagMessage with null socket id");
            return;
        }

        try {
            mCookieTagMap.deleteEntry(new CookieTagMapKey(sockId.mCookie));
        } catch (ErrnoException e) {
            mLog.e("Failed to delete CookieTagMap entry: " + e);
        }
    }
}

