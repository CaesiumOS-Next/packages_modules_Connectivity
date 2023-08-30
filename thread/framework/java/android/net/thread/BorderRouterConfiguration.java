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

package android.net.thread;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Data interface for managing Thread Border Router configurations.
 * @hide
 */
@SystemApi
public final class BorderRouterConfiguration implements Parcelable {
    private final boolean mBorderRouterEnabled;
    private final boolean mPrefixDelegationEnabled;
    private final boolean mNat64Enabled;

    @NonNull
    public static final Creator<BorderRouterConfiguration> CREATOR =
            new Creator<>() {
                @Override
                public BorderRouterConfiguration createFromParcel(Parcel in) {
                    return new BorderRouterConfiguration(in);
                }

                @Override
                public BorderRouterConfiguration[] newArray(int size) {
                    return new BorderRouterConfiguration[size];
                }
            };

    private BorderRouterConfiguration(Parcel in) {
        this(in.readBoolean(), in.readBoolean(), in.readBoolean());
    }

    private BorderRouterConfiguration(Builder builder) {
        this(builder.mBorderRouterEnabled, builder.mPrefixDelegationEnabled, builder.mNat64Enabled);
    }

    private BorderRouterConfiguration(
            boolean borderRouterEnabled, boolean prefixDelegationEnabled, boolean nat64Enabled) {
        this.mBorderRouterEnabled = borderRouterEnabled;
        this.mPrefixDelegationEnabled = prefixDelegationEnabled;
        this.mNat64Enabled = nat64Enabled;
    }

    /** Returns {@code true} if the Thread Border Router feature is enabled. */
    public boolean isBorderRouterEnabled() {
        return mBorderRouterEnabled;
    }

    /**
     * Returns {@code true} if the Thread Border Router NAT64 feature is enabled.
     * @hide
     */
    public boolean isNat64Enabled() {
        return mNat64Enabled;
    }

    /**
     * Returns {@code true} if the Thread Border Router IPv6 Prefix Delegation is enabled.
     * @hide
     */
    public boolean isPrefixDelegationEnabled() {
        return mPrefixDelegationEnabled;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBoolean(mBorderRouterEnabled);
        dest.writeBoolean(mPrefixDelegationEnabled);
        dest.writeBoolean(mNat64Enabled);
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        } else if (!(other instanceof BorderRouterConfiguration)) {
            return false;
        } else {
            BorderRouterConfiguration otherConfig = (BorderRouterConfiguration) other;
            return mBorderRouterEnabled == otherConfig.mBorderRouterEnabled
                    && mNat64Enabled == otherConfig.mNat64Enabled
                    && mPrefixDelegationEnabled == otherConfig.mPrefixDelegationEnabled;
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(mBorderRouterEnabled, mNat64Enabled, mPrefixDelegationEnabled);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{borderRouterEnabled=")
                .append(mBorderRouterEnabled)
                .append(", prefixDelegationEnabled=")
                .append(mPrefixDelegationEnabled)
                .append(", nat64Enabled=")
                .append(mNat64Enabled)
                .append("}");
        return sb.toString();
    }

    /** The builder for creating {@link BorderRouterConfiguration} objects. */
    public static final class Builder {
        private boolean mBorderRouterEnabled;
        private boolean mPrefixDelegationEnabled;
        private boolean mNat64Enabled;

        /**
         * Creates a new {@link Builder} object.
         *
         * @param borderRouterEnabled use {@code true} to enable the whole Border Router feature, or
         *     {@code false} to disable it
         */
        public Builder(boolean borderRouterEnabled) {
            this.mBorderRouterEnabled = borderRouterEnabled;
        }

        /**
         * Creates a new {@link Builder} object from a {@link BorderRouterConfiguration} object.
         *
         * @param config the Border Router configurations to be copied
         */
        public Builder(@NonNull BorderRouterConfiguration config) {
            requireNonNull(config, "config cannot be null");
            this.mBorderRouterEnabled = config.mBorderRouterEnabled;
            this.mNat64Enabled = config.mNat64Enabled;
            this.mPrefixDelegationEnabled = config.mPrefixDelegationEnabled;
        }

        /**
         * Enables/Disables the Thread Border Router IPv6 Prefix Delegation feature. Must also
         * enable the Thread Border Router with {@code setBorderRouterEnabled(true)} when enabling
         * the IPv6 Prefix Delegation feature.
         *
         * @throws IllegalStateException if {@code enabled} is set to {@code true} while the Border
         *     Router feature is not enabled
         * @hide
         */
        @NonNull
        public Builder setPrefixDelegationEnabled(boolean enabled) {
            if (enabled && !mBorderRouterEnabled) {
                throw new IllegalStateException(
                        "Cannot enable Prefix Delegation while the Border Router feature is"
                                + " disabled");
            }
            mPrefixDelegationEnabled = enabled;
            return this;
        }

        /**
         * Enables/Disables the Thread Border Router NAT64 feature. Must also enable the Thread
         * Border Router with {@code setBorderRouterEnabled(true)} when enabling the NAT64 feature.
         *
         * @throws IllegalStateException if {@code enabled} is set to {@code true} while the Border
         *     Router feature is not enabled
         * @hide
         */
        @NonNull
        public Builder setNat64Enabled(boolean enabled) {
            if (enabled && !mBorderRouterEnabled) {
                throw new IllegalStateException(
                        "Cannot enable NAT64 while the Border Router feature is disabled");
            }
            mNat64Enabled = enabled;
            return this;
        }

        /** Creates a new {@link BorderRouterConfiguration} object. */
        @NonNull
        public BorderRouterConfiguration build() {
            return new BorderRouterConfiguration(this);
        }
    }
}
