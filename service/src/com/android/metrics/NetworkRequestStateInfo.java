/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.metrics;

import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

class NetworkRequestStateInfo {
    final int mPackageUid;
    final NetworkRequest mNetworkRequest;
    final long mNetworkRequestReceivedTime;
    int mNetworkRequestDurationMillis;
    private NetworkRequestStateStatsMetrics.Dependencies mDependencies;

    NetworkRequestStateInfo(int packageUid, NetworkRequest networkRequest) {
        this(packageUid, networkRequest, new NetworkRequestStateStatsMetrics.Dependencies());
    }
    NetworkRequestStateInfo(int packageUid, NetworkRequest networkRequest,
            NetworkRequestStateStatsMetrics.Dependencies deps) {
        mDependencies = deps;
        mPackageUid = packageUid;
        mNetworkRequest = networkRequest;
        mNetworkRequestReceivedTime = mDependencies.getElapsedRealtime();
        mNetworkRequestDurationMillis = 0;
    }

    public void captureNetworkRequestCurrentDuration() {
        mNetworkRequestDurationMillis = (int) (
                mDependencies.getElapsedRealtime() - mNetworkRequestReceivedTime);
    }

    public int getRequestId() {
        return mNetworkRequest.requestId;
    }

    public int getTransportTypes() {
        return (int) mNetworkRequest.networkCapabilities.getTransportTypesInternal();
    }

    public boolean getNetCapabilityNotMetered() {
        return mNetworkRequest.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }

    public boolean getNetCapabilityInternet() {
        return mNetworkRequest.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
}
