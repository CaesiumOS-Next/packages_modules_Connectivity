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

package com.android.server.connectivity;

import static android.net.INetd.IF_STATE_UP;
import static android.net.INetd.PERMISSION_SYSTEM;

import static com.android.net.module.util.NetworkStackConstants.IPV6_MIN_MTU;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.INetd;
import android.net.InetAddresses;
import android.net.InterfaceConfigurationParcel;
import android.net.IpPrefix;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.system.ErrnoException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.net.module.util.BpfMap;
import com.android.net.module.util.InterfaceParams;
import com.android.net.module.util.TcUtils;
import com.android.net.module.util.bpf.ClatEgress4Key;
import com.android.net.module.util.bpf.ClatEgress4Value;
import com.android.net.module.util.bpf.ClatIngress6Key;
import com.android.net.module.util.bpf.ClatIngress6Value;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;

/**
 * This coordinator is responsible for providing clat relevant functionality.
 *
 * {@hide}
 */
public class ClatCoordinator {
    private static final String TAG = ClatCoordinator.class.getSimpleName();

    // Sync from external/android-clat/clatd.c
    // 40 bytes IPv6 header - 20 bytes IPv4 header + 8 bytes fragment header.
    @VisibleForTesting
    static final int MTU_DELTA = 28;
    @VisibleForTesting
    static final int CLAT_MAX_MTU = 65536;

    // This must match the interface prefix in clatd.c.
    private static final String CLAT_PREFIX = "v4-";

    // For historical reasons, start with 192.0.0.4, and after that, use all subsequent addresses
    // in 192.0.0.0/29 (RFC 7335).
    @VisibleForTesting
    static final String INIT_V4ADDR_STRING = "192.0.0.4";
    @VisibleForTesting
    static final int INIT_V4ADDR_PREFIX_LEN = 29;
    private static final InetAddress GOOGLE_DNS_4 = InetAddress.parseNumericAddress("8.8.8.8");

    private static final int INVALID_IFINDEX = 0;
    private static final int INVALID_PID = 0;
    private static final long INVALID_COOKIE = 0;

    private static final String CLAT_EGRESS4_MAP_PATH = makeMapPath("egress4");
    private static final String CLAT_INGRESS6_MAP_PATH = makeMapPath("ingress6");

    private static String makeMapPath(String which) {
        return "/sys/fs/bpf/map_clatd_clat_" + which + "_map";
    }

    // For better code clarity when used for 'oifIsEthernet' parameter in ClatEgress4Value.
    static final short ETHER = 1;
    static final short RAWIP = 0;

    @NonNull
    private final INetd mNetd;
    @NonNull
    private final Dependencies mDeps;
    @Nullable
    private final BpfMap<ClatIngress6Key, ClatIngress6Value> mIngressMap;
    @Nullable
    private final BpfMap<ClatEgress4Key, ClatEgress4Value> mEgressMap;
    @Nullable
    private String mIface = null;
    @Nullable
    private String mNat64Prefix = null;
    @Nullable
    private String mXlatLocalAddress4 = null;
    @Nullable
    private String mXlatLocalAddress6 = null;
    private int mPid = INVALID_PID;
    private long mCookie = INVALID_COOKIE;

    // Used to record interface index and stacked interface index.
    // It is used to avoid that we can't make the key because the interface index can't be
    // queried after an interface is destroyed earlier while removing a bpf entry.
    private int mIfaceIndex = INVALID_IFINDEX;
    private int mStackedIfaceIndex = INVALID_IFINDEX;

    /**
     * Dependencies of ClatCoordinator which makes ConnectivityService injection
     * in tests.
     */
    @VisibleForTesting
    public abstract static class Dependencies {
        /**
          * Get netd.
          */
        @NonNull
        public abstract INetd getNetd();

        /**
         * @see ParcelFileDescriptor#adoptFd(int).
         */
        @NonNull
        public ParcelFileDescriptor adoptFd(int fd) {
            return ParcelFileDescriptor.adoptFd(fd);
        }

