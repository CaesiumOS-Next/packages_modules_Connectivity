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
package com.android.networkstack.tethering;

import static android.system.OsConstants.ETH_P_IP;
import static android.system.OsConstants.ETH_P_IPV6;

import android.net.util.InterfaceParams;

import androidx.annotation.NonNull;

import java.io.IOException;

/**
 * The classes and the methods for BPF utilization.
 *
 * {@hide}
 */
public class BpfUtils {
    static {
        System.loadLibrary("tetherutilsjni");
    }

    // The priority of clat/tether hooks - smaller is higher priority.
    // TC tether is higher priority then TC clat to match XDP winning over TC.
    // TODO: remove the prio base hack once netd has removed the prog attachment.
    static final short PRIO_TETHER_BASE = 10;
    static final short PRIO_TETHER6 = PRIO_TETHER_BASE;
    static final short PRIO_TETHER4 = PRIO_TETHER_BASE + 1;
    static final short PRIO_CLAT = PRIO_TETHER_BASE + 2;  // TODO: remove because unused?

    // For better code clarity - do not change values - used for booleans like
    // with_ethernet_header or isEthernet.
    static final boolean RAWIP = false;
    static final boolean ETHER = true;

    // For better code clarity when used for 'bool ingress' parameter.
    static final boolean EGRESS = false;
    static final boolean INGRESS = true;

    // For better code clarity when used for 'bool downstream' parameter.
    static final boolean DOWNSTREAM = true;
    static final boolean UPSTREAM = false;

    private static String makeProgPath(boolean downstream, int ipVersion, boolean ether) {
        String path = "/sys/fs/bpf/tethering/prog_offload_schedcls_tether_";
        path += (downstream ? "downstream" : "upstream");
        path += ipVersion + "_";
        path += (ether ? "ether" : "rawip");
        return path;
    }

    // tc filter add dev .. ingress prio 1 protocol ipv6 bpf object-pinned /sys/fs/bpf/...
    // direct-action
    private static void tcFilterAddDevIngress6Tether(int ifIndex, boolean ether, boolean downstream)
            throws IOException {
        tcFilterAddDevBpf(ifIndex, INGRESS, (short) PRIO_TETHER6, (short) ETH_P_IPV6,
                makeProgPath(downstream, 6, ether));
    }

    // tc filter add dev .. ingress prio 2 protocol ip bpf object-pinned /sys/fs/bpf/...
    // direct-action
    private static void tcFilterAddDevIngress4Tether(int ifIndex, boolean ether, boolean downstream)
            throws IOException {
        tcFilterAddDevBpf(ifIndex, INGRESS, (short) PRIO_TETHER4, (short) ETH_P_IP,
                makeProgPath(downstream, 4, ether));
    }

    // tc filter del dev .. ingress prio 1 protocol ipv6
    private static void tcFilterDelDevIngress6Tether(int ifIndex) throws IOException {
        tcFilterDelDev(ifIndex, INGRESS, (short) PRIO_TETHER6, (short) ETH_P_IPV6);
    }

    // tc filter del dev .. ingress prio 2 protocol ip
    private static void tcFilterDelDevIngress4Tether(int ifIndex) throws IOException {
        tcFilterDelDev(ifIndex, INGRESS, (short) PRIO_TETHER4, (short) ETH_P_IP);
    }

    /** Attach BPF program */
    public static void attachProgram(@NonNull String iface, boolean downstream)
            throws IOException {
        final InterfaceParams params = InterfaceParams.getByName(iface);
        if (params == null) {
            throw new IOException("Fail to get interface params for interface " + iface);
        }

        boolean ether;
        try {
            ether = isEthernet(iface);
        } catch (IOException e) {
            throw new IOException("isEthernet(" + params.index + "[" + iface + "]) failure: " + e);
        }

        try {
            tcFilterAddDevIngress6Tether(params.index, ether, downstream);
        } catch (IOException e) {
            throw new IOException("tcFilterAddDevIngress6Tether(" + params.index + "[" + iface
                    + "]) failure: " + e);
        }

        try {
            tcFilterAddDevIngress4Tether(params.index, ether, downstream);
        } catch (IOException e) {
            throw new IOException("tcFilterAddDevIngress4Tether(" + params.index + "[" + iface
                    + "]) failure: " + e);
        }
    }

    /** Detach BPF program */
    public static void detachProgram(@NonNull String iface) throws IOException {
        final InterfaceParams params = InterfaceParams.getByName(iface);
        if (params == null) {
            throw new IOException("Fail to get interface params for interface " + iface);
        }

        try {
            tcFilterDelDevIngress6Tether(params.index);
        } catch (IOException e) {
            throw new IOException("tcFilterDelDevIngress6Tether(" + params.index + "[" + iface
                    + "]) failure: " + e);
        }

        try {
            tcFilterDelDevIngress4Tether(params.index);
        } catch (IOException e) {
            throw new IOException("tcFilterDelDevIngress4Tether(" + params.index + "[" + iface
                    + "]) failure: " + e);
        }
    }

    private static native boolean isEthernet(String iface) throws IOException;

    private static native void tcFilterAddDevBpf(int ifIndex, boolean ingress, short prio,
            short proto, String bpfProgPath) throws IOException;

    private static native void tcFilterDelDev(int ifIndex, boolean ingress, short prio,
            short proto) throws IOException;
}
