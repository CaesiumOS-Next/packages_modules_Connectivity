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

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.UserHandle;

import java.util.Objects;

/**
 * Network preferences to set the default active network on a per-application basis as per a given
 * {@link android.net.ConnectivityManager.ProfileNetworkPreference}.
 *
 * @hide
 */
@SystemApi(client = MODULE_LIBRARIES)
public final class ProfileNetworkPreferences implements Parcelable {
    @NonNull private final UserHandle mProfile;
    @ConnectivityManager.ProfileNetworkPreference final int mPreference;
    final int mPreferenceSubLevel;

    /**
     *
     */
    @NonNull
    public  UserHandle getProfile() {
        return mProfile;
    }

    /**
     *
     */
    @NonNull
    public  @ConnectivityManager.ProfileNetworkPreference getPreference() {
        return mPreference;
    }

    /**
     *
     */
    public  int getPreferenceSubLevel() {
        return mPreferenceSubLevel;
    }

    private ProfileNetworkPreferences(UserHandle profile,
            @ConnectivityManager.ProfileNetworkPreference int preference, int preferenceSubLevel) {
        Objects.requireNonNull(profile);
        mProfile = profile;
        mPreference = preference;
        mPreferenceSubLevel = preferenceSubLevel;
    }

    @Override
    public String toString() {
        return "ProfileNetworkPreferences{"
                + "mProfile=" + mProfile
                + ", mPreference=" + mPreference
                + ", mPreferenceSubLevel=" + mPreferenceSubLevel
                + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProfileNetworkPreferences that = (ProfileNetworkPreferences) o;

        return mProfile.equals(that.mProfile)
                && mPreference == that.mPreference
                && mPreferenceSubLevel == that.mPreferenceSubLevel;
    }

    @Override
    public int hashCode() {
        return (mProfile.hashCode() * 41)
                + (mPreference * 47)
                + (mPreferenceSubLevel * 67);
    }

    /**
     * Builder used to create {@link ProfileNetworkPreferences} objects.
     * Specify the preferred Network to package name mappings.
     */
    public static final class Builder {
        @NonNull private UserHandle mProfile;
        @ConnectivityManager.ProfileNetworkPreference int mPreference;
        int mPreferenceSubLevel;

        public Builder() {}

        /**
         * Constructor to populate the builder's values with an already built
         * {@link ProfileNetworkPreferences}.
         * @param preferences the {@link ProfileNetworkPreferences} to populate with.
         */
        public Builder(@NonNull final ProfileNetworkPreferences preferences) {
            Objects.requireNonNull(preferences);
            mProfile = preferences.mProfile;
            mPreference = preferences.mPreference;
            mPreferenceSubLevel = preferences.mPreferenceSubLevel;
        }

        /**
         * Add a network preference for a given package. Previously stored values for the given
         * package will be overwritten.
         *
         * @param packageName full package name (e.g.: "com.google.apps.contacts") of the app
         *                    to use the given preference
         * @param preference  the desired network preference to use
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public Builder addProfile(@NonNull UserHandle profile) {
            Objects.requireNonNull(profile);
            mProfile = profile;
            return this;
        }

        /**
         * Remove a network preference for a given package.
         *
         * @param packageName full package name (e.g.: "com.google.apps.contacts") of the app to
         *                    remove a preference for.
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public Builder addPreference(int preference) {
            mPreference = preference;
            return this;
        }

        /**
         * Remove a network preference for a given package.
         *
         * @param packageName full package name (e.g.: "com.google.apps.contacts") of the app to
         *                    remove a preference for.
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public Builder addPreferenceSubLevel(int preferenceSubLevel) {
            mPreferenceSubLevel = preferenceSubLevel;
            return this;
        }

        /**
         * Build {@link ProfileNetworkPreferences} return the current OEM network preferences.
         */
        @NonNull
        public ProfileNetworkPreferences build() {
            return new ProfileNetworkPreferences(mProfile, mPreference, mPreferenceSubLevel);
        }
    }

    @Override
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        dest.writeInt(mProfile);
        dest.writeInt(mPreference);
        dest.writeInt(mPreferenceSubLevel);
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
                public ProfileNetworkPreferences createFromParcel(@NonNull android.os.Parcel in) {
                    return new ProfileNetworkPreferences(
                            in.readBundle(getClass().getClassLoader()));
                }
            };
}