        /**
         * Get interface index for a given interface.
         */
        public int getInterfaceIndex(String ifName) {
            final InterfaceParams params = InterfaceParams.getByName(ifName);
            return params != null ? params.index : INVALID_IFINDEX;
        }

        /**
         * Create tun interface for a given interface name.
         */
        public int createTunInterface(@NonNull String tuniface) throws IOException {
            return native_createTunInterface(tuniface);
        }

        /**
         * Pick an IPv4 address for clat.
         */
        @NonNull
        public String selectIpv4Address(@NonNull String v4addr, int prefixlen)
                throws IOException {
            return native_selectIpv4Address(v4addr, prefixlen);
        }

        /**
         * Generate a checksum-neutral IID.
         */
        @NonNull
        public String generateIpv6Address(@NonNull String iface, @NonNull String v4,
                @NonNull String prefix64) throws IOException {
            return native_generateIpv6Address(iface, v4, prefix64);
        }

        /**
         * Detect MTU.
         */
        public int detectMtu(@NonNull String platSubnet, int platSuffix, int mark)
                throws IOException {
            return native_detectMtu(platSubnet, platSuffix, mark);
        }

        /**
         * Open packet socket.
         */
        public int openPacketSocket() throws IOException {
            return native_openPacketSocket();
        }

        /**
         * Open IPv6 raw socket and set SO_MARK.
         */
        public int openRawSocket6(int mark) throws IOException {
            return native_openRawSocket6(mark);
        }

        /**
         * Add anycast setsockopt.
         */
        public void addAnycastSetsockopt(@NonNull FileDescriptor sock, String v6, int ifindex)
                throws IOException {
            native_addAnycastSetsockopt(sock, v6, ifindex);
        }

        /**
         * Configure packet socket.
         */
        public void configurePacketSocket(@NonNull FileDescriptor sock, String v6, int ifindex)
                throws IOException {
            native_configurePacketSocket(sock, v6, ifindex);
        }

        /**
         * Start clatd.
         */
        public int startClatd(@NonNull FileDescriptor tunfd, @NonNull FileDescriptor readsock6,
                @NonNull FileDescriptor writesock6, @NonNull String iface, @NonNull String pfx96,
                @NonNull String v4, @NonNull String v6) throws IOException {
            return native_startClatd(tunfd, readsock6, writesock6, iface, pfx96, v4, v6);
        }

        /**
         * Stop clatd.
         */
        public void stopClatd(String iface, String pfx96, String v4, String v6, int pid)
                throws IOException {
            native_stopClatd(iface, pfx96, v4, v6, pid);
        }

        /**
         * Tag socket as clat.
         */
        public long tagSocketAsClat(@NonNull FileDescriptor sock) throws IOException {
            return native_tagSocketAsClat(sock);
        }

        /**
         * Untag socket.
         */
        public void untagSocket(long cookie) throws IOException {
            native_untagSocket(cookie);
        }

        /** Get ingress6 BPF map. */
        @Nullable
        public BpfMap<ClatIngress6Key, ClatIngress6Value> getBpfIngress6Map() {
            try {
                return new BpfMap<>(CLAT_INGRESS6_MAP_PATH,
                    BpfMap.BPF_F_RDWR, ClatIngress6Key.class, ClatIngress6Value.class);
            } catch (ErrnoException e) {
                Log.e(TAG, "Cannot create ingress6 map: " + e);
                return null;
            }
        }

        /** Get egress4 BPF map. */
        @Nullable
        public BpfMap<ClatEgress4Key, ClatEgress4Value> getBpfEgress4Map() {
            try {
                return new BpfMap<>(CLAT_EGRESS4_MAP_PATH,
                    BpfMap.BPF_F_RDWR, ClatEgress4Key.class, ClatEgress4Value.class);
            } catch (ErrnoException e) {
                Log.e(TAG, "Cannot create egress4 map: " + e);
                return null;
            }
        }

        /** Checks if the network interface uses an ethernet L2 header. */
        public boolean isEthernet(String iface) throws IOException {
            return TcUtils.isEthernet(iface);
        }
    }

