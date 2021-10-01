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
import android.content.Context;
import android.net.connectivity.aidl.ConnectivityNative;
import android.os.Binder;
import android.os.Process;
import android.os.ServiceSpecificException;
import android.system.ErrnoException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.BpfBitmap;
import com.android.net.module.util.BpfUtils;
import com.android.net.module.util.PermissionUtils;

import java.io.IOException;
import java.util.ArrayList;

/**
 * @hide
 */
public class ConnectivityNativeService extends ConnectivityNative.Stub {
    public static final String SERVICE_NAME = "connectivity_native";

    private static final String TAG = ConnectivityNativeService.class.getSimpleName();
    private static final String CGROUP_PATH = "/sys/fs/cgroup";
    private static final String V4_PROG_PATH =
            "/sys/fs/bpf/prog_block_bind4_block_port";
    private static final String V6_PROG_PATH =
            "/sys/fs/bpf/prog_block_bind6_block_port";
    private static final String BLOCKED_PORTS_MAP_PATH = "/sys/fs/bpf/map_block_blocked_ports_map";

    // BPF map for port blocking.
    @Nullable
    private final BpfBitmap mBpfBlockedPortsMap;

    public interface Errors {
         int UNSET_FAILED = 1;
         int CLEAR_FAILED = 1;
    }

    /**
     * Dependencies of ConnectivityNativeService, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        /** Get BPF maps. */
        @Nullable public BpfBitmap getBlockPortsMap() {
            try {
                return new BpfBitmap(BLOCKED_PORTS_MAP_PATH);
            } catch (ErrnoException e) {
                Log.e(TAG, "Cannot create blocked ports map: " + e);
                Log.e(TAG, "Path: " + BLOCKED_PORTS_MAP_PATH);
                return null;
            }
        }
    }

    private void checkBlockPortPermission() {
        final int uid = Binder.getCallingUid();
        if (uid == Process.ROOT_UID || uid == Process.PHONE_UID) return;
        //PermissionUtils.enforceNetworkStackPermission(getContext());
        // TODO: how to get context of AIDL caller?
    }

    public ConnectivityNativeService() {
        this(new Dependencies());
    }

    @VisibleForTesting
    protected ConnectivityNativeService(@NonNull Dependencies deps) {
        mBpfBlockedPortsMap = deps.getBlockPortsMap();
        if (!attachProgram()) {
            throw new UnsupportedOperationException("Unable to attach program");
        }
    }

    @Override
    // TODO: Should the error be thrown from here as well?
    public void blockBindOnPort(int port) {
        checkBlockPortPermission();
        try {
            mBpfBlockedPortsMap.set(port);
            Log.e(TAG, "Blocking port: " + port);
        } catch (ErrnoException e) {
            Log.e(TAG, "Could not set bitmap value for (port: " + port + ")" + e);
        } catch (IllegalStateException e) {
            // Silent if the rule already exists. Note that the errno EEXIST was rethrown as
            // IllegalStateException. See BpfMap#insertEntry.
        }
    }

    @Override
    // TODO: Should the error be thrown from here as well?
    public void unblockBindOnPort(int port) {
        checkBlockPortPermission();
        try {
            mBpfBlockedPortsMap.unset(port);
        } catch (ErrnoException e) {
            throw new ServiceSpecificException(Errors.UNSET_FAILED,
                    "Could not unset bitmap value for (port: " + port + ")" + e);
        }
    }

    @Override
    // TODO: Should the error be thrown from here as well?
    public void unblockAllPortsForBind() {
        checkBlockPortPermission();
        try {
            mBpfBlockedPortsMap.clear();
        } catch (ErrnoException e) {
            throw new ServiceSpecificException(Errors.CLEAR_FAILED, "Could not clear map" + e);
        }
    }

    @Override
    public int[] getPortsBlockedForBind() {
        checkBlockPortPermission();

        ArrayList<Integer> portMap = new ArrayList<Integer>();
        for (int i = 0; i <= 65535; i++) {
            try {
                if (mBpfBlockedPortsMap.get(i)) portMap.add(i);
            } catch (ErrnoException e) {
                Log.e(TAG, "Failed to get index " + i, e);
            }
        }
        return portMap.stream().mapToInt(i -> i).toArray();
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
            BpfUtils.attachProgram(BPF_CGROUP_INET4_BIND, V4_PROG_PATH, CGROUP_PATH, 0);
        } catch (IOException e) {
            Log.e(TAG, "Unable to attach to BPF_CGROUP_INET4_BIND: " + e);
            return false;
        }
        try {
            BpfUtils.attachProgram(BPF_CGROUP_INET6_BIND, V6_PROG_PATH, CGROUP_PATH, 0);
        } catch (IOException e) {
            Log.e(TAG, "Unable to attach to BPF_CGROUP_INET6_BIND: " + e);

            // Detach BPF_CGROUP_INET4_BIND
            try {
                BpfUtils.detachProgram(BPF_CGROUP_INET4_BIND, CGROUP_PATH);
            } catch (IOException ee) {
                Log.e(TAG, "Unable to attach to BPF_CGROUP_INET4_BIND: " + e);
            }
            return false;
        }

        Log.e(TAG, "Attached BPF_CGROUP_INET4_BIND and BPF_CGROUP_INET6_BIND programs");
        return true;
    }

    /**
     * Detach BPF program
     */
    private void detachProgram() {
        try {
            BpfUtils.detachProgram(BPF_CGROUP_INET4_BIND, CGROUP_PATH);
        } catch (IOException e) {
            Log.e(TAG, "Unable to detach to BPF_CGROUP_INET4_BIND: " + e);
        }
        try {
            BpfUtils.detachProgram(BPF_CGROUP_INET6_BIND, CGROUP_PATH);
        } catch (IOException e) {
            Log.e(TAG, "Unable to detach to BPF_CGROUP_INET6_BIND: " + e);
        }
    }
}
