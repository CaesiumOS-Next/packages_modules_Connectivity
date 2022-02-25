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
import static android.net.NetworkCapabilities.NET_ENTERPRISE_ID_1;
import static android.net.NetworkCapabilities.NET_ENTERPRISE_ID_5;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.ConnectivityManager.ProfileNetworkPreferenceBehavior;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Network preferences to be set for the user profile.
 * @hide
 */
@SystemApi(client = MODULE_LIBRARIES)
public final class ProfileNetworkPreference implements Parcelable {
    private final @ProfileNetworkPreferenceBehavior int mBehavior;
    private final @NetworkCapabilities.EnterpriseId int mEnterpriseId;
    private final List<Integer> mIncludedUids;
    private final List<Integer> mExcludedUids;

    private ProfileNetworkPreference(int behavior, List<Integer> includedUids,
            List<Integer> excludedUids,
            @NetworkCapabilities.EnterpriseId int enterpriseId) {
        mBehavior = behavior;
        mEnterpriseId = enterpriseId;
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
        mBehavior = in.readInt();
        mIncludedUids = in.readArrayList(Integer.class.getClassLoader());
        mExcludedUids = in.readArrayList(Integer.class.getClassLoader());
        mEnterpriseId = in.readInt();
    }

    /*
     * Get the behavior applied by this profile network preference.
     *
     * See the documentation for the individual behaviors for a description of the supported
     * behaviors.
     */
    public int getBehavior() {
        return mBehavior;
    }

    /**
     * Get the list of UIDs subject to this preference.
     *
     * Included UIDs and Excluded UIDs can't both be non-empty.
     * if both are empty, it means this request applies to all uids in the user profile.
     * if included is not empty, then only included UIDs are applied.
     * if excluded is not empty, then it is all uids in the user profile except these UIDs.
     * @return List of uids included for the profile preference.
     * {@see #getExcludedUids()}
     */
    public @NonNull List<Integer> getIncludedUids() {
        return new ArrayList<>(mIncludedUids);
    }

    /**
     * Get the list of UIDS excluded from this preference.
     *
     * <ul>Included UIDs and Excluded UIDs can't both be non-empty.</ul>
     * <ul>If both are empty, it means this request applies to all uids in the user profile.</ul>
     * <ul>If included is not empty, then only included UIDs are applied.</ul>
     * <ul>If excluded is not empty, then it is all uids in the user profile except these UIDs.</ul>
     * @return List of uids not included for the profile preference.
     * {@see #getIncludedUids()}
     */
    public @NonNull List<Integer> getExcludedUids() {
        return new ArrayList<>(mExcludedUids);
    }

    /**
     * Get preference enterprise identifier.
     *
     * Preference enterprise identifier will be used to create different network preferences
     * within enterprise preference category.
     * Valid values starts from PROFILE_NETWORK_PREFERENCE_ENTERPRISE_ID_1 to
     * NetworkCapabilities.NET_ENTERPRISE_ID_5.
     * Preference identifier is not applicable if preference is set as
     * PROFILE_NETWORK_PREFERENCE_DEFAULT. Default value is
     * NetworkCapabilities.NET_ENTERPRISE_ID_1.
     * @return Preference enterprise identifier.
     *
     */
    public @NetworkCapabilities.EnterpriseId int getEnterpriseId() {
        return mEnterpriseId;
    }

    @Override
    public String toString() {
        return "ProfileNetworkPreference{"
                + "mPreference=" + getBehavior()
                + "mIncludedUids=" + mIncludedUids.toString()
                + "mExcludedUids=" + mExcludedUids.toString()
                + "mPreferenceEnterpriseId=" + mEnterpriseId
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final ProfileNetworkPreference that = (ProfileNetworkPreference) o;
        return mBehavior == that.mBehavior
                && (Objects.equals(mIncludedUids, that.mIncludedUids))
                && (Objects.equals(mExcludedUids, that.mExcludedUids))
                && mEnterpriseId == that.mEnterpriseId;
    }

    @Override
    public int hashCode() {
        return mBehavior
                + mEnterpriseId * 2
                + (Objects.hashCode(mIncludedUids) * 11)
                + (Objects.hashCode(mExcludedUids) * 13);
    }

    /**
     * Builder used to create {@link ProfileNetworkPreference} objects.
     */
    public static final class Builder {
        private @ProfileNetworkPreferenceBehavior int mBehavior =
                PROFILE_NETWORK_PREFERENCE_DEFAULT;
        private @NonNull List<Integer> mIncludedUids = new ArrayList<>();
        private @NonNull List<Integer> mExcludedUids = new ArrayList<>();
        private int mEnterpriseId;

