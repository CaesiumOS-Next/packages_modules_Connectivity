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
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.net.ConnectivityManager.ProfileNetworkPreference;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;

import java.util.Objects;

/**
 * Network preferences to be set for the user profile
 * {@link ProfileNetworkPreference}.
 * @hide
 */
@SystemApi(client = MODULE_LIBRARIES)
public final class ProfileNetworkPreferences implements Parcelable {
    private final @NonNull UserHandle mUser;
    private final @ProfileNetworkPreference int mProfilePreference;

    private ProfileNetworkPreferences(UserHandle user, int profilePreference) {
        mUser = user;
        mProfilePreference = profilePreference;
    }

    private ProfileNetworkPreferences(Parcel in) {
        mUser = UserHandle.readFromParcel(in);
        mProfilePreference = in.readInt();
    }

    public int getProfileNetworkPreference() {
        return mProfilePreference;
    }
    public @Nullable UserHandle getUser() {
        return mUser;
    }

    @Override
    public String toString() {
        return "ProfileNetworkPreferences{"
                + "mUser=" + getUser()
                + "mProfilePreference=" + getProfileNetworkPreference()
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProfileNetworkPreferences that = (ProfileNetworkPreferences) o;
        return mProfilePreference == that.mProfilePreference
                && Objects.equals(mUser, that.mUser);
    }

    @Override
    public int hashCode() {
        return ((mProfilePreference * 17)
                + (Objects.hashCode(mUser) * 23));
    }

    /**
     * Builder used to create {@link ProfileNetworkPreferences} objects.
     * Specify the preferred Network preference
     */
    public static final class Builder {
        private @Nullable UserHandle mUser;
        private @ProfileNetworkPreference int mProfilePreference =
                PROFILE_NETWORK_PREFERENCE_DEFAULT;

        /**
         * Constructs an empty Builder with PROFILE_NETWORK_PREFERENCE_DEFAULT profile preference
         * and null user
         */
        public Builder() {}

        /**
         * Set the profile network preference
         * See the documentation for the individual preferences for a description of the supported
         * behaviors. Default value will be PROFILE_NETWORK_PREFERENCE_DEFAULT.
         * @param preference  the desired network preference to use
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public Builder setProfileNetworkPreference(@ProfileNetworkPreference int preference) {
            mProfilePreference = preference;
            return this;
        }

        /**
         * Set the user profile corresponding to network preferences.
         * @param user  profile
         * @return The builder to facilitate chaining.
         */
        // This function is for establishing per-profile default networking and can only be called
        // by the device policy manager, running as the system server. It would make no sense to
        // call it on a context for a user because it does not establish a setting on behalf of
        // a user, rather it establishes a setting for a user on behalf of the DPM.
        @SuppressLint({"UserHandleName"})
        @NonNull
        public Builder setUser(@NonNull @ProfileNetworkPreference UserHandle user) {
            mUser = user;
            return this;
        }

        /**
         * Returns an instance of {@link ProfileNetworkPreferences} created from the
         * fields set on this builder.
         */
        @NonNull
        public ProfileNetworkPreferences build() {
            if (mUser == null) {
                throw new IllegalStateException("user handle should not be null");
            }
            return new ProfileNetworkPreferences(mUser, mProfilePreference);
        }
    }

    @Override
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        UserHandle.writeToParcel(mUser, dest);
        dest.writeInt(mProfilePreference);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<ProfileNetworkPreferences> CREATOR =
            new Creator<ProfileNetworkPreferences>() {
                @Override
                public ProfileNetworkPreferences[] newArray(int size) {
                    return new ProfileNetworkPreferences[size];
                }

                @Override
                public ProfileNetworkPreferences createFromParcel(
                        @NonNull android.os.Parcel in) {
                    return new ProfileNetworkPreferences(in);
                }
            };
}
