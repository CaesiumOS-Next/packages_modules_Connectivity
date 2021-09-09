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

import static android.net.DscpPolicy.DSCP_POLICY_STATUS_SUCCESS;
import static android.net.DscpPolicy.DSCP_POLICY_STATUS_REQUEST_DECLINED;
import static android.net.DscpPolicy.DSCP_POLICY_STATUS_REQUESTED_CLASSIFIER_NOT_SUPPORTED;
import static android.net.DscpPolicy.DSCP_POLICY_STATUS_INSUFFICIENT_PROCESSING_RESOURCES;
import static com.android.net.module.util.BpfUtils.BPF_CGROUP_INET_EGRESS;
//import static com.android.networkstack.tethering.util.TetheringUtils.getTetheringJniLibraryName;

import android.annotation.Nullable;
import android.net.DscpPolicy;
import android.os.RemoteException;
import android.system.ErrnoException;
import android.util.Log;

import com.android.net.module.util.BpfMap;
import com.android.net.module.util.BpfUtils;
import com.android.net.module.util.Struct;
import com.android.server.connectivity.NetworkAgentInfo;

import java.net.InetAddress;
import java.io.IOException;
import java.util.ArrayList;

//import com.android.net.module.util.JniUtil;

public class DscpPolicyTracker {
    static {
        //System.loadLibrary(JniUtil.getJniLibraryName(TetheringUtils.class.getPackage()));
        //System.loadLibrary(getTetheringJniLibraryName());
        System.loadLibrary("com_android_connectivity_com_android_net_module_util_jni");
    }

    private static final String TAG = DscpPolicyTracker.class.getSimpleName();
    // TODO: Paths need to be moved out of Tethering, but that is the default location
    // for BPF connectivity programs right now.
    private static final String PROG_PATH =
            "/sys/fs/bpf/tethering/prog_dscp_policy_cgroupskb_add_dscp_bit";
    // Name is "map + *.o + map_name + map". Can probably shorten this
    private static final String IPV4_POLICY_MAP_PATH = makeMapPath(
            "dscp_policy_ipv4_dscp_policies");
    private static final String IPV6_POLICY_MAP_PATH = makeMapPath(
            "dscp_policy_ipv6_dscp_policies");
    private static final int MAX_POLICIES = 16;

    private static String makeMapPath(String which) {
        return "/sys/fs/bpf/tethering/map_" + which + "_map";
    }

    private boolean mIsProgramAttached;

    // BPF map for downstream IPv4 forwarding.
    //@Nullable
    private BpfMap<Struct.U32, DscpPolicyValue> mBpfDscpIpv4Policies;
    private BpfMap<Struct.U32, DscpPolicyValue> mBpfDscpIpv6Policies;

    private final ArrayList<Integer> mPolicyIdToBpfMapIndex;

    public DscpPolicyTracker()
            throws ErrnoException {
        mIsProgramAttached = false;

        mPolicyIdToBpfMapIndex = new ArrayList<Integer>(16);
        mBpfDscpIpv4Policies = new BpfMap<Struct.U32, DscpPolicyValue>(IPV4_POLICY_MAP_PATH,
                BpfMap.BPF_F_RDWR, Struct.U32.class, DscpPolicyValue.class);
        mBpfDscpIpv6Policies = new BpfMap<Struct.U32, DscpPolicyValue>(IPV6_POLICY_MAP_PATH,
                BpfMap.BPF_F_RDWR, Struct.U32.class, DscpPolicyValue.class);
    }

    public void addDscpPolicy(DscpPolicy policy, NetworkAgentInfo nai) {
        int status = DSCP_POLICY_STATUS_SUCCESS;
        if (!mIsProgramAttached) {
            if (!attachProgram()) {
                Log.e(TAG, "Unable to attach program");
                return;
            }
            mIsProgramAttached = true;
        }

        if (mPolicyIdToBpfMapIndex.size() >= MAX_POLICIES) {
            status = DSCP_POLICY_STATUS_INSUFFICIENT_PROCESSING_RESOURCES;
        } else {
            // Currently all classifiers are supported, if any are removed return
            // DSCP_POLICY_STATUS_REQUESTED_CLASSIFIER_NOT_SUPPORTED,
            // and for any other generic error DSCP_POLICY_STATUS_REQUEST_DECLINED

            try {
                // Update map
                // TODO: Only update IPv4 map for testing purposes for now
                mBpfDscpIpv4Policies.insertOrReplaceEntry(
                        new Struct.U32(mPolicyIdToBpfMapIndex.size()),
                        new DscpPolicyValue(toIpv4MappedAddressBytes(policy.getSrcAddr()),
                        toIpv4MappedAddressBytes(policy.getDstAddr()),
                        policy.getSrcPort(), policy.getDstPortRangeStart(),
                        policy.getDstPortRangeEnd(), (short) policy.getProtocol(),
                        (short) policy.getDscp()));
                mPolicyIdToBpfMapIndex.add(policy.getPolicyId());
            } catch (ErrnoException e) {
                Log.e(TAG, "Failed to insert policies into map: ", e);
                status = DSCP_POLICY_STATUS_INSUFFICIENT_PROCESSING_RESOURCES;
            }
        }

        // DSCP policy ID's are 1-255. For now it is fine to use the map key + 1
        // as the policy ID.

        try {
            // Since nai is accessible here it might be best to update status here
            nai.networkAgent.onDscpPolicyStatusUpdated(policy.getPolicyId(), status);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed update policy status: ", e);
        }
    }

    // Pulled from BpfCoordinator
    private byte[] toIpv4MappedAddressBytes(InetAddress ia) {
        final byte[] addr6 = new byte[16];
        if (ia != null) {
            final byte[] addr4 = ia.getAddress();
            addr6[10] = (byte) 0xff;
            addr6[11] = (byte) 0xff;
            addr6[12] = addr4[0];
            addr6[13] = addr4[1];
            addr6[14] = addr4[2];
            addr6[15] = addr4[3];
        }
        return addr6;
    }

    public void removeDscpPolicy(int policyId) {
        if (!mIsProgramAttached) {
            // Nothing to remove since program is not attached.
            return;
        }

        // Key is policyId - 1, since range is 1-255.
        int index = mPolicyIdToBpfMapIndex.indexOf(policyId);
        if (index > -1) {
            Struct.U32 key = new Struct.U32(index);
            try {
                boolean ret = mBpfDscpIpv4Policies.deleteEntry(key);
            } catch (ErrnoException e) {
                Log.e(TAG, "Failed to delete policies from map: ", e);
            }

            mPolicyIdToBpfMapIndex.remove(index);
        }


        if (mPolicyIdToBpfMapIndex.isEmpty()) {
            detachProgram();
        }
    }

    public void removeAllDscpPolicies() {
        if (!mIsProgramAttached) {
            // Nothing to remove since program is not attached.
            return;
        }

        try {
            mBpfDscpIpv4Policies.clear();
        } catch (ErrnoException e) {
            Log.e(TAG, "Failed to insert policies into map: ", e);
        }
        // Can detach program since no policies are active.
        detachProgram();
    }

    /**
     * Attach BPF program
     */
    private boolean attachProgram() {
        try {
            BpfUtils.attachProgram(PROG_PATH, BPF_CGROUP_INET_EGRESS);
        } catch (IOException e) { //| ErrnoException
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
            BpfUtils.detachProgram(BPF_CGROUP_INET_EGRESS);
        } catch (IOException e) {
            Log.e(TAG, "Unabled to detach to BPF_CGROUP_INET_EGRESS: " + e);
        }
    }
}
