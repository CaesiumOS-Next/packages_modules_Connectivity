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

package com.android.server.connectivity;

import android.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A generic data class containing network preferences.
 * @param <K> The type of key in T
 * @param <T> The type of preference stored in this preference list
 */
public class NetworkPreferenceList<K, T extends NetworkPreferenceList.NetworkPreference<K>> {
    /**
     * A network preference
     * @param <K> the type of key by which this preference is indexed. A NetworkPreferenceList
     *            can have multiple preferences associated with this key, but has methods to
     *            work on the keys.
     */
    public interface NetworkPreference<K> {
        /**
         * Whether this preference codes for cancelling the preference for this key
         */
        boolean isCancel();
        /** The key */
        K getKey();
    }

    @NonNull public final List<T> preferences;

    public NetworkPreferenceList() {
        preferences = Collections.EMPTY_LIST;
    }

    private NetworkPreferenceList(@NonNull final List<T> list) {
        preferences = Collections.unmodifiableList(list);
    }

    /**
     * Returns a new object consisting of this object plus the passed preference.
     *
     * Passing a Preference object containing a null capabilities object is equivalent
     * to removing the preference for this user.
     */
    public NetworkPreferenceList<K, T> plus(@NonNull final T pref) {
        final ArrayList<T> newPrefs = new ArrayList<>(preferences);
        if (!pref.isCancel()) {
            newPrefs.add(pref);
        }
        return new NetworkPreferenceList<>(newPrefs);
    }

    /**
     * Remove all preferences corresponding to a key.
     */
    public NetworkPreferenceList<K, T> minus(@NonNull final K key) {
        final ArrayList<T> newPrefs = new ArrayList<>();
        for (final T existingPref : preferences) {
            if (!existingPref.getKey().equals(key)) {
                newPrefs.add(existingPref);
            }
        }
        return new NetworkPreferenceList<>(newPrefs);
    }

    public boolean isEmpty() {
        return preferences.isEmpty();
    }
}
