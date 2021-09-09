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

import android.annotation.Nullable;
import android.net.DscpPolicy;

import com.android.net.module.util.BpfMap;

public class DscpPolicyTracker {
    private static final String TAG = VendorConnectivityService.class.getSimpleName();
    // TODO: Update based on bpf filename
    private static final String PROG_PATH = "/sys/fs/bpf/prog_dscp_policy_cgroupskb_add_dscp_bit";
    private static final String BLOCKED_PORTS_MAP_PATH = makeMapPath("add_dscp_bit");

    private static String makeMapPath(String which) {
        return "/sys/fs/bpf/connectivity/map_connectivity_" + which + "_map";
    }

    private boolean mIsProgramAttached;

    // BPF map for downstream IPv4 forwarding.
    //@Nullable
    //private final BpfMap<DscpPolicyKey, DscpPolicyValue> mBpfDscpIpv4Policies;
    //private final BpfMap<DscpPolicyKey, DscpPolicyValue> mBpfDscpIpv6Policies;

    public DscpPolicyTracker() {
        mIsProgramAttached = false;
    }

    public void addDscpPolicy(DscpPolicy policy) {
        if (!mIsProgramAttached) {
            if (!attachProgram()) {
                Log.e(TAG, "Unabled to attach program");
                return;
            }
            mIsProgramAttached = true;
        }

        // Update map
    }

    public void removeDscpPolicy(DscpPolicy policy) {

    }

    public void removeAllDscpPolicies() {

    }

    /**
     * Attach BPF program
     */
    private boolean attachProgram() {
        try {
            BpfUtils.attachProgram(PROG_PATH, BpfAttachType.BPF_CGROUP_INET_EGRESS);
        } catch (IOException e) {
            Log.e(TAG, "Unabled to attach to BPF_CGROUP_INET_EGRESS: " + e);
            return false;
        }
        return true;
    }

    /**
     * Detach BPF program
     */
    public void detachProgram() {
        // Currently unused
        try {
            BpfUtils.detachProgram(BpfAttachType.BPF_CGROUP_INET_EGRESS);
        } catch (IOException e) {
            Log.e(TAG, "Unabled to detach to BPF_CGROUP_INET_EGRESS: " + e);
        }
    }
}
