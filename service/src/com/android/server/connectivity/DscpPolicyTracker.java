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

import static android.net.DscpPolicy.DSCP_POLICY_STATUS_DELETED;
import static android.net.DscpPolicy.DSCP_POLICY_STATUS_INSUFFICIENT_PROCESSING_RESOURCES;
import static android.net.DscpPolicy.DSCP_POLICY_STATUS_REQUEST_DECLINED;
import static android.net.DscpPolicy.DSCP_POLICY_STATUS_REQUESTED_CLASSIFIER_NOT_SUPPORTED;
import static android.net.DscpPolicy.DSCP_POLICY_STATUS_SUCCESS;
import static android.system.OsConstants.ETH_P_IP;
import static android.system.OsConstants.ETH_P_IPV6;

import android.annotation.NonNull;
import android.net.DscpPolicy;
import android.os.RemoteException;
import android.system.ErrnoException;
import android.util.Log;
import android.util.SparseIntArray;

import com.android.net.module.util.BpfMap;
import com.android.net.module.util.Struct;
import com.android.net.module.util.TcUtils;
import com.android.server.connectivity.NetworkAgentInfo;

import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * DscpPolicyTracker has a single entry point from ConnectivityService handler.
 * This guarantees that all requests are thread safe.
 */
public class DscpPolicyTracker {
    static {
        System.loadLibrary("com_android_connectivity_com_android_net_module_util_jni");
    }

    // After tethering and clat priorities.
    static final short PRIO_DSCP = 5;

    private static final String TAG = DscpPolicyTracker.class.getSimpleName();
    private static final String PROG_PATH =
            "/sys/fs/bpf/prog_dscp_policy_schedcls_add_dscp_bit";
    // Name is "map + *.o + map_name + map". Can probably shorten this
    private static final String IPV4_POLICY_MAP_PATH = makeMapPath(
            "dscp_policy_ipv4_dscp_policies");
    private static final String IPV6_POLICY_MAP_PATH = makeMapPath(
            "dscp_policy_ipv6_dscp_policies");
    private static final int MAX_POLICIES = 16;

    private static String makeMapPath(String which) {
        return "/sys/fs/bpf/map_" + which + "_map";
    }

    private Set<String> mAttachedIfaces;

    private BpfMap<Struct.U32, DscpPolicyValue> mBpfDscpIpv4Policies;
    private BpfMap<Struct.U32, DscpPolicyValue> mBpfDscpIpv6Policies;
    private SparseIntArray mPolicyIdToBpfMapIndex;

    public DscpPolicyTracker() {
        mAttachedIfaces = new HashSet<String>();
    }

    private int getFirstFreeIndex() {
        for (int i=0; i < MAX_POLICIES; i++) {
            if (mPolicyIdToBpfMapIndex.indexOfValue(i) < 0)
                return i;
        }
        return MAX_POLICIES;
    }

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

    /**
     * Add the provided DSCP policy to the bpf map. Attach bpf program dscp_policy to iface
     * if not already attached. Response will be sent back to nai with status.
     *
     * DSCP_POLICY_STATUS_SUCCESS - if policy was added successfully
     * DSCP_POLICY_STATUS_INSUFFICIENT_PROCESSING_RESOURCES - if max policies were already set
     */
    public void addDscpPolicy(NetworkAgentInfo nai, DscpPolicy policy) {
        int status = DSCP_POLICY_STATUS_SUCCESS;

        if (!mAttachedIfaces.contains(nai.linkProperties.getInterfaceName())) {
            if (!attachProgram(nai.linkProperties.getInterfaceName())) {
                Log.e(TAG, "Unable to attach program");
                return;
            }
        }

        // If there is no existing policy with a matching ID, and we are already at
        // the maximum number of policies then return INSUFFICIENT_PROCESSING_RESOURCES.
        if (mPolicyIdToBpfMapIndex.get(policy.getPolicyId(), -1) == -1 &&
                mPolicyIdToBpfMapIndex.size() >= MAX_POLICIES) {
            status = DSCP_POLICY_STATUS_INSUFFICIENT_PROCESSING_RESOURCES;
        } else {
            // Currently all classifiers are supported, if any are removed return
            // DSCP_POLICY_STATUS_REQUESTED_CLASSIFIER_NOT_SUPPORTED,
            // and for any other generic error DSCP_POLICY_STATUS_REQUEST_DECLINED

            int addIndex = 0;
            // If a policy with a matching ID exists, replace it, otherwise use the next free
            // index for the policy.
            if (mPolicyIdToBpfMapIndex.get(policy.getPolicyId(), -1) != -1)
                addIndex = mPolicyIdToBpfMapIndex.get(policy.getPolicyId());
            else
                addIndex = getFirstFreeIndex();

            try {
                mPolicyIdToBpfMapIndex.put(policy.getPolicyId(), addIndex);

                if ((policy.getDstAddr() == null || policy.getDstAddr() instanceof Inet4Address)
                        && (policy.getSrcAddr() == null ||
                        policy.getSrcAddr() instanceof Inet4Address)) {
                    mBpfDscpIpv4Policies.insertOrReplaceEntry(
                        new Struct.U32(addIndex),
                        new DscpPolicyValue(toIpv4MappedAddressBytes(policy.getSrcAddr()),
                            toIpv4MappedAddressBytes(policy.getDstAddr()),
                            policy.getSrcPort(), policy.getDstPortRange(),
                            (short) policy.getProtocol(), (short) policy.getDscpValue()));
                }

                if ((policy.getDstAddr() == null || policy.getDstAddr() instanceof Inet6Address)
                        && (policy.getSrcAddr() == null ||
                        policy.getSrcAddr() instanceof Inet6Address)) {
                    mBpfDscpIpv6Policies.insertOrReplaceEntry(
                        new Struct.U32(addIndex),
                        new DscpPolicyValue(policy.getSrcAddr() != null ?
                                policy.getSrcAddr().getAddress() : new byte[16],
                            policy.getDstAddr() != null ?
                                policy.getDstAddr().getAddress() : new byte[16],
                            policy.getSrcPort(), policy.getDstPortRange(),
                            (short) policy.getProtocol(), (short) policy.getDscpValue()));
                }
            } catch (ErrnoException e) {
                Log.e(TAG, "Failed to insert policies into map: ", e);
                status = DSCP_POLICY_STATUS_INSUFFICIENT_PROCESSING_RESOURCES;
            }
        }

        try {
            // Since nai is accessible here it might be best to update status here
            nai.networkAgent.onDscpPolicyStatusUpdated(policy.getPolicyId(), status);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed update policy status: ", e);
        }
    }

