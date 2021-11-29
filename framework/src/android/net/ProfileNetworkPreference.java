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
import android.annotation.SystemApi;
import android.net.ConnectivityManager.ProfileNetworkPreferencePolicy;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Network preferences to be set for the user profile
 * {@link ProfileNetworkPreferencePolicy}.
 * @hide
 */
@SystemApi(client = MODULE_LIBRARIES)
public final class ProfileNetworkPreference implements Parcelable {
    private final @ProfileNetworkPreferencePolicy int mPreference;
    private final List<Integer> mAllowUids;
    private final List<Integer> mDisallowUids;

    private ProfileNetworkPreference(int preference, List<Integer> allowUids,
            List<Integer> disallowUids) {
        mPreference = preference;
        if (allowUids != null) {
            mAllowUids = new ArrayList<>(allowUids);
        } else {
            mAllowUids = new ArrayList<>();
        }

        if (disallowUids != null) {
            mDisallowUids = new ArrayList<>(disallowUids);
        } else {
            mDisallowUids = new ArrayList<>();
        }
    }

    private ProfileNetworkPreference(Parcel in) {
        mPreference = in.readInt();
        mAllowUids = in.readArrayList(Integer.class.getClassLoader(), Integer.class);
        mDisallowUids = in.readArrayList(Integer.class.getClassLoader(), Integer.class);
    }

    public int getPreference() {
        return mPreference;
    }

    /**
     * @return List of uids allowed for the profile preference.
     *      Null or empty would mean that this request applies to all uids in the profile.
     * If allowUids are nonempty, disallow uid list should be empty and vice versa.
     * {@see #getDisallowUids()}
     */
    public @NonNull List<Integer> getAllowUids() {
        return new ArrayList<>(mAllowUids);
    }

    /**
     * @return List of uids not allowed for the profile preference.
     * If allowUids are nonempty, disallow uid list should be empty and vice versa.
     * {@see #getAllowUids()}
     */
    public @NonNull List<Integer> getDisallowUids() {
        return new ArrayList<>(mDisallowUids);
    }

    @Override
    public String toString() {
        return "ProfileNetworkPreference{"
                + "mPreference=" + getPreference()
                + "mAllowUids=" + mAllowUids.toString()
                + "mDisallowUids=" + mDisallowUids.toString()
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final ProfileNetworkPreference that = (ProfileNetworkPreference) o;
        return mPreference == that.mPreference
                && (Objects.equals(mAllowUids, that.mAllowUids))
                && (Objects.equals(mDisallowUids, that.mDisallowUids));
    }

    @Override
    public int hashCode() {
        return mPreference
                + (Objects.hashCode(mAllowUids) * 11)
                + (Objects.hashCode(mDisallowUids) * 13);
    }

    /**
     * Builder used to create {@link ProfileNetworkPreference} objects.
     * Specify the preferred Network preference
     */
    public static final class Builder {
        private @ProfileNetworkPreferencePolicy int mPreference =
                PROFILE_NETWORK_PREFERENCE_DEFAULT;
        private List<Integer> mAllowUids;
        private List<Integer> mDisallowUids;

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
         * This is a list of uids for which profile perefence is set.
         * Null would mean that this preference applies to all uids in the profile.
         * {@see #setDisallowUids(List<Integer>)}
         * If allowUids are nonempty, disallow uid list should be empty and vice versa.
         * @param uids  list of uids that are allowed
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public Builder setAllowUids(@Nullable List<Integer> uids) {
            mAllowUids = new ArrayList<Integer>(uids);
            return this;
        }


        /**
         * This is a list of uids that are not allowed for the profile perefence.
         * {@see #setAllowUids(List<Integer>)}
         * If allowUids are nonempty, disallow uid list should be empty and vice versa.
         * @param uids  list of uids that are not allowed
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public Builder setDisallowUids(@Nullable List<Integer> uids) {
            mDisallowUids = new ArrayList<Integer>(uids);
            return this;
        }

         /**
         * Returns an instance of {@link ProfileNetworkPreference} created from the
         * fields set on this builder.
         */
        @NonNull
        public ProfileNetworkPreference  build() {
            return new ProfileNetworkPreference(mPreference, mAllowUids, mDisallowUids);
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
