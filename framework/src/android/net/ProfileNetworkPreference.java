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
import static android.net.ConnectivityManager.PROFILE_NETWORK_PREFERENCE_ENTERPRISE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.ConnectivityManager.ProfileNetworkPreferencePolicy;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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
    private boolean mAllowFallback;
    private List<Integer> mUids;

    /**
     * Enterprise preference sublevel. Valid values starts from
     * PROFILE_NETWORK_PREFERENCE_ENTERPRISE_SUB_LEVEL_1 to
     * PROFILE_NETWORK_PREFERENCE_ENTERPRISE_SUB_LEVEL_5.
     */
    private int mPreferenceEnterpriseSubLevel;

    /**
     * enterprise capability sub level 1
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int PROFILE_NETWORK_PREFERENCE_ENTERPRISE_SUB_LEVEL_1 = 1;

    /**
     * enterprise capability sub level 2
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int PROFILE_NETWORK_PREFERENCE_ENTERPRISE_SUB_LEVEL_2 = 2;

    /**
     * enterprise capability sub level 3
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int PROFILE_NETWORK_PREFERENCE_ENTERPRISE_SUB_LEVEL_3 = 3;

    /**
     * enterprise capability sub level 4
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int PROFILE_NETWORK_PREFERENCE_ENTERPRISE_SUB_LEVEL_4 = 4;

    /**
     * enterprise capability sub level 5
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int PROFILE_NETWORK_PREFERENCE_ENTERPRISE_SUB_LEVEL_5 = 5;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "PROFILE_NETWORK_PREFERENCE_ENTERPRISE_SUB_LEVEL_" }, value = {
            PROFILE_NETWORK_PREFERENCE_ENTERPRISE_SUB_LEVEL_1,
            PROFILE_NETWORK_PREFERENCE_ENTERPRISE_SUB_LEVEL_2,
            PROFILE_NETWORK_PREFERENCE_ENTERPRISE_SUB_LEVEL_3,
            PROFILE_NETWORK_PREFERENCE_ENTERPRISE_SUB_LEVEL_4,
            PROFILE_NETWORK_PREFERENCE_ENTERPRISE_SUB_LEVEL_5,
    })

    public @interface ProfileNetworkEnterpriseSubLevel {
    }

    private ProfileNetworkPreference(int preference, boolean allowFallback, List<Integer> uids,
            int preferenceEnterpriseSubLevel) {
        mPreference = preference;
        mAllowFallback = allowFallback;
        mUids = uids;
        mPreferenceEnterpriseSubLevel = preferenceEnterpriseSubLevel;
    }

    private ProfileNetworkPreference(Parcel in) {
        mPreference = in.readInt();
        mAllowFallback = in.readBoolean();
        mUids = in.readArrayList(Integer.class.getClassLoader(), Integer.class);
        mPreferenceEnterpriseSubLevel = in.readInt();
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

    /**
     * @return List of uids applicable for the profile preference.
     *      * Null would mean that this request applies to all uids in the profile.
     */
    public @NonNull List<Integer> getUids() {
        return new ArrayList<>(mUids);
    }

    /**
     * @return preference enterprise sub-level. valid values starts from
     * PROFILE_NETWORK_PREFERENCE_ENTERPRISE_SUB_LEVEL_1 to
     * PROFILE_NETWORK_PREFERENCE_ENTERPRISE_SUB_LEVEL_5.
     * preference sub-level is applicable only if preference is set as
     * PROFILE_NETWORK_PREFERENCE_ENTERPRISE
     *
     */
    public int getPreferenceEnterpriseSubLevel() {
        return mPreferenceEnterpriseSubLevel;
    }

    @Override
    public String toString() {
        return "ProfileNetworkPreference{"
                + "mPreference=" + getPreference()
                + "mAllowFallback=" + isAllowFallback()
                + "mUids=" + mUids.toString()
                + "mPreferenceEnterpriseSubLevel=" + mPreferenceEnterpriseSubLevel
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final ProfileNetworkPreference that = (ProfileNetworkPreference) o;
        return mPreference == that.mPreference
                && mAllowFallback == that.mAllowFallback
                && mPreferenceEnterpriseSubLevel == that.mPreferenceEnterpriseSubLevel
                && (mUids == that.mUids || mUids.equals(that.mUids));
    }

    @Override
    public int hashCode() {
        return ((mPreference * 17)
                + (Objects.hashCode(mAllowFallback) * 19)
                + (Objects.hashCode(mUids) * 23)
                + mPreferenceEnterpriseSubLevel * 29);
    }

    /**
     * Builder used to create {@link ProfileNetworkPreference} objects.
     * Specify the preferred Network preference
     */
    public static final class Builder {
        private @ProfileNetworkPreferencePolicy int mPreference =
                PROFILE_NETWORK_PREFERENCE_ENTERPRISE_SUB_LEVEL_1;
        private boolean mAllowFallback = true;
        private List<Integer> mUids;
        private int mPreferenceEnterpriseSubLevel;

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
         * This is a list of uids for which profile perefence is set.
         * Null would mean that this preference applies to all uids in the profile.
         * @param uids  list of uids
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public Builder setUids(@Nullable List<Integer> uids) {
            mUids = new ArrayList<Integer>(uids);
            return this;
        }

         /**
         * Returns an instance of {@link ProfileNetworkPreference} created from the
         * fields set on this builder.
         */
        @NonNull
        public ProfileNetworkPreference  build() {
            if (mPreference == PROFILE_NETWORK_PREFERENCE_ENTERPRISE) {
                if ((mPreferenceEnterpriseSubLevel
                        < PROFILE_NETWORK_PREFERENCE_ENTERPRISE_SUB_LEVEL_1)
                        || (mPreferenceEnterpriseSubLevel
                        > PROFILE_NETWORK_PREFERENCE_ENTERPRISE_SUB_LEVEL_5)) {
                    throw new IllegalArgumentException("Invalid preference sub-level");
                }
            }
            return new ProfileNetworkPreference(mPreference, mAllowFallback, mUids,
                    mPreferenceEnterpriseSubLevel);
        }

        /**
         * Set the preference enterprise sub-level. valid values starts from
         * PROFILE_NETWORK_PREFERENCE_ENTERPRISE_SUB_LEVEL_1 to
         * PROFILE_NETWORK_PREFERENCE_ENTERPRISE_SUB_LEVEL_5.
         * preference sub-level is applicable only if preference is set as
         * PROFILE_NETWORK_PREFERENCE_ENTERPRISE.
         * @param preferenceSubLevel  preference sub level
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public Builder setPreferenceEnterpriseSubLevel(int preferenceSubLevel) {
            mPreferenceEnterpriseSubLevel = preferenceSubLevel;
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