        /**
         * Constructs an empty Builder with PROFILE_NETWORK_PREFERENCE_DEFAULT behavior
         */
        public Builder() {}

        /**
         * Set the profile network preference behavior.
         *
         * See the documentation for the individual behaviors for a description of the supported
         * behaviors. Default value is PROFILE_NETWORK_PREFERENCE_DEFAULT.
         * @param behavior the desired behavior
         * @return This builder
         */
        @NonNull
        public Builder setBehavior(@ProfileNetworkPreferenceBehavior int behavior) {
            mBehavior = behavior;
            return this;
        }

        /**
         * Set the list of uids for which this profile preference is set.
         *
         * Null means that this preference applies to all uids in the profile.
         * {@see #setExcludedUids(List<Integer>)}
         * Included UIDs and Excluded UIDs can't both be non-empty.
         * If both are empty, it means this request applies to all uids in the user profile.
         * If included is not empty, then only included UIDs are applied.
         * If excluded is not empty, then it is all uids in the user profile except these UIDs.
         * @param uids list of uids that are included
         * @return This builder
         */
        @NonNull
        public Builder setIncludedUids(@Nullable List<Integer> uids) {
            if (uids != null) {
                mIncludedUids = new ArrayList<>(uids);
            } else {
                mIncludedUids = new ArrayList<>();
            }
            return this;
        }


        /**
         * Set the list of uids that are excluded for the profile preference.
         *
         * {@see #setIncludedUids(List<Integer>)}
         * Included UIDs and Excluded UIDs can't both be non-empty.
         * if both are empty, it means this request applies to all uids in the user profile.
         * if included is not empty, then only included UIDs are applied.
         * if excluded is not empty, then it is all uids in the user profile except these UIDs.
         * @param uids  list of uids that are not included
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public Builder setExcludedUids(@Nullable List<Integer> uids) {
            if (uids != null) {
                mExcludedUids = new ArrayList<>(uids);
            } else {
                mExcludedUids = new ArrayList<>();
            }
            return this;
        }

        /**
         * Check if the given preference enterprise identifier is valid
         *
         * Valid values starts from PROFILE_NETWORK_PREFERENCE_ENTERPRISE_ID_1 to
         * NetworkCapabilities.NET_ENTERPRISE_ID_5.
         * @return True if valid else false
         * @hide
         */
        private boolean isEnterpriseIdentifierValid(
                @NetworkCapabilities.EnterpriseId int identifier) {
            if ((identifier >= NET_ENTERPRISE_ID_1)
                    && (identifier <= NET_ENTERPRISE_ID_5)) {
                return true;
            }
            return false;
        }

        /**
         * Returns an instance of {@link ProfileNetworkPreference} created from the
         * fields set on this builder.
         */
        @NonNull
        public ProfileNetworkPreference build() {
            if (mIncludedUids.size() > 0 && mExcludedUids.size() > 0) {
                throw new IllegalArgumentException("Both includedUids and excludedUids "
                        + "cannot be nonempty");
            }

            if (((mBehavior != PROFILE_NETWORK_PREFERENCE_DEFAULT)
                    && (!isEnterpriseIdentifierValid(mEnterpriseId)))
                    || ((mBehavior == PROFILE_NETWORK_PREFERENCE_DEFAULT)
                    && (mEnterpriseId != 0))) {
                throw new IllegalStateException("Invalid preference enterprise identifier");
            }
            return new ProfileNetworkPreference(mBehavior, mIncludedUids,
                    mExcludedUids, mEnterpriseId);
        }

        /**
         * Set the preference enterprise identifier.
         *
         * This enterprise identifier will be used to create different network preferences
         * within enterprise preference category.
         * Valid values range from NetworkCapabilities.NET_ENTERPRISE_ID_1 to
         * NetworkCapabilities.NET_ENTERPRISE_ID_5.
         * The enterprise identifier is not applicable if the preference is set as
         * PROFILE_NETWORK_PREFERENCE_DEFAULT. The default value is
         * NetworkCapabilities.NET_ENTERPRISE_ID_1.
         *
         * @param enterpriseId enterprise ID
         * @return This builder
         */
        @NonNull
        public Builder setEnterpriseId(@NetworkCapabilities.EnterpriseId int enterpriseId) {
            mEnterpriseId = enterpriseId;
            return this;
        }
    }

    @Override
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        dest.writeInt(mBehavior);
        dest.writeList(mIncludedUids);
        dest.writeList(mExcludedUids);
        dest.writeInt(mEnterpriseId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<ProfileNetworkPreference> CREATOR =
            new Creator<>() {
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
