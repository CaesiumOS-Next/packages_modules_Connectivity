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

import android.annotation.NonNull;
import android.annotation.Nullable;

/** Class representing a discovered device. */
public final class DiscoveredDevice {
    private ConnectionInfo mConnectionInfo;
    private String mDisplayName;

    public DiscoveredDevice(ConnectionInfo connectionInfo, @Nullable String displayName) {
        this.mConnectionInfo = connectionInfo;
        this.mDisplayName = displayName;
    }

    public ConnectionInfo getConnectionInfo() {
        return this.mConnectionInfo;
    }

    public String getDisplayName() {
        return this.mDisplayName;
    }

    /** Returns true if this ConnectionInfo object is equals to other. */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DiscoveredDevice)) {
            return false;
        }

        DiscoveredDevice other = (DiscoveredDevice) o;
        return mConnectionInfo.equals(other.getConnectionInfo())
                && mDisplayName.equals(other.getDisplayName());
    }

    /** Builder for {@link DiscoveredDevice}. */
    public static class Builder {
        private ConnectionInfo mConnectionInfo;
        private String mDisplayName;

        private Builder() {}

        /** Static method to create a new builder */
        public static Builder newInstance() {
            return new Builder();
        }

        /**
         * Sets the connection info of the DiscoveredDevice.
         *
         * @param connectionInfo identifying the connection.
         */
        @NonNull
        public Builder setConnectionInfo(ConnectionInfo connectionInfo) {
            mConnectionInfo = connectionInfo;
            return this;
        }

        /**
         * Sets the display name of the DiscoveredDevice.
         *
         * @param displayName May be null.
         */
        @NonNull
        public Builder setDisplayName(@Nullable String displayName) {
            mDisplayName = displayName;
            return this;
        }

        /** Builds the DiscoveredDevice object. */
        @NonNull
        public DiscoveredDevice build() {
            return new DiscoveredDevice(this.mConnectionInfo, this.mDisplayName);
        }
    }
}
