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

import android.util.Log;

class NetworkRequestStateInfo {

    private static final String TAG = "NetworkRequestStateInfo";
    final int mPackageUid;
    final int mNetworkRequestId;
    final int mTransportTypes;
    final boolean mNetCapabilityNotMetered;
    final boolean mNetCapabilityInternet;
    final int mNetworkRequestReceivedTime;
    int mNetworkRequestDurationMillis;

    NetworkRequestStateInfo(Builder builder) {
        mPackageUid = builder.mPackageUid;
        mNetworkRequestId = builder.mNetworkRequestId;
        mTransportTypes = builder.mTransportTypes;
        mNetCapabilityNotMetered = builder.mNetCapabilityNotMetered;
        mNetCapabilityInternet = builder.mNetCapabilityInternet;
        mNetworkRequestReceivedTime = builder.mNetworkRequestReceivedTime;
        mNetworkRequestDurationMillis = builder.mNetworkRequestDurationMillis;
    }

    public void updateNetworkRequestDuration(int newNetworkRequestDurationMillis) {
        if (newNetworkRequestDurationMillis < mNetworkRequestDurationMillis) {
            Log.e(TAG, "Network request (id = " + mNetworkRequestId
                    + ") duration is requested to be shortened");
        } else if (newNetworkRequestDurationMillis == mNetworkRequestDurationMillis) {
            Log.w(TAG, "Network request (id = " + mNetworkRequestId
                    + ") duration is requested to be updated for the same value ("
                    + mNetworkRequestDurationMillis);
        } else {
            Log.i(TAG, "Network request (id = " + mNetworkRequestId
                    + ") duration is updated from " + mNetworkRequestDurationMillis + " to "
                    + newNetworkRequestDurationMillis);
            mNetworkRequestDurationMillis = newNetworkRequestDurationMillis;
        }
    }

    public static class Builder {

        private int mPackageUid;
        private int mNetworkRequestId;
        private int mTransportTypes;
        private boolean mNetCapabilityNotMetered;
        private boolean mNetCapabilityInternet;
        private int mNetworkRequestReceivedTime;
        private int mNetworkRequestDurationMillis;

        Builder() {
        }

        public Builder setPackageUid(int packageUid) {
            this.mPackageUid = packageUid;
            return this;
        }

        public Builder setNetworkRequestId(int networkRequestId) {
            this.mNetworkRequestId = networkRequestId;
            return this;
        }

        public Builder setTransportTypes(int transportTypes) {
            this.mTransportTypes = transportTypes;
            return this;
        }

        public Builder setNetCapabilityNotMetered(boolean netCapabilityNotMetered) {
            this.mNetCapabilityNotMetered = netCapabilityNotMetered;
            return this;
        }

        public Builder setNetCapabilityInternet(boolean netCapabilityInternet) {
            this.mNetCapabilityInternet = netCapabilityInternet;
            return this;
        }

        public Builder setNetworkRequestReceivedTime(int networkRequestReceivedTime) {
            this.mNetworkRequestReceivedTime = networkRequestReceivedTime;
            return this;
        }

        public Builder setNetworkRequestDuration(int networkRequestDurationMillis) {
            this.mNetworkRequestDurationMillis = networkRequestDurationMillis;
            return this;
        }

        public NetworkRequestStateInfo build() {
            return new NetworkRequestStateInfo(this);
        }
    }
}