    private void removePolicyFromMap(NetworkAgentInfo nai, int policyId, int index) {
        try {
            mBpfDscpIpv4Policies.replaceEntry(new Struct.U32(index), new DscpPolicyValue());
            mBpfDscpIpv6Policies.replaceEntry(new Struct.U32(index), new DscpPolicyValue());
        } catch (ErrnoException e) {
            Log.e(TAG, "Failed to delete policy from map: ", e);
        }

        try {
            nai.networkAgent.onDscpPolicyStatusUpdated(policyId, DSCP_POLICY_STATUS_DELETED);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed update policy status: ", e);
        }
    }

    /**
     * Remove specified DSCP policy and detach program if no other policies are active.
     */
    public void removeDscpPolicy(NetworkAgentInfo nai, int policyId) {
        if (!mAttachedIfaces.contains(nai.linkProperties.getInterfaceName())) {
            // Nothing to remove since program is not attached.
            return;
        }

        if (mPolicyIdToBpfMapIndex.get(policyId, -1) != -1) {
            removePolicyFromMap(nai, policyId, mPolicyIdToBpfMapIndex.get(policyId));
            mPolicyIdToBpfMapIndex.delete(policyId);
        }

        // TODO: detach should only occur if no more policies are present on the nai's iface.
        if (mPolicyIdToBpfMapIndex.size() == 0) {
            detachProgram(nai.linkProperties.getInterfaceName());
        }
    }

    /**
     * Remove all DSCP policies and detach program.
     */
    // TODO: Remove all should only remove policies from corresponding nai iface.
    public void removeAllDscpPolicies(NetworkAgentInfo nai) {
        if (!mAttachedIfaces.contains(nai.linkProperties.getInterfaceName())) {
            // Nothing to remove since program is not attached.
            return;
        }

        for (int i = 0; i < mPolicyIdToBpfMapIndex.size(); i++) {
            removePolicyFromMap(nai, mPolicyIdToBpfMapIndex.keyAt(i),
                    mPolicyIdToBpfMapIndex.valueAt(i));
        }
        mPolicyIdToBpfMapIndex.clear();

        // Can detach program since no policies are active.
        detachProgram(nai.linkProperties.getInterfaceName());
    }

    /**
     * Attach BPF program
     */
    private boolean attachProgram(@NonNull String iface) {
        // TODO: attach needs to be per iface not program.

        try {
            NetworkInterface netIface = NetworkInterface.getByName(iface);
            TcUtils.tcFilterAddDevBpf(netIface.getIndex(), false, PRIO_DSCP, (short) ETH_P_IP,
                    PROG_PATH);

            mPolicyIdToBpfMapIndex = new SparseIntArray(MAX_POLICIES);
            mBpfDscpIpv4Policies = new BpfMap<Struct.U32, DscpPolicyValue>(IPV4_POLICY_MAP_PATH,
                    BpfMap.BPF_F_RDWR, Struct.U32.class, DscpPolicyValue.class);
            mBpfDscpIpv6Policies = new BpfMap<Struct.U32, DscpPolicyValue>(IPV6_POLICY_MAP_PATH,
                    BpfMap.BPF_F_RDWR, Struct.U32.class, DscpPolicyValue.class);
        } catch (IOException | ErrnoException e) {
            Log.e(TAG, "Unabled to attach to TC on " + iface + ": " + e);
            return false;
        }
        mAttachedIfaces.add(iface);
        return true;
    }

    /**
     * Detach BPF program
     */
    public void detachProgram(@NonNull String iface) {
        try {
            NetworkInterface netIface = NetworkInterface.getByName(iface);
            TcUtils.tcFilterDelDev(netIface.getIndex(), false, PRIO_DSCP, (short) ETH_P_IP);
        } catch (IOException e) {
            Log.e(TAG, "Unabled to detach to TC on " + iface + ": " + e);
        }
        mAttachedIfaces.remove(iface);
    }
}
