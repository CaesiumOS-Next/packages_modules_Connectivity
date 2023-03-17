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

package com.android.server.connectivity.mdns;

import android.annotation.NonNull;
import android.os.Handler;
import android.system.OsConstants;

import com.android.net.module.util.SharedLog;
import com.android.net.module.util.ip.NetlinkMonitor;
import com.android.net.module.util.netlink.NetlinkConstants;
import com.android.net.module.util.netlink.NetlinkMessage;
import com.android.net.module.util.netlink.RtNetlinkAddressMessage;

/**
 * The netlink monitor for MdnsSocketProvider.
 */
public class SocketNetlinkMonitor extends NetlinkMonitor {

    /**
     * The callback interface for the netlink monitor messages.
     */
    public interface NetLinkMonitorCallBack {
        /**
         * Handles the RtNetlinkAddressMessage.
         */
        void processRtNetlinkAddressMessage(RtNetlinkAddressMessage msg);
    }

    @NonNull
    final NetLinkMonitorCallBack mCb;

    SocketNetlinkMonitor(@NonNull final Handler handler,
            @NonNull final NetLinkMonitorCallBack cb,
            @NonNull SharedLog log) {
        super(handler, log, SocketNetlinkMonitor.class.getSimpleName(), OsConstants.NETLINK_ROUTE,
                NetlinkConstants.RTMGRP_IPV4_IFADDR | NetlinkConstants.RTMGRP_IPV6_IFADDR);
        mCb = cb;
    }

    @Override
    public void processNetlinkMessage(NetlinkMessage nlMsg, long whenMs) {
        if (nlMsg instanceof RtNetlinkAddressMessage) {
            mCb.processRtNetlinkAddressMessage((RtNetlinkAddressMessage) nlMsg);
        }
    }

    /**
     * Returns whether monitoring netlink is supported by this {@link SocketNetlinkMonitor}.
     */
    public boolean isSupported() {
        return true;
    }
}
