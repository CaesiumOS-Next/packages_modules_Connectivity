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

import static android.net.INetd.IF_STATE_UP;

import android.net.INetd;
import android.net.InetAddresses;
import android.net.InterfaceConfigurationParcel;
import android.net.IpPrefix;
import com.android.net.module.util.InterfaceParams;
import android.net.util.SharedLog;
import android.system.Os;
import android.util.Log;

import android.os.RemoteException;
import android.os.ServiceSpecificException;
import java.io.IOException;

/**
 * This coordinator is responsible for providing clat relevant functionality.
 *
 * {@hide}
 */
public class ClatCoordinator {
    private static final String TAG = ClatCoordinator.class.getSimpleName();

    // include/uapi/linux/in6.h
    private static final int IPV6_JOIN_ANYCAST = 27;
    private static final int IPV6_LEAVE_ANYCAST = 28;

    // This must match the interface prefix in clatd.c.
    private static final String CLAT_PREFIX = "v4-";

    // For historical reasons, start with 192.0.0.4, and after that, use all subsequent addresses in
    // 192.0.0.0/29 (RFC 7335).
    private static final String V4ADDR_STRING = "192.0.0.4";
    private static final int V4ADDR_PREFIX_LEN = 29;

    private final INetd mNetd;

    public ClatCoordinator(INetd netd) {
        mNetd = netd;
    }

    public String startClat(final String iface, final int netId, final IpPrefix nat64Prefix) throws IOException {
        if (nat64Prefix == null) return null;

        final String tunIface = CLAT_PREFIX + iface;

        final int tunFd = createTunInterface(tunIface);
        if (tunFd == -1) return null;
        //int tunFd = 0;

        // disable IPv6 on it - failing to do so is not a critical error
        try {
            mNetd.interfaceSetEnableIPv6(tunIface, false /* enabled */);
        } catch (RemoteException | ServiceSpecificException e) {
            Log.e(TAG, "Disable IPv6 on " + tunIface + " failed: " + e);
        }

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

        // Config tun interface address and bring up.
        try {
            mNetd.interfaceSetMtu(tunIface, 1280 /*TODO: get MTU correctly*/);
        } catch (RemoteException | ServiceSpecificException e) {
            Log.e(TAG, "Set MTU 1280 on " + tunIface + " failed: " + e);
            return null;
        }

        // TODO: use InterfaceController?
        final InterfaceConfigurationParcel ifConfig = new InterfaceConfigurationParcel();
        ifConfig.ifName = tunIface;
        ifConfig.ipv4Addr = v4;
        ifConfig.prefixLength = 32;
        ifConfig.hwAddr = "";
        ifConfig.flags = new String[] {IF_STATE_UP};
        try {
            mNetd.interfaceSetCfg(ifConfig);
        } catch (RemoteException | ServiceSpecificException e) {
            Log.e(TAG, "Setting IPv4 address to " + ifConfig.ipv4Addr + "/"
                    + ifConfig.prefixLength + " failed on " + ifConfig.ifName + ": " + e);
            return null;
        }

        // Configure clat IPv6 address.
        // 0xf0000 = PERMISSION_SYSTEM | explicitlySelected = true | protectedFromVpn = true
        final Integer fwmark = 0xf0000 | (netId & 0xffff);
        final int tunSockWrite = openTunnelWriteSocket(fwmark);  // TODO: store socket fd for closing.
        if (tunSockWrite == -1) {
            Log.e(TAG, "Open tunnel write socket failed");
            return null;
        }
        final int tunSockRead = openTunnelReadSocket();  // TODO: store socket fd for closing.
        if (tunSockRead == -1) {
            Log.e(TAG, "Open tunnel read socket failed");
            // Os.close(tunSockWrite); TODO: close the socket tunSockWrite, tunFd
            return null;
        }

        Log.w(TAG, "tunSockWrite: " + tunSockWrite + ", tunSockRead " + tunSockRead);  //+debug

        // Start translating packets to the new prefix.
        final InterfaceParams tunParams = InterfaceParams.getByName(tunIface);
        if (tunParams == null) {
            Log.e(TAG, "Fail to get interface params for interface " + tunIface);
            // TODO: close the socket tunSockWrite, tunSockRead, tunFd
            return null;
        }
        Log.w(TAG, "tunParams.index: " + tunParams.index);  //+debug


        final InterfaceParams nativeParams = InterfaceParams.getByName(iface);
        if (nativeParams == null) {
            Log.e(TAG, "Fail to get interface params for interface " + iface);
            // TODO: close the socket tunSockWrite, tunSockRead, tunFd
            return null;
        }
        Log.w(TAG, "nativeParams.index: " + nativeParams.index);  //+debug

        if (doAnycastSetsockopt(tunSockWrite, IPV6_JOIN_ANYCAST, v6, nativeParams.index) == -1) {
            Log.w(TAG, "doAnycastSetsockopt IPV6_JOIN_ANYCAST failed");
            // TODO: close the socket tunSockWrite, tunSockRead, tunFd
            return null;
        }

        // Update our packet socket filter to reflect the new 464xlat IP address.
        if (configurePacketSocket(tunSockRead, v6, nativeParams.index) == -1) {
            Log.w(TAG, "configurePacketSocket failed");
            // TODO: close the socket tunSockWrite, tunSockRead, tunFd
            return null;
        }

        // Fork clatd
        // 0xf0000 = PERMISSION_SYSTEM | explicitlySelected = true | protectedFromVpn = trues
//        final Integer fwmark = 0xf0000 | (netId & 0xffff);
        try {
            final int pid = forkClatd(tunFd, tunSockRead, tunSockWrite, iface, "0x" + Integer.toHexString(fwmark), pfx96, v4, v6);
            // final int pid = mNetd.clatdFork(iface, "0x" + Integer.toHexString(fwmark), pfx96, v4, v6, tunFd);

            Log.w(TAG, "[SSD] fwmark: " + "0x" + Integer.toHexString(fwmark));
            Log.w(TAG, "[SSD] clatd pid " + pid);
            Log.w(TAG, "[SSD] tunFd " + tunFd);
            Log.w(TAG, "[SSD] tunSockRead " + tunSockRead);
            Log.w(TAG, "[SSD] tunSockWrite " + tunSockWrite);

        } catch (IOException e) {
        //} catch (RemoteException | ServiceSpecificException e) {
            Log.e(TAG, "Error fork clatd on " + iface + ": " + e);
            return null;
        }
        return v6;
    }

    private static native int createTunInterface(String iface) throws IOException;
    private static native String selectIpv4Address(String v4addr, int prefixlen) throws IOException;
    private static native String generateIpv6Address(String iface, String v4, String prefix64) throws IOException;
    private static native int openTunnelWriteSocket(int mark) throws IOException;
    private static native int openTunnelReadSocket() throws IOException;
    private static native int doAnycastSetsockopt(int sock, int opt, String v6, int ifindex) throws IOException;
    private static native int configurePacketSocket(int sock, String v6, int ifindex) throws IOException;
    private static native int forkClatd(int tunfd, int readSock, int writeSock, String iface, String fwmark,
            String pfx96, String v4, String v6) throws IOException;
}
