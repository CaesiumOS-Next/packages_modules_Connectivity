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
import android.annotation.Nullable;
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
public final class ProfileNetworkPreferenceDetails implements Parcelable {
    private UserHandle mUser;
    private @ProfileNetworkPreference int mProfilePreference;

    private ProfileNetworkPreferenceDetails() {
    }

    private ProfileNetworkPreferenceDetails(Parcel in) {
        mUser = UserHandle.readFromParcel(in);
        mProfilePreference = in.readInt();
    }

    public int getProfileNetworkPreference() {
        return mProfilePreference;
    }
    public @Nullable UserHandle getProfileAsUser() {
        return mUser;
    }

    @Override
    public String toString() {
        return "ProfileNetworkPreferenceDetails{"
                + "mUser=" + getProfileAsUser()
                + "mProfilePreference=" + getProfileNetworkPreference()
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProfileNetworkPreferenceDetails that = (ProfileNetworkPreferenceDetails) o;
        if (mUser == null && that.mUser != null) return false;

        return mProfilePreference == that.mProfilePreference
                && (mUser == that.mUser || mUser.equals(that.mUser));
    }

    @Override
    public int hashCode() {
        return ((mProfilePreference * 17)
                + (Objects.hashCode(mUser) * 23));
    }

    /**
     * Builder used to create {@link ProfileNetworkPreferenceDetails} objects.
     * Specify the preferred Network preference
     */
    public static final class Builder {
        private ProfileNetworkPreferenceDetails mPreferences;

        /**
         * Constructor to populate the builder's values with an already built
         * {@link ProfileNetworkPreferenceDetails}.
         */
        public Builder() {
            mPreferences = new ProfileNetworkPreferenceDetails();
        }

        /**
         * set the network preference
         * @param preference  the desired network preference to use
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public Builder setProfileNetworkPreference(@ProfileNetworkPreference int preference) {
            mPreferences.mProfilePreference = preference;
            return this;
        }

        /**
         * Set the user profile corresponding to netwprk preferences.
         * @param user  profile
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public Builder setProfileAsUser(@NonNull @ProfileNetworkPreference UserHandle user) {
            mPreferences.mUser = user;
            return this;
        }

        /**
         * Build {@link ProfileNetworkPreferenceDetails} return the current Profile network
         * preferences.
         */
        @NonNull
        public ProfileNetworkPreferenceDetails build() {
            return mPreferences;
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
    public static final Creator<ProfileNetworkPreferenceDetails> CREATOR =
            new Creator<ProfileNetworkPreferenceDetails>() {
                @Override
                public ProfileNetworkPreferenceDetails[] newArray(int size) {
                    return new ProfileNetworkPreferenceDetails[size];
                }

                @Override
                public ProfileNetworkPreferenceDetails createFromParcel(
                        @NonNull android.os.Parcel in) {
                    return new ProfileNetworkPreferenceDetails(in);
                }
            };
}
