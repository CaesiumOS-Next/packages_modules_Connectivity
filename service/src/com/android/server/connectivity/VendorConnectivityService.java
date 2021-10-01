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

package com.android.server.connectivity;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.PortBlockParcel;
import android.net.VendorConnectivity;
import android.net.util.SharedLog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.BpfMap;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class VendorConnectivityService extends VendorConnectivity.Stub {
    // Not needed since is present in ConnectivityServiceInitializer?
    // static {
    //     System.loadLibrary("service-connectivity");
    // }

    // Bpf prog is not marked "Critical()" should there be a catch somewhere in case
    // prog does not load

    private static final String TAG = VendorConnectivityService.class.getSimpleName();
    private static final String BLOCKED_PORTS_MAP_PATH = makeMapPath("blocked_ports");

    private static String makeMapPath(String which) {
        return "/sys/fs/bpf/connectivity/map_offload_connectivity_" + which + "_map";
    }

    public static final int CMD_BLOCK_PORT = 1;

    public static final int CMD_UNBLOCK_PORT = 2;

    public static final int CMD_UNBLOCK_ALL_PORTS = 3;

    public static final int CMD_GET_BLOCKED_PORTS = 4;

    private static final String MESSAGE_BUNDLE_KEY_PORT = "port";
    private static final String MESSAGE_BUNDLE_KEY_IP_TYPE = "ipType";
    private static final String MESSAGE_BUNDLE_KEY_PROTOCOL = "protocol";

    // Only needed if fd is created in java
    FileDescriptor fd;

    // BPF map for downstream IPv4 forwarding.
    @Nullable
    private final BpfMap<PortBlockKey, PortBlockValue> mBpfBlockedPortsMap;

    @VisibleForTesting
    public static class Dependencies {
        /**
         * Check OS Build at least T.
         */
        public boolean isAtLeastT() {
            return SdkLevel.isAtLeastT();
        }

        /** Get downstream6 BPF map. */
        @Nullable public BpfMap<PortBlockKey, PortBlockValue> getBlockPortsMap() {
            if (!isAtLeastT()) return null;
            try {
                return new BpfMap<>(BLOCKED_PORTS_MAP_PATH,
                    BpfMap.BPF_F_RDWR, PortBlockKey.class, PortBlockValue.class);
            } catch (ErrnoException e) {
                Log.e(TAG, "Cannot create downstream6 map: " + e);
                return null;
            }

            // Can the FDs be created in Java or should they be done in native instead?
            // Since bpf_loader does not use expected_attach_type only 1 FD should be necessary.
            // fd = new FileDescriptor();
            // attachProgramToCgroup(BLOCKED_PORTS_MAP_PATH, fd, BPF_CGROUP_INET4_BIND);
            // attachProgramToCgroup(BLOCKED_PORTS_MAP_PATH, fd, BPF_CGROUP_INET6_BIND);
        }

        // TODO: what other methods should be added for testing?
    }

    public VendorConnectivityService() {
        this(new Dependencies());
    }

    @VisibleForTesting
    protected VendorConnectivityService(@NonNull Dependencies deps) {
        mBpfBlockedPortsMap = deps.getBlockPortsMap();
    }

    @Override
    public void blockPort(PortBlockParcel parcel) {
        PortBlockKey key = new PortBlockKey(parcel.port, parcel.ipType, parcel.protocol);
        PortBlockValue val = new PortBlockValue(1);
        try {
            mBpfBlockedPortsMap.insertEntry(key, val);
        } catch (ErrnoException e) {
            Log.e(TAG, "Could not insert entry (" + key + ", " + val + "): " + e);
        } catch (IllegalStateException e) {
            // Silent if the rule already exists. Note that the errno EEXIST was rethrown as
            // IllegalStateException. See BpfMap#insertEntry.
        }
    }

    @Override
    public void unblockPort(PortBlockParcel parcel) {
        PortBlockKey key = new PortBlockKey(parcel.port, parcel.ipType, parcel.protocol);
        try {
            mBpfBlockedPortsMap.deleteEntry(key);
        } catch (ErrnoException e) {
            Log.e(TAG, "Could not delete entry (key: " + key + ")" + e);
        }
    }

    @Override
    public void unblockAllPorts() {
        try {
            mBpfBlockedPortsMap.clear();
        } catch (ErrnoException e) {
            Log.e(TAG, "Could not clear map" + e);
        }
    }

    @Override
    public PortBlockParcel[] getBlockedPorts() {
        PortBlockParcel[] portMap = new PortBlockParcel[10];
        return portMap;
    }

    @Override
    public int getInterfaceVersion() {
        return this.VERSION;
    }

    @Override
    public String getInterfaceHash() {
        return this.HASH;
    }

    /**
     * Attach BPF program
     *
     * TODO: consider error handling if the attach program failed.
     */
    public void maybeAttachProgram(@NonNull String iface) {
        // try {
        //     // TODO: is extIface necessary to attach program to
        //     // TODO: Move BpfUtils to common location
        //     BpfUtils.attachProgram(intIface, false);
        //     BpfUtils.attachProgram(extIface, true);
        // } catch (IOException e) {
        //     Log.e(TAG, "Could not detach program: " + e);
        // }
    }

    /**
     * Detach BPF program
     */
    public void maybeDetachProgram(@NonNull String intIface, @NonNull String extIface) {
        // try {
        //     // TODO: is extIface necessary to attach program to
        //     // TODO: Move BpfUtils to common location
        //     BpfUtils.detachProgram(intIface);
        //     BpfUtils.detachProgram(extIface);
        // } catch (IOException e) {
        //     Log.e(TAG, "Could not detach program: " + e);
        // }
    }

    /**
     * Dump information.
     * Block the function until all the data are dumped on the handler thread or timed-out. The
     * reason is that dumpsys invokes this function on the thread of caller and the data may only
     * be allowed to be accessed on the handler thread.
     */
    public void dump(@NonNull IndentingPrintWriter pw) {

    }
}