/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.net;

import android.annotation.Nullable;
import android.net.NetworkStats;

import java.time.Clock;
import java.util.HashMap;

class TrafficStatsRateLimitCache {
    private final Clock mClock;
    private final long mExpiryDurationMs; // Expiry duration in milliseconds


    TrafficStatsRateLimitCache(Clock clock, long expiryDurationMs) {
        mClock = clock;
        mExpiryDurationMs = expiryDurationMs;
    }

    private record TrafficStatsCacheKey(int callerUid, String iface, int uid) {
    }

    private record TrafficStatsCacheValue(long timestamp, NetworkStats.Entry entry) {
    }

    private final HashMap<TrafficStatsCacheKey, TrafficStatsCacheValue> mMap = new HashMap<>();

    @Nullable
    NetworkStats.Entry get(int callerUid, String iface, int uid) {
        final TrafficStatsCacheKey key = new TrafficStatsCacheKey(callerUid, iface, uid);
        synchronized (mMap) { // Synchronize for thread-safety
            final TrafficStatsCacheValue value = mMap.get(key);
            if (value != null && !isExpired(value.timestamp)) {
                return value.entry;
            } else {
                mMap.remove(key); // Remove expired entries
                return null;
            }
        }
    }

    void put(int callerUid, String iface, int uid, final NetworkStats.Entry entry) {
        final TrafficStatsCacheKey key = new TrafficStatsCacheKey(callerUid, iface, uid);
        synchronized (mMap) { // Synchronize for thread-safety
            mMap.put(key, new TrafficStatsCacheValue(mClock.millis(), entry));
        }
    }

    private boolean isExpired(long timestamp) {
        return mClock.millis() > timestamp + mExpiryDurationMs;
    }
}