    @VisibleForTesting
    static int getFwmark(int netId) {
        // See union Fwmark in system/netd/include/Fwmark.h
        return (netId & 0xffff)
                | 0x1 << 16  // protectedFromVpn: true
                | 0x1 << 17  // explicitlySelected: true
                | (PERMISSION_SYSTEM & 0x3) << 18;
    }

    @VisibleForTesting
    static int adjustMtu(int mtu) {
        // clamp to minimum ipv6 mtu - this probably cannot ever trigger
        if (mtu < IPV6_MIN_MTU) mtu = IPV6_MIN_MTU;
        // clamp to buffer size
        if (mtu > CLAT_MAX_MTU) mtu = CLAT_MAX_MTU;
        // decrease by ipv6(40) + ipv6 fragmentation header(8) vs ipv4(20) overhead of 28 bytes
        mtu -= MTU_DELTA;

        return mtu;
    }

    public ClatCoordinator(@NonNull Dependencies deps) {
        mDeps = deps;
        mNetd = mDeps.getNetd();
        mIngressMap = mDeps.getBpfIngress6Map();
        mEgressMap = mDeps.getBpfEgress4Map();
    }

    // The interface information {ifaceStr, ifaceIndex, tunIfaceIndex, pfx96Str, v4Str, v6Str}
    // validation is guaranteed by #clatStart.
    private void maybeStartBpf(final String ifaceStr, final int ifaceIndex,
            final int tunIfaceIndex, final String pfx96Str, final String v4Str,
            final String v6Str) {
        // TODO: attach program.

        if (mIngressMap == null || mEgressMap == null) return;

        final short isEthernet;
        try {
            isEthernet = mDeps.isEthernet(ifaceStr) ? ETHER : RAWIP;
        } catch (IOException e) {
            Log.e(TAG, "Fail to call isEthernet for interface " + ifaceStr);
            return;
        }

        final Inet4Address local4 = (Inet4Address) InetAddresses.parseNumericAddress(v4Str);
        final Inet6Address local6 = (Inet6Address) InetAddresses.parseNumericAddress(v6Str);
        final Inet6Address pfx96 = (Inet6Address) InetAddresses.parseNumericAddress(pfx96Str);

        final ClatEgress4Key txKey = new ClatEgress4Key(tunIfaceIndex, local4);
        final ClatEgress4Value txValue = new ClatEgress4Value(ifaceIndex, local6, pfx96,
                isEthernet);
        final ClatIngress6Key rxKey = new ClatIngress6Key(ifaceIndex, pfx96, local6);
        final ClatIngress6Value rxValue = new ClatIngress6Value(tunIfaceIndex, local4);

        try {
            mEgressMap.insertEntry(txKey, txValue);
        } catch (ErrnoException | IllegalStateException e) {
            Log.e(TAG, "Could not insert entry (" + txKey + ", " + txValue + "): " + e);
            return;
        }

        try {
            mIngressMap.insertEntry(rxKey, rxValue);
        } catch (ErrnoException | IllegalStateException e) {
            // TODO: rewinding the egress map
            Log.e(TAG, "Could not insert entry (" + rxKey + ", " + rxValue + "): " + e);
            return;
        }
    }

    // The parameter {ifaceIndex, tunIfaceIndex, pfx96Str, v4Str, v6Str} is passed by #clatStop
    // which uses #clatStart has initialized and validated member variables.
    private void maybeStopBpf(final int ifaceIndex, final int tunIfaceIndex,
            final String pfx96Str, final String v4Str, final String v6Str) {
        // TODO: dettach program.

        final Inet4Address local4 = (Inet4Address) InetAddresses.parseNumericAddress(v4Str);
        final Inet6Address local6 = (Inet6Address) InetAddresses.parseNumericAddress(v6Str);
        final Inet6Address pfx96 = (Inet6Address) InetAddresses.parseNumericAddress(pfx96Str);

        final ClatEgress4Key txKey = new ClatEgress4Key(tunIfaceIndex, local4);
        final ClatIngress6Key rxKey = new ClatIngress6Key(ifaceIndex, pfx96, local6);

        try {
            mEgressMap.deleteEntry(txKey);
        } catch (ErrnoException | IllegalStateException e) {
            Log.e(TAG, "Could not delete entry (" + txKey + "): " + e);
        }

        try {
            mIngressMap.deleteEntry(rxKey);
        } catch (ErrnoException | IllegalStateException e) {
            Log.e(TAG, "Could not delete entry (" + rxKey + "): " + e);
        }
    }

