/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.networkstack.tethering.apishim.api30;

import android.net.INetd;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.networkstack.tethering.BpfCoordinator.Dependencies;
import com.android.networkstack.tethering.BpfCoordinator.Ipv6ForwardingRule;

/**
 * Bpf coordinator class for API shims.
 */
public class BpfCoordinatorShimImpl
        extends com.android.networkstack.tethering.apishim.common.BpfCoordinatorShim {
    private static final String TAG = "api30.BpfCoordinatorShimImpl";

    private final INetd mNetd;

    public BpfCoordinatorShimImpl(@NonNull final Dependencies deps) {
        mNetd = deps.getNetd();
    }

    @Override
    public boolean isInitialized() {
        return mNetd != null;
    };

    @Override
    public boolean tetherOffloadRuleAdd(@NonNull final Ipv6ForwardingRule rule) {
        if (!isInitialized()) return false;

        try {
            mNetd.tetherOffloadRuleAdd(rule.toTetherOffloadRuleParcel());
        } catch (RemoteException | ServiceSpecificException e) {
            Log.e(TAG, "Could not add IPv6 forwarding rule: ", e);
            return false;
        }

        return true;
    };

    @Override
    public String toString() {
        return "mNetd{"
                + (mNetd != null ? "initialized" : "not initialized") + "} "
                + "}";
    }
}
