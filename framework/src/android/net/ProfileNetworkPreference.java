/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.net;

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;
import static android.net.ConnectivityManager.PROFILE_NETWORK_PREFERENCE_DEFAULT;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.net.ConnectivityManager.ProfileNetworkPreferencePolicy;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Network preferences to be set for the user profile
 * {@link ProfileNetworkPreferencePolicy}.
 * @hide
 */
@SystemApi(client = MODULE_LIBRARIES)
public final class ProfileNetworkPreference implements Parcelable {
    private final @ProfileNetworkPreferencePolicy int mPreference;
    private boolean mAllowFallback;

    private ProfileNetworkPreference(int preference, boolean allowFallback) {
        mPreference = preference;
        mAllowFallback = allowFallback;
    }

    private ProfileNetworkPreference(Parcel in) {
        mPreference = in.readInt();
        mAllowFallback = in.readBoolean();
    }

    public int getPreference() {
        return mPreference;
    }

    /**
     * is fallback to default network allowed. This boolean allows an enterprise admin to
     * configure whether default connection (default internet or wifi) should be used or not
     * if an enterprise connection is not available. If enterprise apn is available and if slice
     * is not available then enterprise apn will be used to set up the data call. But if that fails
     * then the data traffic will fall back to default pdn if allowFallback is set to true
     * else no network will be marked as available for the enterprise profile.
     * @return true if fallback is allowed, else false.
     */
    public boolean isAllowFallback() {
        return mAllowFallback;
    }

    @Override
    public String toString() {
        return "ProfileNetworkPreference{"
                + "mPreference=" + getPreference()
                + "mAllowFallback=" + isAllowFallback()
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final ProfileNetworkPreference that = (ProfileNetworkPreference) o;
        return mPreference == that.mPreference
                && mAllowFallback == that.mAllowFallback;
    }

    @Override
    public int hashCode() {
        return ((mPreference * 17)
                + (Objects.hashCode(mAllowFallback) * 23));
    }

    /**
     * Builder used to create {@link ProfileNetworkPreference} objects.
     * Specify the preferred Network preference
     */
    public static final class Builder {
        private @ProfileNetworkPreferencePolicy int mPreference =
                PROFILE_NETWORK_PREFERENCE_DEFAULT;
        private boolean mAllowFallback = true;

        /**
         * Constructs an empty Builder with PROFILE_NETWORK_PREFERENCE_DEFAULT profile preference
         */
        public Builder() {}

        /**
         * Set the profile network preference
         * See the documentation for the individual preferences for a description of the supported
         * behaviors. Default value is PROFILE_NETWORK_PREFERENCE_DEFAULT.
         * @param preference  the desired network preference to use
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public Builder setPreference(@ProfileNetworkPreferencePolicy int preference) {
            mPreference = preference;
            return this;
        }

        /**
         * Set allow fallback. This boolean allows an enterprise admin to configure whether
         * default connection (default internet or wifi) should be used or not if an enterprise
         * connection is not available. If enterprise apn is available and if slice is not
         * available then enterprise apn will be used to set up the data call. But if that fails
         * then the data traffic will fall back to default pdn if allowFallback is set to true
         * else no network will be marked as available for the enterprise profile.
         * @param allowFallback  true if fallback is allowed else false
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public Builder setAllowFallback(boolean allowFallback) {
            mAllowFallback = allowFallback;
            return this;
        }

        /**
         * Returns an instance of {@link ProfileNetworkPreference} created from the
         * fields set on this builder.
         */
        @NonNull
        public ProfileNetworkPreference  build() {
            return new ProfileNetworkPreference(mPreference, mAllowFallback);
        }
    }

    @Override
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        dest.writeInt(mPreference);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<ProfileNetworkPreference> CREATOR =
            new Creator<ProfileNetworkPreference>() {
                @Override
                public ProfileNetworkPreference[] newArray(int size) {
                    return new ProfileNetworkPreference[size];
                }

                @Override
                public ProfileNetworkPreference  createFromParcel(
                        @NonNull android.os.Parcel in) {
                    return new ProfileNetworkPreference(in);
                }
            };
}
