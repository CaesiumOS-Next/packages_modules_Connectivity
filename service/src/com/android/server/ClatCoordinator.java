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
import static android.net.INetd.PERMISSION_SYSTEM;

import static com.android.net.module.util.NetworkStackConstants.IPV6_MIN_MTU;

import android.net.INetd;
import android.net.InetAddresses;
import android.net.InterfaceConfigurationParcel;
import android.net.IpPrefix;
import android.system.Os;
import android.util.Log;

import android.os.RemoteException;
import android.os.ServiceSpecificException;

import com.android.net.module.util.InterfaceParams;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;

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

    // Sync from external/android-clat/clatd.c
    // 40 bytes IPv6 header - 20 bytes IPv4 header + 8 bytes fragment header.
    private static final int MTU_DELTA = 28;
    private static final int CLAT_MAX_MTU = 65536;

    // This must match the interface prefix in clatd.c.
    private static final String CLAT_PREFIX = "v4-";

    // For historical reasons, start with 192.0.0.4, and after that, use all subsequent addresses in
    // 192.0.0.0/29 (RFC 7335).
    private static final String V4ADDR_STRING = "192.0.0.4";
    private static final int V4ADDR_PREFIX_LEN = 29;
    private static final InetAddress GOOGLE_DNS_4 = InetAddress.parseNumericAddress("8.8.8.8");

    private final INetd mNetd;
    private String mIface;
    private int mPid = 0;

    public ClatCoordinator(INetd netd) {
        mNetd = netd;
    }

    private int getFwmark(int netId) {
        // See Fwmark in system/netd/include/Fwmark.h
        return (netId & 0xffff)
                | 0x1 << 16  // protectedFromVpn: true
                | 0x1 << 17  // explicitlySelected: true
                | (PERMISSION_SYSTEM & 0x3) << 18;
    }

    public String clatStart(final String iface, final int netId, final IpPrefix nat64Prefix)
            throws IOException {
        if (nat64Prefix == null) return null;

        final String tunIface = CLAT_PREFIX + iface;

        final int tunFd = createTunInterface(tunIface);
        if (tunFd == -1) return null;

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

        final Integer fwmark = getFwmark(netId);
        int mtu = detectMtu(pfx96, ByteBuffer.wrap(GOOGLE_DNS_4.getAddress()).getInt(), fwmark);
        // clamp to minimum ipv6 mtu - this probably cannot ever trigger
        if (mtu < IPV6_MIN_MTU) mtu = IPV6_MIN_MTU;
        // clamp to buffer size
        if (mtu > CLAT_MAX_MTU) mtu = CLAT_MAX_MTU;
        // decrease by ipv6(40) + ipv6 fragmentation header(8) vs ipv4(20) overhead of 28 bytes
        mtu -= MTU_DELTA;
        Log.w(TAG, "ipv4 mtu is " + mtu);

        // Config tun interface address and bring up.
        try {
            mNetd.interfaceSetMtu(tunIface, mtu);
        } catch (RemoteException | ServiceSpecificException e) {
            Log.e(TAG, "Set MTU " + mtu + " on " + tunIface + " failed: " + e);
            return null;
        }

        final InterfaceConfigurationParcel ifConfig = new InterfaceConfigurationParcel();
        ifConfig.ifName = tunIface;
        ifConfig.ipv4Addr = v4;
        ifConfig.prefixLength = 32;
        ifConfig.hwAddr = "";
        ifConfig.flags = new String[] {IF_STATE_UP};
        try {
            // TODO: use InterfaceController
            mNetd.interfaceSetCfg(ifConfig);
        } catch (RemoteException | ServiceSpecificException e) {
            Log.e(TAG, "Setting IPv4 address to " + ifConfig.ipv4Addr + "/"
                    + ifConfig.prefixLength + " failed on " + ifConfig.ifName + ": " + e);
            return null;
        }

        // Configure clat IPv6 address.
        final int clatSockWrite = openClatWriteSocket(fwmark);
        if (clatSockWrite == -1) {
            Log.e(TAG, "Open tunnel write socket failed");
            return null;
        }
        final int clatSockRead = openClatReadSocket();
        if (clatSockRead == -1) {
            Log.e(TAG, "Open tunnel read socket failed");
            // Os.close(clatSockWrite); TODO: close the socket clatSockWrite, tunFd
            return null;
        }

        Log.w(TAG, "clatSockWrite: " + clatSockWrite + ", clatSockRead " + clatSockRead);  //+debug

        // Start translating packets to the new prefix.
        final InterfaceParams tunParams = InterfaceParams.getByName(tunIface);
        if (tunParams == null) {
            Log.e(TAG, "Fail to get interface params for interface " + tunIface);
            // TODO: close the socket clatSockWrite, clatSockRead, tunFd
            return null;
        }
        Log.w(TAG, "tunParams.index: " + tunParams.index);  //+debug


        final InterfaceParams nativeParams = InterfaceParams.getByName(iface);
        if (nativeParams == null) {
            Log.e(TAG, "Fail to get interface params for interface " + iface);
            // TODO: close the socket clatSockWrite, clatSockRead, tunFd
            return null;
        }
        Log.w(TAG, "nativeParams.index: " + nativeParams.index);  //+debug

        if (doAnycastSetsockopt(clatSockWrite, IPV6_JOIN_ANYCAST, v6, nativeParams.index) == -1) {
            Log.w(TAG, "doAnycastSetsockopt IPV6_JOIN_ANYCAST failed");
            // TODO: close the socket clatSockWrite, clatSockRead, tunFd
            return null;
        }

        // Update our packet socket filter to reflect the new 464xlat IP address.
        if (configurePacketSocket(clatSockRead, v6, nativeParams.index) == -1) {
            Log.w(TAG, "configurePacketSocket failed");
            // TODO: close the socket clatSockWrite, clatSockRead, tunFd
            return null;
        }

        // Fork clatd
        // TODO: remove the fwmark which is not necessary any more.
        try {
            mPid = startClatd(tunFd, clatSockRead, clatSockWrite, iface,
                    "0x" + Integer.toHexString(fwmark), pfx96, v4, v6);
            mIface = iface;

            Log.w(TAG, "fwmark: " + "0x" + Integer.toHexString(fwmark));
            Log.w(TAG, "clatd pid " + mPid);
            Log.w(TAG, "tunFd " + tunFd);
            Log.w(TAG, "clatSockRead " + clatSockRead);
            Log.w(TAG, "clatSockWrite " + clatSockWrite);
        } catch (IOException e) {
            Log.e(TAG, "Error start clatd on " + iface + ": " + e);
            return null;
        }

        // File descriptors have been duped and passed to clatd. Close the temporary file
        // descriptors.
        closeFd(tunFd);
        closeFd(clatSockRead);
        closeFd(clatSockWrite);

        return v6;
    }

    public void clatStop(final String iface) throws IOException {
        if (mPid == 0) return;

        Log.i(TAG, "Stopping clatd pid=" + mPid + " on " + mIface);

        stopClatd(mPid);
        // TODO: remove setIptablesDropRule

        Log.i(TAG, "clatd on " + mIface + " stopped");

        mIface = null;
        mPid = 0;
    }

    private static native int createTunInterface(String iface) throws IOException;
    private static native String selectIpv4Address(String v4addr, int prefixlen)
            throws IOException;
    private static native String generateIpv6Address(String iface, String v4, String prefix64)
            throws IOException;
    private static native int detectMtu(String platSubnet, int platSuffix, int mark) throws IOException;
    private static native int openClatWriteSocket(int mark) throws IOException;
    private static native int openClatReadSocket() throws IOException;
    private static native int doAnycastSetsockopt(int sock, int opt, String v6, int ifindex)
            throws IOException;
    private static native int configurePacketSocket(int sock, String v6, int ifindex)
            throws IOException;
    private static native int startClatd(int tunfd, int readSock, int writeSock, String iface,
            String fwmark, String pfx96, String v4, String v6) throws IOException;
    private static native void stopClatd(int pid) throws IOException;
    private static native void closeFd(int fd) throws IOException;
}
