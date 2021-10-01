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
import com.android.net.module.util.BpfUtils;
import com.android.net.module.util.BpfUtils.BpfAttachType;

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
    private static final String TAG = VendorConnectivityService.class.getSimpleName();
    private static final String PROG_PATH = "/sys/fs/bpf/prog_block_cgroupsockaddr_block_port";
    private static final String BLOCKED_PORTS_MAP_PATH = makeMapPath("blocked_ports");

    private static String makeMapPath(String which) {
        return "/sys/fs/bpf/connectivity/map_offload_connectivity_" + which + "_map";
    }

    private boolean mIsProgramAttached;

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
        }

        // TODO: what other methods should be added for testing?
    }

    public VendorConnectivityService() {
        this(new Dependencies());
    }

    @VisibleForTesting
    protected VendorConnectivityService(@NonNull Dependencies deps) {
        mIsProgramAttached = false;
        mBpfBlockedPortsMap = deps.getBlockPortsMap();
    }

    @Override
    public void blockPort(int port) {
        if (!mIsProgramAttached) {
            if (!maybeAttachProgram()) {
                Log.e(TAG, "Unabled to attach program");
                return;
            }
            mIsProgramAttached = true;
        }

        PortBlockKey key = new PortBlockKey(port);
        PortBlockValue val;
        try {
            val = new PortBlockValue(port, mBpfBlockedPortsMap.getValue(
                new PortBlockKey(port)));
        } catch (ErrnoException e) {
            Log.e(TAG, "Unable to update value from existing val, port: " + port + "): " + e);
            return;
        }

        try {
            mBpfBlockedPortsMap.insertOrReplaceEntry(key, val);
        } catch (ErrnoException e) {
            Log.e(TAG, "Could not insert entry (" + key + ", " + val + "): " + e);
        } catch (IllegalStateException e) {
            // Silent if the rule already exists. Note that the errno EEXIST was rethrown as
            // IllegalStateException. See BpfMap#insertEntry.
        }
    }

    @Override
    public void unblockPort(int port) {
        PortBlockKey key = new PortBlockKey(port);
        try {
            mBpfBlockedPortsMap.deleteEntry(key);
        } catch (ErrnoException e) {
            Log.e(TAG, "Could not delete entry (key: " + key + ")" + e);
        }

        // TODO: should the program be dettached if no entries are left?
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
    public int[] getBlockedPorts() {
        // TODO: return full map
        int[] portMap = new int[10];
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
     */
    private boolean maybeAttachProgram() {
        try {
            BpfUtils.attachProgram(PROG_PATH, BpfAttachType.BPF_CGROUP_INET4_BIND);
        } catch (IOException e) {
            Log.e(TAG, "Unabled to attach to BPF_CGROUP_INET4_BIND: " + e);
            return false;
        }
        try {
            BpfUtils.attachProgram(PROG_PATH, BpfAttachType.BPF_CGROUP_INET6_BIND);
        } catch (IOException e) {
            Log.e(TAG, "Unabled to attach to BPF_CGROUP_INET6_BIND: " + e);
            return false;
        }
        return true;
    }

    /**
     * Detach BPF program
     */
    public void maybeDetachProgram() {
        // Currently unused
        try {
            BpfUtils.detachProgram(BpfAttachType.BPF_CGROUP_INET4_BIND);
        } catch (IOException e) {
            Log.e(TAG, "Unabled to attach to BPF_CGROUP_INET4_BIND: " + e);
        }
        try {
            BpfUtils.detachProgram(BpfAttachType.BPF_CGROUP_INET6_BIND);
        } catch (IOException e) {
            Log.e(TAG, "Unabled to attach to BPF_CGROUP_INET6_BIND: " + e);
        }
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