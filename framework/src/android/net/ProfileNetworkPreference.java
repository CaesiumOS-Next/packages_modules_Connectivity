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
    private final List<Integer> mIncludedUids;
    private final List<Integer> mExcludedUids;

    private ProfileNetworkPreference(int preference, List<Integer> includedUids,
            List<Integer> excludedUids) {
        mPreference = preference;
        if (includedUids != null) {
            mIncludedUids = new ArrayList<>(includedUids);
        } else {
            mIncludedUids = new ArrayList<>();
        }

        if (excludedUids != null) {
            mExcludedUids = new ArrayList<>(excludedUids);
        } else {
            mExcludedUids = new ArrayList<>();
        }
    }

    private ProfileNetworkPreference(Parcel in) {
        mPreference = in.readInt();
        mIncludedUids = in.readArrayList(Integer.class.getClassLoader(), Integer.class);
        mExcludedUids = in.readArrayList(Integer.class.getClassLoader(), Integer.class);
    }

    public int getPreference() {
        return mPreference;
    }

    /**
     * @return List of uids allowed for the profile preference.
     *      Null or empty would mean that this request applies to all uids in the profile.
     * If includedUids are nonempty, excluded uid list should be empty and vice versa.
     * {@see #getExcludedUids()}
     */
    public @NonNull List<Integer> getIncludedUids() {
        return new ArrayList<>(mIncludedUids);
    }

    /**
     * @return List of uids not allowed for the profile preference.
     * If includedUids are nonempty, excluded uid list should be empty and vice versa.
     * {@see #getIncludedUids()}
     */
    public @NonNull List<Integer> getExcludedUids() {
        return new ArrayList<>(mExcludedUids);
    }

    @Override
    public String toString() {
        return "ProfileNetworkPreference{"
                + "mPreference=" + getPreference()
                + "mIncludedUids=" + mIncludedUids.toString()
                + "mExcludedUids=" + mExcludedUids.toString()
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final ProfileNetworkPreference that = (ProfileNetworkPreference) o;
        return mPreference == that.mPreference
                && (Objects.equals(mIncludedUids, that.mIncludedUids))
                && (Objects.equals(mExcludedUids, that.mExcludedUids));
    }

    @Override
    public int hashCode() {
        return mPreference
                + (Objects.hashCode(mIncludedUids) * 11)
                + (Objects.hashCode(mExcludedUids) * 13);
    }

    /**
     * Builder used to create {@link ProfileNetworkPreference} objects.
     * Specify the preferred Network preference
     */
    public static final class Builder {
        private @ProfileNetworkPreferencePolicy int mPreference =
                PROFILE_NETWORK_PREFERENCE_DEFAULT;
        private List<Integer> mIncludedUids;
        private List<Integer> mExcludedUids;

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
         * {@see #setExcludedUids(List<Integer>)}
         * If includedUids are nonempty, excluded uid list should be empty and vice versa.
         * @param uids  list of uids that are allowed
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public Builder setIncludedUids(@Nullable List<Integer> uids) {
            if (uids != null) {
                mIncludedUids = new ArrayList<Integer>(uids);
            } else {
                mIncludedUids = new ArrayList<Integer>();
            }
            return this;
        }


        /**
         * This is a list of uids that are excluded for the profile perefence.
         * {@see #setIncludedUids(List<Integer>)}
         * If includedUids are nonempty, excluded uid list should be empty and vice versa.
         * @param uids  list of uids that are not allowed
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public Builder setExcludedUids(@Nullable List<Integer> uids) {
            if (uids != null) {
                mExcludedUids = new ArrayList<Integer>(uids);
            } else {
                mExcludedUids = new ArrayList<Integer>();
            }
            return this;
        }

         /**
         * Returns an instance of {@link ProfileNetworkPreference} created from the
         * fields set on this builder.
         */
        @NonNull
        public ProfileNetworkPreference  build() {
            if (mIncludedUids != null && mIncludedUids.size() > 0
                    && mExcludedUids != null && mExcludedUids.size() > 0) {
                throw new IllegalArgumentException("Both includedUids and excludedUids "
                        + "cannot be nonempty");
            }
            return new ProfileNetworkPreference(mPreference, mIncludedUids, mExcludedUids);
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
