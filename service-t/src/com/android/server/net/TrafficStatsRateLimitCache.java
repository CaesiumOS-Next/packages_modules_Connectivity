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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.NetworkStats;

import java.time.Clock;
import java.util.HashMap;
import java.util.Objects;

/**
 * A thread-safe cache for storing and retrieving {@link NetworkStats.Entry} objects,
 * with an adjustable expiry duration to manage data freshness.
 */
class TrafficStatsRateLimitCache {
    private final Clock mClock;
    private final long mExpiryDurationMs;

    /**
     * Constructs a new {@link TrafficStatsRateLimitCache} with the specified expiry duration.
     *
     * @param clock The {@link Clock} to use for determining timestamps.
     * @param expiryDurationMs The expiry duration in milliseconds.
     */
    TrafficStatsRateLimitCache(@NonNull Clock clock, long expiryDurationMs) {
        mClock = clock;
        mExpiryDurationMs = expiryDurationMs;
    }

    private static class TrafficStatsCacheKey {
        public final int callerUid;
        @Nullable
        public final String iface;
        public final int uid;

        TrafficStatsCacheKey(int callerUid, @Nullable String iface, int uid) {
            this.callerUid = callerUid;
            this.iface = iface;
            this.uid = uid;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TrafficStatsCacheKey)) return false;
            TrafficStatsCacheKey that = (TrafficStatsCacheKey) o;
            return callerUid == that.callerUid && uid == that.uid
                    && Objects.equals(iface, that.iface);
        }

        @Override
        public int hashCode() {
            return Objects.hash(callerUid, iface, uid);
        }
    }

    private static class TrafficStatsCacheValue {
        public final long timestamp;
        @NonNull
        public final NetworkStats.Entry entry;

        TrafficStatsCacheValue(long timestamp, NetworkStats.Entry entry) {
            this.timestamp = timestamp;
            this.entry = entry;
        }
    }

    private final HashMap<TrafficStatsCacheKey, TrafficStatsCacheValue> mMap = new HashMap<>();

    /**
     * Retrieves a {@link NetworkStats.Entry} from the cache, associated with the given key.
     *
     * @param callerUid The caller UID to include in the cache key.
     * @param iface The interface name to include in the cache key. Null if not applicable.
     * @param uid The UID to include in the cache key. {@code UID_ALL} if not applicable.
     * @return The cached {@link NetworkStats.Entry}, or null if not found or expired.
     */
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

    /**
     * Stores a {@link NetworkStats.Entry} in the cache, associated with the given key.
     *
     * @param callerUid The caller UID to include in the cache key.
     * @param iface The interface name to include in the cache key. Null if not applicable.
     * @param uid The UID to include in the cache key. {@code UID_ALL} if not applicable.
     * @param entry The {@link NetworkStats.Entry} to store in the cache.
     */
    void put(int callerUid, String iface, int uid, @NonNull final NetworkStats.Entry entry) {
        Objects.requireNonNull(entry);
        final TrafficStatsCacheKey key = new TrafficStatsCacheKey(callerUid, iface, uid);
        synchronized (mMap) { // Synchronize for thread-safety
            mMap.put(key, new TrafficStatsCacheValue(mClock.millis(), entry));
        }
    }

    private boolean isExpired(long timestamp) {
        return mClock.millis() > timestamp + mExpiryDurationMs;
    }
}
