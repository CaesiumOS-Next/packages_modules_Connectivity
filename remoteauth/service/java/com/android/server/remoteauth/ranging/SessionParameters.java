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
package com.android.server.remote_auth.ranging;

import android.annotation.NonNull;

import com.android.server.remote_auth.ranging.RangingCapabilities.RangingMethod;

/** The set of parameters to create a ranging session. */
public class SessionParameters {

    private String mDeviceId;
    private RangingMethod mRangingMethod;
    private float mLowerProximityBoundaryM;
    private float mUpperProximityBoundaryM;
    private boolean mAutoGenerateParams;
    private byte[] mBaseKey;
    private byte[] mSyncData;

    public String getDeviceId() {
        return mDeviceId;
    }

    public RangingMethod getRangingMethod() {
        return mRangingMethod;
    }

    public float getLowerProximityBoundaryM() {
        return mLowerProximityBoundaryM;
    }

    public float getUpperProximityBoundaryM() {
        return mUpperProximityBoundaryM;
    }

    public boolean getAutoGenerateParams() {
        return mAutoGenerateParams;
    }

    public byte[] getBaseKey() {
        return mBaseKey;
    }

    public byte[] getSyncData() {
        return mSyncData;
    }

    private SessionParameters(
            String deviceId,
            RangingMethod rangingMethod,
            float lowerProximityBoundaryM,
            float upperProximityBoundaryM,
            boolean autoGenerateParams,
            byte[] baseKey,
            byte[] syncData) {
        mDeviceId = deviceId;
        mRangingMethod = rangingMethod;
        mLowerProximityBoundaryM = lowerProximityBoundaryM;
        mUpperProximityBoundaryM = upperProximityBoundaryM;
        mAutoGenerateParams = autoGenerateParams;
        mBaseKey = baseKey;
        mSyncData = syncData;
    }

    /** Builder class for {@link SessionParameters}. */
    public static final class Builder {
        private String mDeviceId = new String("");
        private RangingMethod mRangingMethod = RangingMethod.UNKNOWN;
        private float mLowerProximityBoundaryM;
        private float mUpperProximityBoundaryM;
        private boolean mAutoGenerateParams = false;
        private byte[] mBaseKey = new byte[] {};
        private byte[] mSyncData = new byte[] {};

        /** Set the device id. */
        public Builder setDeviceId(@NonNull String deviceId) {
            mDeviceId = deviceId;
            return this;
        }

        /** Set the ranging method. */
        public Builder setRangingMethod(RangingMethod rangingMethod) {
            mRangingMethod = rangingMethod;
            return this;
        }

        /** Set the lower proximity boundary in meters. */
        public Builder setLowerProximityBoundaryM(float lowerProximityBoundaryM) {
            mLowerProximityBoundaryM = lowerProximityBoundaryM;
            return this;
        }

        /** Set the upper proximity boundary in meters. */
        public Builder setUpperProximityBoundaryM(float upperProximityBoundaryM) {
            mUpperProximityBoundaryM = upperProximityBoundaryM;
            return this;
        }

        /** Set the auto generate ranging parameters flag. */
        public Builder setAutoGenerateParams(boolean autoGenerateParams) {
            mAutoGenerateParams = autoGenerateParams;
            return this;
        }

        /** Set the base key. */
        public Builder setBaseKey(@NonNull byte[] baseKey) {
            mBaseKey = baseKey;
            return this;
        }

        /** Set the sync data. */
        public Builder setSyncData(@NonNull byte[] syncData) {
            mSyncData = syncData;
            return this;
        }

        /** Builds {@link SessionParameters}. */
        public SessionParameters build() {
            if (mDeviceId.isEmpty()) {
                throw new IllegalArgumentException("Empty deviceId");
            }
            if (mRangingMethod == RangingMethod.UNKNOWN) {
                throw new IllegalArgumentException("Unknown rangingMethod");
            }
            if (mLowerProximityBoundaryM < 0) {
                throw new IllegalArgumentException(
                        "Negative lowerProximityBoundaryM: " + mLowerProximityBoundaryM);
            }
            if (mLowerProximityBoundaryM > mUpperProximityBoundaryM) {
                throw new IllegalArgumentException(
                        "lowerProximityBoundaryM is greater than upperProximityBoundaryM: "
                                + mLowerProximityBoundaryM
                                + " > "
                                + mUpperProximityBoundaryM);
            }
            // If mAutoGenerateParams is false, mBaseKey and mSyncData will not be used.
            if (mAutoGenerateParams) {
                if (mBaseKey.length == 0) {
                    throw new IllegalArgumentException("Empty baseKey");
                }
                if (mBaseKey.length != 16 && mBaseKey.length != 32) {
                    throw new IllegalArgumentException(
                            "Invalid baseKey length: " + mBaseKey.length);
                }
                if (mSyncData.length == 0) {
                    throw new IllegalArgumentException("Empty syncData");
                }
                if (mSyncData.length != 16) {
                    throw new IllegalArgumentException(
                            "Invalid syncData length: " + mSyncData.length);
                }
            }

            return new SessionParameters(
                    mDeviceId,
                    mRangingMethod,
                    mLowerProximityBoundaryM,
                    mUpperProximityBoundaryM,
                    mAutoGenerateParams,
                    mBaseKey,
                    mSyncData);
        }
    }
}