    /**
     * Start clatd for a given interface and NAT64 prefix.
     */
    public String clatStart(final String iface, final int netId,
            @NonNull final IpPrefix nat64Prefix)
            throws IOException {
        if (mIface != null || mPid != INVALID_PID) {
            throw new IOException("Clatd is already running on " + mIface + " (pid " + mPid + ")");
        }
        if (nat64Prefix.getPrefixLength() != 96) {
            throw new IOException("Prefix must be 96 bits long: " + nat64Prefix);
        }

        // [1] Pick an IPv4 address from 192.0.0.4, 192.0.0.5, 192.0.0.6 ..
        final String v4;
        try {
            v4 = mDeps.selectIpv4Address(INIT_V4ADDR_STRING, INIT_V4ADDR_PREFIX_LEN);
        } catch (IOException e) {
            throw new IOException("no IPv4 addresses were available for clat: " + e);
        }

        // [2] Generate a checksum-neutral IID.
        final String pfx96 = nat64Prefix.getAddress().getHostAddress();
        final String v6;
        try {
            v6 = mDeps.generateIpv6Address(iface, v4, pfx96);
        } catch (IOException e) {
            throw new IOException("no IPv6 addresses were available for clat: " + e);
        }

        // [3] Open, configure and bring up the tun interface.
        // Create the v4-... tun interface.
        final String tunIface = CLAT_PREFIX + iface;
        final ParcelFileDescriptor tunFd;
        try {
            tunFd = mDeps.adoptFd(mDeps.createTunInterface(tunIface));
        } catch (IOException e) {
            throw new IOException("Create tun interface " + tunIface + " failed: " + e);
        }

        // disable IPv6 on it - failing to do so is not a critical error
        try {
            mNetd.interfaceSetEnableIPv6(tunIface, false /* enabled */);
        } catch (RemoteException | ServiceSpecificException e) {
            tunFd.close();
            Log.e(TAG, "Disable IPv6 on " + tunIface + " failed: " + e);
        }

        // Detect ipv4 mtu.
        final Integer fwmark = getFwmark(netId);
        final int detectedMtu = mDeps.detectMtu(pfx96,
                ByteBuffer.wrap(GOOGLE_DNS_4.getAddress()).getInt(), fwmark);
        final int mtu = adjustMtu(detectedMtu);
        Log.i(TAG, "ipv4 mtu is " + mtu);

        // TODO: add setIptablesDropRule

        // Config tun interface mtu, address and bring up.
        try {
            mNetd.interfaceSetMtu(tunIface, mtu);
        } catch (RemoteException | ServiceSpecificException e) {
            tunFd.close();
            throw new IOException("Set MTU " + mtu + " on " + tunIface + " failed: " + e);
        }
        final InterfaceConfigurationParcel ifConfig = new InterfaceConfigurationParcel();
        ifConfig.ifName = tunIface;
        ifConfig.ipv4Addr = v4;
        ifConfig.prefixLength = 32;
        ifConfig.hwAddr = "";
        ifConfig.flags = new String[] {IF_STATE_UP};
        try {
            mNetd.interfaceSetCfg(ifConfig);
        } catch (RemoteException | ServiceSpecificException e) {
            tunFd.close();
            throw new IOException("Setting IPv4 address to " + ifConfig.ipv4Addr + "/"
                    + ifConfig.prefixLength + " failed on " + ifConfig.ifName + ": " + e);
        }

        // [4] Open and configure local 464xlat read/write sockets.
        // Opens a packet socket to receive IPv6 packets in clatd.
        final ParcelFileDescriptor readSock6;
        try {
            // Use a JNI call to get native file descriptor instead of Os.socket() because we would
            // like to use ParcelFileDescriptor to manage file descriptor. But ctor
            // ParcelFileDescriptor(FileDescriptor fd) is a @hide function. Need to use native file
            // descriptor to initialize ParcelFileDescriptor object instead.
            readSock6 = mDeps.adoptFd(mDeps.openPacketSocket());
        } catch (IOException e) {
            tunFd.close();
            throw new IOException("Open packet socket failed: " + e);
        }

        // Opens a raw socket with a given fwmark to send IPv6 packets in clatd.
        final ParcelFileDescriptor writeSock6;
        try {
            // Use a JNI call to get native file descriptor instead of Os.socket(). See above
            // reason why we use jniOpenPacketSocket6().
            writeSock6 = mDeps.adoptFd(mDeps.openRawSocket6(fwmark));
        } catch (IOException e) {
            tunFd.close();
            readSock6.close();
            throw new IOException("Open raw socket failed: " + e);
        }

        final int ifaceIndex = mDeps.getInterfaceIndex(iface);
        if (ifaceIndex == INVALID_IFINDEX) {
            tunFd.close();
            readSock6.close();
            writeSock6.close();
            throw new IOException("Fail to get interface index for interface " + iface);
        }

        // Start translating packets to the new prefix.
        try {
            mDeps.addAnycastSetsockopt(writeSock6.getFileDescriptor(), v6, ifaceIndex);
        } catch (IOException e) {
            tunFd.close();
            readSock6.close();
            writeSock6.close();
            throw new IOException("add anycast sockopt failed: " + e);
        }

        // Tag socket as AID_CLAT to avoid duplicated CLAT data usage accounting.
        long cookie;
        try {
            cookie = mDeps.tagSocketAsClat(writeSock6.getFileDescriptor());
        } catch (IOException e) {
            tunFd.close();
            readSock6.close();
            writeSock6.close();
            throw new IOException("tag raw socket failed: " + e);
        }

        // Update our packet socket filter to reflect the new 464xlat IP address.
        try {
            mDeps.configurePacketSocket(readSock6.getFileDescriptor(), v6, ifaceIndex);
        } catch (IOException e) {
            tunFd.close();
            readSock6.close();
            writeSock6.close();
            throw new IOException("configure packet socket failed: " + e);
        }

        // [5] Start clatd.
        final int tunIfaceIndex = mDeps.getInterfaceIndex(tunIface);
        if (tunIfaceIndex == INVALID_IFINDEX) {
            tunFd.close();
            readSock6.close();
            writeSock6.close();
            throw new IOException("Fail to get interface index for interface " + tunIface);
        }

        try {
            mPid = mDeps.startClatd(tunFd.getFileDescriptor(), readSock6.getFileDescriptor(),
                    writeSock6.getFileDescriptor(), iface, pfx96, v4, v6);
            maybeStartBpf(iface, ifaceIndex, tunIfaceIndex, pfx96, v4, v6);

            mIface = iface;
            mIfaceIndex = ifaceIndex;
            mStackedIfaceIndex = tunIfaceIndex;
            mNat64Prefix = pfx96;
            mXlatLocalAddress4 = v4;
            mXlatLocalAddress6 = v6;
            mCookie = cookie;
        } catch (IOException e) {
            mDeps.untagSocket(cookie);
            throw new IOException("Error start clatd on " + iface + ": " + e);
        } finally {
            tunFd.close();
            readSock6.close();
            writeSock6.close();
        }

        return v6;
    }

