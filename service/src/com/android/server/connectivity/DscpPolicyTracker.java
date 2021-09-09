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
import android.system.ErrnoException;
import android.util.Log;

import com.android.net.module.util.BpfMap;
import com.android.net.module.util.BpfUtils;
import com.android.net.module.util.BpfUtils.BpfAttachType;
import com.android.net.module.util.U32Struct;

import java.net.InetAddress;
import java.io.IOException;

public class DscpPolicyTracker {
    private static final String TAG = DscpPolicyTracker.class.getSimpleName();
    // TODO: Paths need to be moved out of Tethering, but that is the default location
    // for BPF connectivity programs right now.
    private static final String PROG_PATH =
            "/sys/fs/bpf/tethering/prog_dscp_policy_cgroupskb_add_dscp_bit";
    // Name is "map + *.o + map_name + map". Can probably shorten this
    private static final String IPV4_POLICY_MAP_PATH = makeMapPath("dscp_policy_ipv4_dscp_policies");
    private static final String IPV6_POLICY_MAP_PATH = makeMapPath("dscp_policyipv6_dscp_policies");

    private static String makeMapPath(String which) {
        return "/sys/fs/bpf/tethering/map_" + which + "_map";
    }

    private boolean mIsProgramAttached;
    private int numPolicies;

    // BPF map for downstream IPv4 forwarding.
    //@Nullable
    private final BpfMap<U32Struct, DscpPolicyValue> mBpfDscpIpv4Policies;
    private final BpfMap<U32Struct, DscpPolicyValue> mBpfDscpIpv6Policies;

    public DscpPolicyTracker() throws ErrnoException {
        mIsProgramAttached = false;
        numPolicies = 0;

        mBpfDscpIpv4Policies = new BpfMap<U32Struct, DscpPolicyValue>(IPV4_POLICY_MAP_PATH,
                BpfMap.BPF_F_RDWR, U32Struct.class, DscpPolicyValue.class);
        mBpfDscpIpv6Policies = new BpfMap<U32Struct, DscpPolicyValue>(IPV6_POLICY_MAP_PATH,
                BpfMap.BPF_F_RDWR, U32Struct.class, DscpPolicyValue.class);
    }

    public void addDscpPolicy(DscpPolicy policy) throws ErrnoException {
        if (!mIsProgramAttached) {
            if (!attachProgram()) {
                Log.e(TAG, "Unabled to attach program");
                return;
            }
            mIsProgramAttached = true;
        }

        // Update map
        // TODO: Only update IPv4 map for testing purposes for now
        mBpfDscpIpv4Policies.insertEntry(new U32Struct(numPolicies++),
                new DscpPolicyValue(toIpv4MappedAddressBytes(policy.getSrcAddr()),
                toIpv4MappedAddressBytes(policy.getDstAddr()),
                policy.getSrcPort(), policy.getDstPortRangeStart(),
                policy.getDstPortRangeEnd(), policy.getProtocol(),
                policy.getDscp()));
    }

    // Pulled from BpfCoordinator
    private byte[] toIpv4MappedAddressBytes(InetAddress ia) {
        final byte[] addr4 = ia.getAddress();
        final byte[] addr6 = new byte[16];
        addr6[10] = (byte) 0xff;
        addr6[11] = (byte) 0xff;
        addr6[12] = addr4[0];
        addr6[13] = addr4[1];
        addr6[14] = addr4[2];
        addr6[15] = addr4[3];
        return addr6;
    }

    public void removeDscpPolicy(DscpPolicy policy) {
        if (!mIsProgramAttached) {
            // Nothing to remove.
            return;
        }

        // Key is just an int position element, is there a clean way to delete these?
    }

    public void removeAllDscpPolicies() throws ErrnoException {
        if (!mIsProgramAttached) {
            // Nothing to remove.
            return;
        }

        mBpfDscpIpv4Policies.clear();
        // Can detach program since no policies are active.
        detachProgram();
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
