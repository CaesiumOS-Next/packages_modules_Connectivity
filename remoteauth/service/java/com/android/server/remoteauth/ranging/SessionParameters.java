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
package com.android.server.remoteauth.ranging;

import static com.android.server.remoteauth.ranging.RangingCapabilities.RANGING_METHOD_UNKNOWN;

import android.annotation.NonNull;

import com.android.internal.util.Preconditions;
import com.android.server.remoteauth.ranging.RangingCapabilities.RangingMethod;

/** The set of parameters to create a ranging session. */
public class SessionParameters {

    private final String mDeviceId;
    @RangingMethod private final int mRangingMethod;
    private final float mLowerProximityBoundaryM;
    private final float mUpperProximityBoundaryM;
    private final boolean mAutoGenerateParams;
    private final byte[] mBaseKey;
    private final byte[] mSyncData;

    public String getDeviceId() {
        return mDeviceId;
    }

    @RangingMethod
    public int getRangingMethod() {
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
            @RangingMethod int rangingMethod,
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
        @RangingMethod private int mRangingMethod = RANGING_METHOD_UNKNOWN;
        private float mLowerProximityBoundaryM;
        private float mUpperProximityBoundaryM;
        private boolean mAutoGenerateParams = false;
        private byte[] mBaseKey = new byte[] {};
        private byte[] mSyncData = new byte[] {};

        /** Sets the device id. */
        public Builder setDeviceId(@NonNull String deviceId) {
            mDeviceId = deviceId;
            return this;
        }

        /** Sets the ranging method. */
        public Builder setRangingMethod(@RangingMethod int rangingMethod) {
            mRangingMethod = rangingMethod;
            return this;
        }

        /** Sets the lower proximity boundary in meters. */
        public Builder setLowerProximityBoundaryM(float lowerProximityBoundaryM) {
            mLowerProximityBoundaryM = lowerProximityBoundaryM;
            return this;
        }

        /** Sets the upper proximity boundary in meters. */
        public Builder setUpperProximityBoundaryM(float upperProximityBoundaryM) {
            mUpperProximityBoundaryM = upperProximityBoundaryM;
            return this;
        }

        /** Sets the auto generate ranging parameters flag. */
        public Builder setAutoGenerateParams(boolean autoGenerateParams) {
            mAutoGenerateParams = autoGenerateParams;
            return this;
        }

        /** Sets the base key. */
        public Builder setBaseKey(@NonNull byte[] baseKey) {
            mBaseKey = baseKey;
            return this;
        }

        /** Sets the sync data. */
        public Builder setSyncData(@NonNull byte[] syncData) {
            mSyncData = syncData;
            return this;
        }

        /** Builds {@link SessionParameters}. */
        public SessionParameters build() {
            Preconditions.checkArgument(!mDeviceId.isEmpty(), "deviceId must not be empty.");
            Preconditions.checkArgument(
                    mRangingMethod != RANGING_METHOD_UNKNOWN, "Unknown rangingMethod");
            Preconditions.checkArgument(
                    mLowerProximityBoundaryM >= 0,
                    "Negative lowerProximityBoundaryM: " + mLowerProximityBoundaryM);
            Preconditions.checkArgument(
                    mLowerProximityBoundaryM <= mUpperProximityBoundaryM,
                    "lowerProximityBoundaryM is greater than upperProximityBoundaryM: "
                            + mLowerProximityBoundaryM
                            + " > "
                            + mUpperProximityBoundaryM);
            // If mAutoGenerateParams is false, mBaseKey and mSyncData will not be used.
            if (mAutoGenerateParams) {
                Preconditions.checkArgument(
                        mBaseKey.length == 16 || mBaseKey.length == 32,
                        "Invalid baseKey length: " + mBaseKey.length);
                Preconditions.checkArgument(
                        mSyncData.length == 16, "Invalid syncData length: " + mSyncData.length);
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
