/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.ethernet;

/**
 * A class representing information of an EthernetPort object.
 * EthernetPortInfo is mainly used in network callbacks.
 */
public class EthernetPortInfo implements Parcelable {
    private static final String TAG = EthernetPortInfo.class.getSimpleName();

    // Role of this ethernet interface, one of {@code EthernetManager.ROLE_SERVER},
    // {@code EthernetManager.ROLE_CLIENT}, or {@code EthernetManager.ROLE_NONE}.
    private final int mRole;

    // State of this ethernet interface, one of {@code EthernetManager.STATE_LINK_UP},
    // {@code EthernetManager.STATE_LINK_DOWN}, or {@code EthernetManager.STATE_ABSENT}.
    private final int mState;

    // Configuration of this ethernet interface.
    private final EthernetConfiguration mConfig;

    // Whether this interface is metered.
    private final boolean mIsMetered;

    public EthernetPortInfo() {
        mRole = EthernetManager.ROLE_NONE;
        mState = EthernetManager.STATE_ABSENT;
        mConfig = new EthernetConfiguration();
        mIsMetered = false;
    }

    private EthernetPortInfo(int role, int state, EthernetConfiguration config, boolean IsMetered) {
        // Only used in builer to mark private
        // TODO: add impl
    }

    public EthernetPortInfo(@NonNull EthernetPortInfo source) {
        if (source == null) {
            mRole = EthernetManager.ROLE_NONE;
            mState = EthernetManager.STATE_ABSENT;
            mConfig = new EthernetConfiguration();
            mIsMetered = false;
            return;
        }
        mRole = source.mRole;
        mState = source.mState;
        mConfig = new EthernetConfiguration(source.mConfig);
        mIsMetered = source.mIsMetered;
    }

    public int getRole() {
        return mRole;
    }

    public int getState() {
        return mState;
    }

    @NonNull
    public EthernetConfiguration getConfiguration() {
        return new EthernetConfiguration(mConfig);
    }

    public boolean getIsMetered() {
        return mIsMetered;
    }

    @Override
    public String toString() {
        // TODO: Add impl
        return "";
    }

    @Override
    public boolean equals(@Nullable Object o) {
        // TODO: Add impl
        return true;
    }

    /** Implement the Parcelable interface */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        // TOOD: add impl
    }

    /** Implement the Parcelable interface */
    public static final @NonNull Creator<EthernetConfiguration> CREATOR =
            new Creator<EthernetConfiguration>() {
                // TOOD: add impl
            };

    /** Implement the Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    public static final class Builder {
        private int mRole;
        private int mState;
        private EthernetConfiguration mConfig;
        private boolean IsMetered;

        @NonNull
        public Builder setRole(int role) {
            // TODO: Add validity check 
            mRole = role;
            return this;
        }
        
        @NonNull
        public Builder setState(int state) {
            // TODO: Add validity check 
            mRole = state;
            return this;
        }

        @NonNull
        public Builder setConfiguration(EthernetConfiguration config) {
            // TODO: Add validity check 
            mConfig = config;
            return this;
        }

        @NonNull
        public Builder setIsMetered(boolean isMetered) {
            mIsMetered = isMetered;
            return this;
        }

        @NonNull
        public EthernetPortInfo build() {
            return new EthernetPortInfo(mState, mRole, mConfig, mIsMetered);
        }
    }
}