    /**
     * Stop clatd
     */
    public void clatStop() throws IOException {
        if (mPid == INVALID_PID) {
            throw new IOException("Clatd has not started");
        }
        Log.i(TAG, "Stopping clatd pid=" + mPid + " on " + mIface);

        mDeps.stopClatd(mIface, mNat64Prefix, mXlatLocalAddress4, mXlatLocalAddress6, mPid);
        mDeps.untagSocket(mCookie);
        maybeStopBpf(mIfaceIndex, mStackedIfaceIndex, mNat64Prefix, mXlatLocalAddress4,
                mXlatLocalAddress6);

        Log.i(TAG, "clatd on " + mIface + " stopped");

        mIface = null;
        mIfaceIndex = INVALID_IFINDEX;
        mStackedIfaceIndex = INVALID_IFINDEX;
        mNat64Prefix = null;
        mXlatLocalAddress4 = null;
        mXlatLocalAddress6 = null;
        mPid = INVALID_PID;
        mCookie = INVALID_COOKIE;
    }

    private void dumpBpfIngress(@NonNull IndentingPrintWriter pw) {
        if (mIngressMap == null) {
            pw.println("No BPF ingress6 map");
            return;
        }

        try {
            if (mIngressMap.isEmpty()) {
                pw.println("<empty>");
            }
            pw.println("BPF ingress map: iif nat64Prefix v6Addr -> v4Addr oif");
            pw.increaseIndent();
            mIngressMap.forEach((k, v) -> {
                // TODO: print interface name
                pw.println(String.format("%d %s/96 %s -> %s %d", k.iif, k.pfx96, k.local6,
                        v.local4, v.oif));
            });
            pw.decreaseIndent();
        } catch (ErrnoException e) {
            pw.println("Error dumping BPF ingress6 map: " + e);
        }
    }

