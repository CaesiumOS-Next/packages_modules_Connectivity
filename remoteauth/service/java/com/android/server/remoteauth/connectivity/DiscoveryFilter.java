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

package com.google.android.server.remoteauth.connectivity;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;

/** Filter for device discovery. */
public final class DiscoveryFilter {
    @IntDef({DeviceType.WATCH})
    public @interface DeviceType {
        int WATCH = 0;
    }

    private @DeviceType int mDeviceType;
    private final @Nullable String mDeviceName;
    private final @Nullable String mPeerAddress;

    public DiscoveryFilter(
            @DeviceType int deviceType, @Nullable String deviceName, @Nullable String peerAddress) {
        this.mDeviceType = deviceType;
        this.mDeviceName = deviceName;
        this.mPeerAddress = peerAddress;
    }

    public @DeviceType int getDeviceType() {
        return this.mDeviceType;
    }

    public @Nullable String getDeviceName() {
        return this.mDeviceName;
    }

    public @Nullable String getPeerAddress() {
        return this.mPeerAddress;
    }

    /** Builder for {@link DiscoverFilter} */
    public static class Builder {
        private Builder() {}

        /** Static method to create a new builder */
        public static Builder newInstance() {
            return new Builder();
        }

        /**
         * Sets the device type of the DiscoveryFilter.
         *
         * @param deviceType of the peer device.
         */
        @NonNull
        public Builder setDeviceType(@DeviceType int deviceType) {
            mDeviceType = deviceType;
            return this;
        }

        /**
         * Sets the device name.
         *
         * @param deviceName May be null.
         */
        @NonNull
        public Builder setDeviceName(String deviceName) {
            mDeviceName = deviceName;
            return this;
        }

        /**
         * Sets the peer address.
         *
         * @param peerAddress Mac address of the peer device.
         */
        @NonNull
        public Builder setPeerAddress(String peerAddress) {
            mPeerAddress = peerAddress;
            return this;
        }

        /** Builds the DiscoveryFilter object. */
        @NonNull
        public DiscoveryFilter build() {
            return new DiscoveryFilter(this.mDeviceType, this.mDeviceName, this.mPeerAddress);
        }

        private @DeviceType int mDeviceType;
        private @Nullable String mDeviceName;
        private @Nullable String mPeerAddress;
    }
}
