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
import static android.net.ConnectivityManager.PROFILE_NETWORK_PREFERENCE_ENTERPRISE;
import static android.net.NetworkCapabilities.NET_CAPABILITY_ENTERPRISE_ID_1;
import static android.net.NetworkCapabilities.NET_CAPABILITY_ENTERPRISE_ID_5;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.net.ConnectivityManager.ProfileNetworkPreferencePolicy;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Network preferences to be set for the user profile
 * {@link ProfileNetworkPreferencePolicy}.
 * @hide
 */
@SystemApi(client = MODULE_LIBRARIES)
public final class ProfileNetworkPreference implements Parcelable {
    private final @ProfileNetworkPreferencePolicy int mPreference;
    private final @NetworkCapabilities.EnterpriseCapabilityId int mPreferenceEnterpriseId;

    private ProfileNetworkPreference(int preference,
            @NetworkCapabilities.EnterpriseCapabilityId int preferenceEnterpriseId) {
        mPreference = preference;
        mPreferenceEnterpriseId = preferenceEnterpriseId;
    }

    private ProfileNetworkPreference(Parcel in) {
        mPreference = in.readInt();
        mPreferenceEnterpriseId = in.readInt();
    }

    public int getPreference() {
        return mPreference;
    }

    /**
     * @return Preference enterprise sub-level.
     * Preference enterprise sub-level will be used to create different network preferences
     * within enterprise preference category.
     * Valid values starts from PROFILE_NETWORK_PREFERENCE_ENTERPRISE_ID_1 to
     * NetworkCapabilities.NET_CAPABILITY_ENTERPRISE_ID_5.
     * Preference sub-level is applicable only if preference is set as
     * PROFILE_NETWORK_PREFERENCE_ENTERPRISE. Default value is
     * NetworkCapabilities.NET_CAPABILITY_ENTERPRISE_ID_1.
     *
     */
    public @NetworkCapabilities.EnterpriseCapabilityId int getPreferenceEnterpriseId() {
        return mPreferenceEnterpriseId;
    }

    @Override
    public String toString() {
        return "ProfileNetworkPreference{"
                + "mPreference=" + getPreference()
                + "mPreferenceEnterpriseId=" + mPreferenceEnterpriseId
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final ProfileNetworkPreference that = (ProfileNetworkPreference) o;
        return mPreference == that.mPreference
                && mPreferenceEnterpriseId == that.mPreferenceEnterpriseId;
    }

    @Override
    public int hashCode() {
        return mPreference
                + mPreferenceEnterpriseId * 2;
    }

    /**
     * Builder used to create {@link ProfileNetworkPreference} objects.
     * Specify the preferred Network preference
     */
    public static final class Builder {
        private @ProfileNetworkPreferencePolicy int mPreference =
                PROFILE_NETWORK_PREFERENCE_DEFAULT;
        private int mPreferenceEnterpriseId =
                NET_CAPABILITY_ENTERPRISE_ID_1;

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
         * Returns an instance of {@link ProfileNetworkPreference} created from the
         * fields set on this builder.
         */
        @NonNull
        public ProfileNetworkPreference  build() {
            if (mPreference == PROFILE_NETWORK_PREFERENCE_ENTERPRISE) {
                if ((mPreferenceEnterpriseId
                        < NET_CAPABILITY_ENTERPRISE_ID_1)
                        || (mPreferenceEnterpriseId
                        > NET_CAPABILITY_ENTERPRISE_ID_5)) {
                    throw new IllegalArgumentException("Invalid preference enterprise sub-level");
                }
            }
            return new ProfileNetworkPreference(mPreference, mPreferenceEnterpriseId);
        }

        /**
         * Set the preference enterprise sub-level.
         *
         * Preference enterprise sub-level will be used to create different network preferences
         * within enterprise preference category.
         * Valid values starts from NetworkCapabilities.NET_CAPABILITY_ENTERPRISE_ID_1 to
         * NetworkCapabilities.NET_CAPABILITY_ENTERPRISE_ID_5.
         * Preference sub-level is applicable only if preference is set as
         * PROFILE_NETWORK_PREFERENCE_ENTERPRISE. Default value is
         * NetworkCapabilities.NET_CAPABILITY_ENTERPRISE_ID_1.
         * @param preferenceId  preference sub level
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public Builder setPreferenceEnterpriseId(
                @NetworkCapabilities.EnterpriseCapabilityId int preferenceId) {
            mPreferenceEnterpriseId = preferenceId;
            return this;
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
