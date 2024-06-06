package android.net.thread;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/** @hide */
@FlaggedApi(ThreadNetworkFlags.FLAG_THREAD_CONFIGURATION_ENABLED)
@SystemApi
public final class ThreadConfiguration implements Parcelable {

    private final boolean mNat64Enabled;
    private final boolean mPrefixDelegationEnabled;
    private final boolean mTrelEnabled;

    /** Returns {@code true} if NAT64 is enabled. */
    public boolean isNat64Enabled() {
        return mNat64Enabled;
    }

    /** Returns {@code true} if DHCPv6 Prefix Delegation is enabled. */
    public boolean isPrefixDelegationEnabled() {
        return mPrefixDelegationEnabled;
    }

    /** Returns {@code true} if TREL is enabled. */
    public boolean isTrelEnabled() {
        return mTrelEnabled;
    }

    public boolean equals(Object other) {
        if (this == other) {
            return true;
        } else if (!(other instanceof ThreadConfiguration)) {
            return false;
        } else {
            ThreadConfiguration otherConfig = (ThreadConfiguration) other;
            return mNat64Enabled == otherConfig.mNat64Enabled
                    && mPrefixDelegationEnabled == otherConfig.mPrefixDelegationEnabled
                    && mTrelEnabled == otherConfig.mTrelEnabled;
        }
    }

    public int hashCode() {
        return Objects.hash(mNat64Enabled, mPrefixDelegationEnabled, mTrelEnabled);
    }

    public String toString() {
        return "ThreadConfiguration {"
                + "mNat64Enabled="
                + mNat64Enabled
                + ", mPrefixDelegationEnabled="
                + mPrefixDelegationEnabled
                + ", mTrelEnabled="
                + mTrelEnabled
                + "}";
    }

    private ThreadConfiguration(Builder builder) {
        this(builder.mNat64Enabled, builder.mPrefixDelegationEnabled, builder.mTrelEnabled);
    }

    private ThreadConfiguration(
            boolean nat64Enabled, boolean prefixDelegationEnabled, boolean trelEnabled) {
        this.mNat64Enabled = nat64Enabled;
        this.mPrefixDelegationEnabled = prefixDelegationEnabled;
        this.mTrelEnabled = trelEnabled;
    }

    public static final class Builder {
        private boolean mNat64Enabled = false;
        private boolean mPrefixDelegationEnabled = false;
        private boolean mTrelEnabled = false;

        /** Creates a new {@link Builder} object with all features disabled. */
        public Builder() {}

        /**
         * Creates a new {@link Builder} object from a {@link ThreadConfiguration} object.
         *
         * @param config the Border Router configurations to be copied
         */
        public Builder(@NonNull ThreadConfiguration config) {
            Objects.requireNonNull(config);

            mNat64Enabled = config.mNat64Enabled;
            mPrefixDelegationEnabled = config.mPrefixDelegationEnabled;
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
        public Builder setPrefixDelegationEnabled(boolean enabled) {
            this.mPrefixDelegationEnabled = enabled;
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

        /** Creates a new {@link ThreadConfiguration} object. */
        @NonNull
        public ThreadConfiguration build() {
            return new ThreadConfiguration(this);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBoolean(mNat64Enabled);
        dest.writeBoolean(mPrefixDelegationEnabled);
        dest.writeBoolean(mTrelEnabled);
    }

    public static final @NonNull Creator<ThreadConfiguration> CREATOR =
            new Creator<ThreadConfiguration>() {
                @Override
                public ThreadConfiguration createFromParcel(Parcel in) {
                    ThreadConfiguration.Builder builder = new ThreadConfiguration.Builder();
                    builder.setNat64Enabled(in.readBoolean());
                    builder.setPrefixDelegationEnabled(in.readBoolean());
                    builder.setTrelEnabled(in.readBoolean());
                    return builder.build();
                }

                @Override
                public ThreadConfiguration[] newArray(int size) {
                    return new ThreadConfiguration[size];
                }
            };
}
