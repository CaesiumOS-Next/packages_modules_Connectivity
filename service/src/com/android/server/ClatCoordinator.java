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

package com.android.server;

import android.net.IpPrefix;
import android.util.Log;

import java.io.IOException;

/**
 * This coordinator is responsible for providing clat relevant functionality.
 *
 * {@hide}
 */
public class ClatCoordinator {
    private static final String TAG = ClatCoordinator.class.getSimpleName();

    // This must match the interface prefix in clatd.c.
    private static final String CLAT_PREFIX = "v4-";

    // For historical reasons, start with 192.0.0.4, and after that, use all subsequent addresses in
    // 192.0.0.0/29 (RFC 7335).
    private static final String V4ADDR_STRING = "192.0.0.4";
    private static final int V4ADDR_PREFIX_LEN = 29;


    public String startClat(final String iface, final int netId, final IpPrefix nat64Prefix) throws IOException {
        if (nat64Prefix == null) return null;

        int tunFd = createTunInterface(CLAT_PREFIX + iface);
        if (tunFd == -1) return null;

        // disable IPv6 on it - failing to do so is not a critical error
        // (TODO)

        // 5. initialize tracker object
        final String v4 = selectIpv4Address(V4ADDR_STRING, V4ADDR_PREFIX_LEN);
        Log.w(TAG, "[SSD] v4 " + v4);
        if (v4 == null) {
            Log.e(TAG, "no IPv4 addresses were available for clat");
            return null;
        }

        final String pfx96 = nat64Prefix.getAddress().getHostAddress();
        final String v6 = generateIpv6Address(iface, v4, pfx96);
        Log.w(TAG, "[SSD] v6 " + v6);
        if (v6 == null) {
            Log.e(TAG, "no IPv6 addresses were available for clat");
            return null;
        }

         // 11. add the drop rule for iptables.
         // (TODO)

        // 0xf0000 = PERMISSION_SYSTEM | explicitlySelected = true | protectedFromVpn = trues
        final Integer fwmark = 0xf0000 | (netId & 0xffff);
        final int pid = spawnClatd(tunFd, iface, "0x" + Integer.toHexString(fwmark), pfx96, v4, v6);
        Log.w(TAG, "[SSD] fwmark: " + "0x" + Integer.toHexString(fwmark));
        Log.w(TAG, "[SSD] clatd pid " + pid);
        if (pid == -1) {
            Log.e(TAG, "spawn clatd failed");
            return null;
        }

        return v6;
    }

    private static native int createTunInterface(String iface) throws IOException;
    private static native String selectIpv4Address(String v4addr, int prefixlen) throws IOException;
    private static native String generateIpv6Address(String iface, String v4, String prefix64) throws IOException;
    private static native int spawnClatd(int tunfd, String iface, String fwmark, String pfx96, String v4, String v6) throws IOException;
}