    private void dumpBpfEgress(@NonNull IndentingPrintWriter pw) {
        if (mEgressMap == null) {
            pw.println("No BPF egress4 map");
            return;
        }

        try {
            if (mEgressMap.isEmpty()) {
                pw.println("<empty>");
            }
            pw.println("BPF egress map: iif v4Addr -> v6Addr nat64Prefix oif");
            pw.increaseIndent();
            mEgressMap.forEach((k, v) -> {
                // TODO: print interface name
                pw.println(String.format("%d %s -> %s %s/96 %d %s", k.iif, k.local4, v.local6,
                        v.pfx96, v.oif, v.oifIsEthernet == ETHER ? "ether" : "rawip"));
            });
            pw.decreaseIndent();
        } catch (ErrnoException e) {
            pw.println("Error dumping BPF egress4 map: " + e);
        }
    }

    /**
     * Dump the cordinator information.
     *
     * @param pw print writer.
     */
    public void dump(@NonNull IndentingPrintWriter pw) {
        pw.println("Interface: " + mIface);
        pw.println("Nat64Prefix: " + mNat64Prefix);
        pw.println("XlatLocalAddress4: " + mXlatLocalAddress4);
        pw.println("XlatLocalAddress6: " + mXlatLocalAddress6);
        pw.println("Caltd pid: " + (mPid > 0 ? mPid : "<not started>"));
        pw.println("Socket cookie: " + (mCookie > 0 ? mCookie : "<not started>"));

        pw.println("Forwarding rules:");
        pw.increaseIndent();
        dumpBpfIngress(pw);
        dumpBpfEgress(pw);
        pw.decreaseIndent();
        pw.println();
    }

    private static native String native_selectIpv4Address(String v4addr, int prefixlen)
            throws IOException;
    private static native String native_generateIpv6Address(String iface, String v4,
            String prefix64) throws IOException;
    private static native int native_createTunInterface(String tuniface) throws IOException;
    private static native int native_detectMtu(String platSubnet, int platSuffix, int mark)
            throws IOException;
    private static native int native_openPacketSocket() throws IOException;
    private static native int native_openRawSocket6(int mark) throws IOException;
    private static native void native_addAnycastSetsockopt(FileDescriptor sock, String v6,
            int ifindex) throws IOException;
    private static native void native_configurePacketSocket(FileDescriptor sock, String v6,
            int ifindex) throws IOException;
    private static native int native_startClatd(FileDescriptor tunfd, FileDescriptor readsock6,
            FileDescriptor writesock6, String iface, String pfx96, String v4, String v6)
            throws IOException;
    private static native void native_stopClatd(String iface, String pfx96, String v4, String v6,
            int pid) throws IOException;
    private static native long native_tagSocketAsClat(FileDescriptor sock) throws IOException;
    private static native void native_untagSocket(long cookie) throws IOException;
}
