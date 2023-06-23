/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.connectivity;

import android.annotation.NonNull;
import android.net.UidRange;
import android.util.ArraySet;

/**
 * Record the session and UidRange for a VPN preference.
 */
public class UserNetworkPreferenceInfo implements NetworkPreferenceList.NetworkPreference<String> {
    // The default, behave as if no pref
    public static final int USER_PREFERENCE_AUTOMATIC = 0;
    // This package gets no networking at all
    public static final int USER_PREFERENCE_NO_NETWORK = 1;

    @NonNull
    public final int mUid;
    public final int mMode;

    public UserNetworkPreferenceInfo(final int uid, final int mode) {
        mUid = uid;
        mMode = mode;
    }

    @Override
    public boolean isCancel() {
        return mMode == USER_PREFERENCE_AUTOMATIC;
    }

    @Override
    @NonNull
    public String getKey() {
        return Integer.toString(mUid);
    }

    /** toString */
    public String toString() {
        return "[UserNetworkPreference uid = " + mUid + " mode = " + mMode + "]";
    }
}
