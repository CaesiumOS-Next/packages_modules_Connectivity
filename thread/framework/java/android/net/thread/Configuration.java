/*
 * Copyright 2024 The Android Open Source Project
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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Data interface for Thread device configuration.
 *
 * <p>An example usage of creating a Configuration that turns on NAT64 feature based on an existing
 * configuration:
 *
 * <pre>{@code
 * Configuration config = new Configuration.Builder(existingConfig).setNat64Enabled(true).build();
 * }</pre>
 *
 * @see ThreadNetworkController#setConfiguration
 * @see ThreadNetworkController#registerConfigurationCallback
 * @see ThreadNetworkController#unregisterConfigurationCallback
 * @hide
 */
@FlaggedApi(ThreadNetworkFlags.FLAG_CONFIGURATION_ENABLED)
@SystemApi
public final class Configuration implements Parcelable {
    private final boolean mNat64Enabled;
    private final boolean mDhcp6PdEnabled;
    private final boolean mTrelEnabled;

    private Configuration(Builder builder) {
        this(builder.mNat64Enabled, builder.mDhcp6PdEnabled, builder.mTrelEnabled);
    }

    private Configuration(boolean nat64Enabled, boolean dhcp6PdEnabled, boolean trelEnabled) {
        this.mNat64Enabled = nat64Enabled;
        this.mDhcp6PdEnabled = dhcp6PdEnabled;
        this.mTrelEnabled = trelEnabled;
    }

    /** Returns {@code true} if NAT64 is enabled. */
    public boolean isNat64Enabled() {
        return mNat64Enabled;
    }

    /** Returns {@code true} if DHCPv6 Prefix Delegation is enabled. */
    public boolean isDhcp6PdEnabled() {
        return mDhcp6PdEnabled;
    }

    /** Returns {@code true} if TREL is enabled. */
    public boolean isTrelEnabled() {
        return mTrelEnabled;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        } else if (!(other instanceof Configuration)) {
            return false;
        } else {
            Configuration otherConfig = (Configuration) other;
            return mNat64Enabled == otherConfig.mNat64Enabled
                    && mDhcp6PdEnabled == otherConfig.mDhcp6PdEnabled
                    && mTrelEnabled == otherConfig.mTrelEnabled;
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(mNat64Enabled, mDhcp6PdEnabled, mTrelEnabled);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("Nat64Enabled=").append(mNat64Enabled);
        sb.append(", Dhcp6PdEnabled=").append(mDhcp6PdEnabled);
        sb.append(", TrelEnabled=").append(mTrelEnabled);
        sb.append('}');
        return sb.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBoolean(mNat64Enabled);
        dest.writeBoolean(mDhcp6PdEnabled);
        dest.writeBoolean(mTrelEnabled);
    }

    public static final @NonNull Creator<Configuration> CREATOR =
            new Creator<>() {
                @Override
                public Configuration createFromParcel(Parcel in) {
                    Configuration.Builder builder = new Configuration.Builder();
                    builder.setNat64Enabled(in.readBoolean());
                    builder.setDhcp6PdEnabled(in.readBoolean());
                    builder.setTrelEnabled(in.readBoolean());
                    return builder.build();
                }

                @Override
                public Configuration[] newArray(int size) {
                    return new Configuration[size];
                }
            };

    /** The builder for creating {@link Configuration} objects. */
    public static final class Builder {
        private boolean mNat64Enabled = false;
        private boolean mDhcp6PdEnabled = false;
        private boolean mTrelEnabled = false;

        /** Creates a new {@link Builder} object with all features disabled. */
        public Builder() {}

        /**
         * Creates a new {@link Builder} object from a {@link Configuration} object.
         *
         * @param config the Border Router configurations to be copied
         */
        public Builder(@NonNull Configuration config) {
            Objects.requireNonNull(config);

            mNat64Enabled = config.mNat64Enabled;
            mDhcp6PdEnabled = config.mDhcp6PdEnabled;
            mTrelEnabled = config.mTrelEnabled;
        }

        /**
         * Enables or disables NAT64 for the device.
         *
         * <p>Enabling this feature will allow Thread devices to connect to the internet/cloud over
         * IPv4.
         */
        @NonNull
        public Builder setNat64Enabled(boolean enabled) {
            this.mNat64Enabled = enabled;
            return this;
        }

        /**
         * Enables or disables Prefix Delegation for the device.
         *
         * <p>Enabling this feature will allow Thread devices to connect to the internet/cloud over
         * IPv6.
         */
        @NonNull
        public Builder setDhcp6PdEnabled(boolean enabled) {
            this.mDhcp6PdEnabled = enabled;
            return this;
        }

        /**
         * Enables or disables TREL feature for the device.
         *
         * <p>Enabling this feature will this device to communicate with other TREl-enabled Thread
         * devices over infrastructure network.
         */
        @NonNull
        public Builder setTrelEnabled(boolean enabled) {
            this.mTrelEnabled = enabled;
            return this;
        }

        /** Creates a new {@link Configuration} object. */
        @NonNull
        public Configuration build() {
            return new Configuration(this);
        }
    }
}
