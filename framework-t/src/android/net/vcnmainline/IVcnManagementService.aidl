/*
 * Copyright 2020, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net.vcnmainline;

import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.net.vcnmainline.IVcnStatusCallback;
import android.net.vcnmainline.IVcnUnderlyingNetworkPolicyListener;
import android.net.vcnmainline.VcnConfig;
import android.net.vcnmainline.VcnUnderlyingNetworkPolicy;
import android.os.ParcelUuid;

import java.util.List;

/**
 * @hide
 */
interface IVcnManagementService {
    void setVcnConfig(in ParcelUuid subscriptionGroup, in VcnConfig config, in String opPkgName);
    void clearVcnConfig(in ParcelUuid subscriptionGroup, in String opPkgName);
    List<ParcelUuid> getConfiguredSubscriptionGroups(in String opPkgName);

    void addVcnUnderlyingNetworkPolicyListener(in IVcnUnderlyingNetworkPolicyListener listener);
    void removeVcnUnderlyingNetworkPolicyListener(in IVcnUnderlyingNetworkPolicyListener listener);
    VcnUnderlyingNetworkPolicy getUnderlyingNetworkPolicy(in NetworkCapabilities nc, in LinkProperties lp);

    void registerVcnStatusCallback(in ParcelUuid subscriptionGroup, in IVcnStatusCallback callback, in String opPkgName);
    void unregisterVcnStatusCallback(in IVcnStatusCallback callback);
}
