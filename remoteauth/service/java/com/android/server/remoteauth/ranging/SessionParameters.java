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

import androidx.annotation.IntDef;

import com.android.internal.util.Preconditions;
import com.android.server.remoteauth.ranging.RangingCapabilities.RangingMethod;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** The set of parameters to create a ranging session. */
public class SessionParameters {

    /** Ranging device role */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            value = {
                DEVICE_ROLE_UNKNOWN,
                DEVICE_ROLE_INITIATOR,
                DEVICE_ROLE_RESPONDER,
            })
    public @interface DeviceRole {}

    /** Unknown device role. */
    public static final int DEVICE_ROLE_UNKNOWN = 0x0;

    /** Device that initiate ranging. */
    public static final int DEVICE_ROLE_INITIATOR = 0x1;

    /** Device that respond to ranging. */
    public static final int DEVICE_ROLE_RESPONDER = 0x2;

    private final String mDeviceId;
    @RangingMethod private final int mRangingMethod;
    @DeviceRole private final int mDeviceRole;
    private final float mLowerProximityBoundaryM;
    private final float mUpperProximityBoundaryM;
    private final boolean mAutoDeriveParams;
    private final byte[] mBaseKey;
    private final byte[] mSyncData;

    public String getDeviceId() {
        return mDeviceId;
    }

    @RangingMethod
    public int getRangingMethod() {
        return mRangingMethod;
    }

    @DeviceRole
    public int getDeviceRole() {
        return mDeviceRole;
    }

    public float getLowerProximityBoundaryM() {
        return mLowerProximityBoundaryM;
    }

    public float getUpperProximityBoundaryM() {
        return mUpperProximityBoundaryM;
    }

    public boolean getAutoDeriveParams() {
        return mAutoDeriveParams;
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
            @DeviceRole int deviceRole,
            float lowerProximityBoundaryM,
            float upperProximityBoundaryM,
            boolean autoDeriveParams,
            byte[] baseKey,
            byte[] syncData) {
        mDeviceId = deviceId;
        mRangingMethod = rangingMethod;
        mDeviceRole = deviceRole;
        mLowerProximityBoundaryM = lowerProximityBoundaryM;
        mUpperProximityBoundaryM = upperProximityBoundaryM;
        mAutoDeriveParams = autoDeriveParams;
        mBaseKey = baseKey;
        mSyncData = syncData;
    }

    /** Builder class for {@link SessionParameters}. */
    public static final class Builder {
        private String mDeviceId = new String("");
        @RangingMethod private int mRangingMethod = RANGING_METHOD_UNKNOWN;
        @DeviceRole private int mDeviceRole = DEVICE_ROLE_UNKNOWN;
        private float mLowerProximityBoundaryM;
        private float mUpperProximityBoundaryM;
        private boolean mAutoDeriveParams = false;
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

        /** Sets the device role. */
        public Builder setDeviceRole(@DeviceRole int deviceRole) {
            mDeviceRole = deviceRole;
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

        /** Sets the auto derive ranging parameters flag. */
        public Builder setAutoDeriveParams(boolean autoDeriveParams) {
            mAutoDeriveParams = autoDeriveParams;
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
            Preconditions.checkArgument(mDeviceRole != DEVICE_ROLE_UNKNOWN, "Unknown deviceRole");
            Preconditions.checkArgument(
                    mLowerProximityBoundaryM >= 0,
                    "Negative lowerProximityBoundaryM: " + mLowerProximityBoundaryM);
            Preconditions.checkArgument(
                    mLowerProximityBoundaryM <= mUpperProximityBoundaryM,
                    "lowerProximityBoundaryM is greater than upperProximityBoundaryM: "
                            + mLowerProximityBoundaryM
                            + " > "
                            + mUpperProximityBoundaryM);
            // If mAutoDeriveParams is false, mBaseKey and mSyncData will not be used.
            if (mAutoDeriveParams) {
                Preconditions.checkArgument(
                        mBaseKey.length == 16 || mBaseKey.length == 32,
                        "Invalid baseKey length: " + mBaseKey.length);
                Preconditions.checkArgument(
                        mSyncData.length == 16, "Invalid syncData length: " + mSyncData.length);
            }

            return new SessionParameters(
                    mDeviceId,
                    mRangingMethod,
                    mDeviceRole,
                    mLowerProximityBoundaryM,
                    mUpperProximityBoundaryM,
                    mAutoDeriveParams,
                    mBaseKey,
                    mSyncData);
        }
    }
}
