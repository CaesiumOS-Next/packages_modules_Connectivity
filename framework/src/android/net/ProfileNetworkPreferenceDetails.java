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
import android.net.ConnectivityManager.ProfileNetworkPreference;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

import javax.sip.InvalidArgumentException;

/**
 * Network preferences to be set for the user profile
 * {@link ProfileNetworkPreference}.
 * @hide
 */
@SystemApi(client = MODULE_LIBRARIES)
public final class ProfileNetworkPreferenceDetails implements Parcelable {
    private UserHandle mUser;
    private @ProfileNetworkPreference int mProfilePreference;
    private boolean mAllowFallback;
    private List<Integer> mUids;

    /* preference sub-level. valid values starts from
     * PROFILE_NETWORK_PREFERENCE_SUB_LEVEL_DEFAULT to
     * PROFILE_NETWORK_PREFERENCE_SUB_LEVEL_MAX. */
    private int mPreferenceSubLevel;

    /**
     * Preference sub level default value
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int PROFILE_NETWORK_PREFERENCE_SUB_LEVEL_DEFAULT = 1;

    /**
     * Preference sub level maximum value
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int PROFILE_NETWORK_PREFERENCE_SUB_LEVEL_MAX = 5;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            PROFILE_NETWORK_PREFERENCE_SUB_LEVEL_DEFAULT,
            PROFILE_NETWORK_PREFERENCE_SUB_LEVEL_MAX
    })
    public @interface ProfileNetworkPreferenceSubLevel {
    }

    private ProfileNetworkPreferenceDetails() {
        mUids = new ArrayList<>();
    }

    private ProfileNetworkPreferenceDetails(Parcel in) {
        mUser = UserHandle.readFromParcel(in);
        mProfilePreference = in.readInt();
        mAllowFallback = in.readBoolean();
        mUids = in.readArrayList(Integer.class.getClassLoader());
        mPreferenceSubLevel = in.readInt();
    }

    public int getProfileNetworkPreference() {
        return mProfilePreference;
    }
    public @Nullable UserHandle getProfileAsUser() {
        return mUser;
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
        return mUids;
    }

    /**
     * @return preference sub-level. valid values starts from
     * PROFILE_NETWORK_PREFERENCE_SUB_LEVEL_DEFAULT to
     * PROFILE_NETWORK_PREFERENCE_SUB_LEVEL_MAX.
     * preference sub-level is applicable only if preference is set as
     * PROFILE_NETWORK_PREFERENCE_ENTERPRISE
     *
     */
    public int getPreferenceSubLevel() {
        return mPreferenceSubLevel;
    }

    @Override
    public String toString() {
        return "ProfileNetworkPreferenceDetails{"
                + "mUser=" + getProfileAsUser()
                + "mProfilePreference=" + getProfileNetworkPreference()
                + "mAllowFallback=" + mAllowFallback
                + "mUids=" + mUids.toString()
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProfileNetworkPreferenceDetails that = (ProfileNetworkPreferenceDetails) o;
        if (mUser == null && that.mUser != null) return false;
        if (mUids == null && that.mUids != null) return false;

        return mProfilePreference == that.mProfilePreference
                && mAllowFallback == that.mAllowFallback
                && (mUser == that.mUser || mUser.equals(that.mUser))
                && (mUids == that.mUids || mUids.equals(that.mUids));
    }

    @Override
    public int hashCode() {
        return ((mProfilePreference * 17)
                + (Objects.hashCode(mUser) * 23)
                + (Objects.hashCode(mAllowFallback) * 29)
                + (Objects.hashCode(mUids) * 31));
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
            mPreferences.mAllowFallback = allowFallback;
            return this;
        }

        /**
         * This is a list of uids for which profile perefence is set.
         * Null would mean that this preference applies to all uids in the profile.
         * @param uids  list of uids
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public Builder setUids(@NonNull List<Integer> uids) {
            mPreferences.mUids = new ArrayList<Integer>(uids);
            return this;
        }

        /**
         * Set the preference sub-level. valid values starts from
         * PROFILE_NETWORK_PREFERENCE_SUB_LEVEL_DEFAULT to
         * PROFILE_NETWORK_PREFERENCE_SUB_LEVEL_MAX.
         * preference sub-level is applicable only if preference is set as
         * PROFILE_NETWORK_PREFERENCE_ENTERPRISE.
         * @param preferenceSubLevel  preference sub level
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public Builder setPreferenceSubLevel(int preferenceSubLevel) {
            mPreferences.mPreferenceSubLevel = preferenceSubLevel;
            return this;
        }

        /**
         * Build {@link ProfileNetworkPreferenceDetails} return the current Profile network
         * preferences.
         * @throws IllegalArgumentException
         */
        @NonNull
        public ProfileNetworkPreferenceDetails build() {
            if (mPreferences.mProfilePreference == PROFILE_NETWORK_PREFERENCE_ENTERPRISE) {
                if ((mPreferences.mPreferenceSubLevel
                        < PROFILE_NETWORK_PREFERENCE_SUB_LEVEL_DEFAULT)
                        || (mPreferences.mPreferenceSubLevel
                        > PROFILE_NETWORK_PREFERENCE_SUB_LEVEL_MAX)) {
                    throw new IllegalArgumentException("Invalid preference sub-level");
                }
            }
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
