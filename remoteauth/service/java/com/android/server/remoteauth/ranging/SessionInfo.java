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

import com.android.server.remote_auth.ranging.RangingCapabilities.RangingMethod;

/** Describe information about the session. */
public class SessionInfo {

    private String mDeviceId;
    private RangingMethod mRangingMethod;

    public String getDeviceId() {
        return mDeviceId;
    }

    public RangingMethod getRangingMethod() {
        return mRangingMethod;
    }

    private SessionInfo(String deviceId, RangingMethod rangingMethod) {
        mDeviceId = deviceId;
        mRangingMethod = rangingMethod;
    }

    @Override
    public String toString() {
        return "SessionInfo { "
                + "DeviceId = "
                + mDeviceId
                + "RangingMethod = "
                + mRangingMethod
                + " }";
    }

    /** Builder class for {@link SessionInfo}. */
    public static final class Builder {
        private String mDeviceId = new String("");
        private RangingMethod mRangingMethod = RangingMethod.UNKNOWN;

        /** Set the device id. */
        public Builder setDeviceId(String deviceId) {
            mDeviceId = deviceId;
            return this;
        }

        /** Set the ranging method. */
        public Builder setRangingMethod(RangingMethod rangingMethod) {
            mRangingMethod = rangingMethod;
            return this;
        }

        /** Builds {@link SessionInfo}. */
        public SessionInfo build() {
            if (mDeviceId.isEmpty()) {
                throw new IllegalArgumentException("Empty deviceId");
            }
            if (mRangingMethod == RangingMethod.UNKNOWN) {
                throw new IllegalArgumentException("Unknown rangingMethod");
            }
            return new SessionInfo(mDeviceId, mRangingMethod);
        }
    }
}
