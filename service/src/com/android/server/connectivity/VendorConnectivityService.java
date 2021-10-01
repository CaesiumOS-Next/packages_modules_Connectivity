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

import static com.android.net.module.util.BpfUtils.BPF_CGROUP_INET4_BIND;
import static com.android.net.module.util.BpfUtils.BPF_CGROUP_INET6_BIND;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.VendorConnectivity;
import android.os.Binder;
import android.os.Process;
import android.system.ErrnoException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.BpfBitmap;
import com.android.net.module.util.BpfUtils;

import java.io.IOException;

/**
 * @hide
 */
public class VendorConnectivityService extends VendorConnectivity.Stub {
    private static final String TAG = VendorConnectivityService.class.getSimpleName();
    private static final String V4_PROG_PATH =
            "/sys/fs/bpf/prog_block_bind4_block_ipv4_port";
    private static final String V6_PROG_PATH =
            "/sys/fs/bpf/prog_block_bind6_block_ipv6_port";
    private static final String BLOCKED_PORTS_MAP_PATH = makeMapPath("blocked_ports");

    private static String makeMapPath(String which) {
        return "/sys/fs/bpf/map_block_" + which + "_map";
    }

    private boolean mIsProgramAttached;

    // BPF map for downstream IPv4 forwarding.
    @Nullable
    private final BpfBitmap mBpfBlockedPortsMap;

    /**
     * Dependencies of VendorConnectivityService, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        /**
         * Check OS Build at least T.
         */
        public boolean isAtLeastT() {
            return SdkLevel.isAtLeastT();
        }

        /** Get downstream6 BPF map. */
        @Nullable public BpfBitmap getBlockPortsMap() {
            if (!isAtLeastT()) return null;
            try {
                return new BpfBitmap(BLOCKED_PORTS_MAP_PATH);
            } catch (ErrnoException e) {
                Log.e(TAG, "Cannot create blocked ports map: " + e);
                Log.e(TAG, "Path: " + BLOCKED_PORTS_MAP_PATH);
                return null;
            }
        }

        // TODO: what other methods should be added for testing?
    }

    public VendorConnectivityService() {
        this(new Dependencies());

        //int binderUid = Binder.getCallingUid();
        // check for MAINLINE_NETWORK_STACK permission

        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Only the system may call VendorConnectivityService");
        }

        // final boolean hasPermission = getContext().checkPermission(
        //         android.Manifest.permission.MAINLINE_NETWORK_STACK, Binder.getCallingPid(),
        //         callingUid) == PackageManager.PERMISSION_GRANTED;
    }

    @VisibleForTesting
    protected VendorConnectivityService(@NonNull Dependencies deps) {
        mIsProgramAttached = false;
        mBpfBlockedPortsMap = deps.getBlockPortsMap();
    }

    @Override
    // TODO: Should the error be thrown from here as well?
    public void blockPort(int port) {
        if (!mIsProgramAttached) {
            if (!attachProgram()) {
                Log.e(TAG, "Unable to attach program");
                return;
            }
            mIsProgramAttached = true;
        }

        try {
            mBpfBlockedPortsMap.set(port);
            Log.e(TAG, "Blocking port: " + port);
        } catch (ErrnoException e) {
            Log.e(TAG, "Could not set bitmap vale for (port: " + port + ")" + e);
        } catch (IllegalStateException e) {
            // Silent if the rule already exists. Note that the errno EEXIST was rethrown as
            // IllegalStateException. See BpfMap#insertEntry.
        }
    }

    @Override
    // TODO: Should the error be thrown from here as well?
    public void unblockPort(int port) {
        try {
            mBpfBlockedPortsMap.unset(port);
        } catch (ErrnoException e) {
            Log.e(TAG, "Could not unset bitmap vale for (port: " + port + ")" + e);
        }

        try {
            if (mBpfBlockedPortsMap.isEmpty()) {
                // No ports are blocking, detach program until a blocked port is added.
                detachProgram();
            }
        } catch (ErrnoException e) {
            Log.e(TAG, "Unable to check if map is empty" + e);
        }
    }

    @Override
    // TODO: Should the error be thrown from here as well?
    public void unblockAllPorts() {
        try {
            mBpfBlockedPortsMap.clear();
        } catch (ErrnoException e) {
            Log.e(TAG, "Could not clear map" + e);
        }

        // No ports are blocking, detach program until a blocked port is added.
        detachProgram();
    }

    @Override
    // TODO: Throw error if map is empty?
    public int[] getBlockedPorts() {
        // TODO: is there a correct way of using this with BpfBitmap since we don't track set/unset
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
    private boolean attachProgram() {
        try {
            BpfUtils.attachProgram(V4_PROG_PATH, BPF_CGROUP_INET4_BIND);
        } catch (IOException e) {
            Log.e(TAG, "Unable to attach to BPF_CGROUP_INET4_BIND: " + e);
            return false;
        }
        try {
            BpfUtils.attachProgram(V6_PROG_PATH, BPF_CGROUP_INET6_BIND);
        } catch (IOException e) {
            Log.e(TAG, "Unable to attach to BPF_CGROUP_INET6_BIND: " + e);

            // Detach BPF_CGROUP_INET4_BIND
            try {
                BpfUtils.detachProgram(BPF_CGROUP_INET4_BIND);
            } catch (IOException ee) {
                Log.e(TAG, "Unable to attach to BPF_CGROUP_INET4_BIND: " + e);
            }
            return false;
        }

        Log.e(TAG, "ATTACHED");
        return true;
    }

    /**
     * Detach BPF program
     */
    private void detachProgram() {
        // Currently unused
        try {
            BpfUtils.detachProgram(BPF_CGROUP_INET4_BIND);
        } catch (IOException e) {
            Log.e(TAG, "Unable to detach to BPF_CGROUP_INET4_BIND: " + e);
        }
        try {
            BpfUtils.detachProgram(BPF_CGROUP_INET6_BIND);
        } catch (IOException e) {
            Log.e(TAG, "Unable to detach to BPF_CGROUP_INET6_BIND: " + e);
        }

        mIsProgramAttached = false;
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
