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

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkSpecifier;
import android.net.TelephonyNetworkSpecifier;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.metrics.DailykeepaliveInfoReported;
import com.android.metrics.DurationForNumOfKeepalive;
import com.android.metrics.DurationPerNumOfKeepalive;
import com.android.metrics.KeepaliveLifetimeForCarrier;
import com.android.metrics.KeepaliveLifetimePerCarrier;
import com.android.net.module.util.BitUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// TODO(b/273451360): Also track DailykeepaliveInfoReported
/**
 * Tracks carrier and duration metrics of automatic on/off keepalives.
 *
 * <p>This class follows AutomaticOnOffKeepaliveTracker closely and its on*Keepalive methods needs
 * to be called in a timely manner to keep the metrics accurate. It is also not thread-safe and all
 * public methods must be called by the same thread, namely the ConnectivityService handler thread.
 */
public class KeepaliveStatsTracker {
    private static final String TAG = KeepaliveStatsTracker.class.getSimpleName();

    private final ConnectivityManager mConnectivityManager;
    private final TelephonyManager mTelephonyManager;

    private final Dependencies mDependencies;

    private static final class KeepaliveCarrierStats {
        // The carrier ID for a keepalive, or TelephonyManager.UNKNOWN_CARRIER_ID(-1) if not set.
        public final int carrierId;
        // The transport types of the underlying network for each keepalive. A network may include
        // multiple transport types. Each transfer type is represented by a different bit, defined
        // in NetworkCapabilities
        public final int transportTypes;
        // The keepalive interval in millis.
        public final int intervalMsec;

        // The total time since the keepalive is started until it is stopped.
        private int mLifetimeMsec = 0;
        // The total time the keepalive is active (not suspended).
        private int mActiveLifetimeMsec = 0;

        // A timestamp of the most recent time the lifetime metrics was updated.
        private long mTimestampSinceLastUpdateStats;

        // A flag to indicate if the keepalive is active.
        private boolean mActive = true;
        // A flag to indicate if the keepalive has been stopped.
        private boolean mStopped = false;

        public int getLifetimeMsec() {
            return mLifetimeMsec;
        }

        public int getActiveLifetimeMsec() {
            return mActiveLifetimeMsec;
        }

        public boolean isActive() {
            return mActive;
        }

        public boolean isStopped() {
            return mStopped;
        }

        KeepaliveCarrierStats(
                int carrierId, int transportTypes, int intervalSeconds, long timeNow) {
            this.carrierId = carrierId;
            this.transportTypes = transportTypes;
            this.intervalMsec = intervalSeconds * 1000;
            mTimestampSinceLastUpdateStats = timeNow;
        }

        private void updateLifetimeStats(long timeNow) {
            if (mStopped) return;

            final int durationIncrease = (int) (timeNow - mTimestampSinceLastUpdateStats);
            mLifetimeMsec += durationIncrease;
            if (mActive) mActiveLifetimeMsec += durationIncrease;

            mTimestampSinceLastUpdateStats = timeNow;
        }

        // Updates the lifetime metrics to the given time and sets the active state. This should be
        // called whenever the active state of the keepalive changes.
        public void updateLiftimeStatsAndSetActive(long timeNow, boolean active) {
            updateLifetimeStats(timeNow);
            mActive = active;
        }

        // Updates the lifetime metrics to the given time and marks the keepalive as stopped.
        // This should be called only once at the moment the keepalive is stopped.
        public void updateLiftimeStatsAndStop(long timeNow) {
            updateLifetimeStats(timeNow);
            mStopped = true;
            mActive = false;
        }

        // Resets the lifetime metrics but does not reset the active/stopped state of the keepalive.
        public void resetLifetimeStats() {
            mLifetimeMsec = 0;
            mActiveLifetimeMsec = 0;
        }
    }

    // List of duration stats metric where the index is the number of concurrent keepalives.
    // Each DurationForNumOfKeepalive message stores a registered duration and an active duration.
    // Registered duration is the total time spent with mNumRegisteredKeepalive == index.
    // Active duration is the total time spent with mNumActiveKeepalive == index.
    private final List<DurationForNumOfKeepalive.Builder> mDurationPerNumOfKeepalive =
            new ArrayList<>();
    // Map of keepalives identified by the pair of <network, slot> to their lifetime stats.
    private final Map<Pair<Network, Integer>, KeepaliveCarrierStats>
            mKeepaliveCarrierStatsPerKeepalive = new HashMap<>();

    private int mNumRegisteredKeepalive = 0;
    private int mNumActiveKeepalive = 0;

    // A timestamp of the most recent time the duration metrics was updated.
    private long mTimestampSinceLastUpdateDurations;

    /** Dependency class */
    @VisibleForTesting
    public static class Dependencies {
        // Returns a timestamp with the time base of SystemClock.uptimeMillis to keep durations
        // relative to start time and avoid timezone change.
        public long getUptimeMillis() {
            return SystemClock.uptimeMillis();
        }
    }

    public KeepaliveStatsTracker(Context context) {
        this(context, new Dependencies());
    }

    @VisibleForTesting
    public KeepaliveStatsTracker(Context context, Dependencies dependencies) {
        mConnectivityManager = context.getSystemService(ConnectivityManager.class);
        mTelephonyManager = context.getSystemService(TelephonyManager.class);
        mDependencies = dependencies;
        mTimestampSinceLastUpdateDurations = mDependencies.getUptimeMillis();
    }

    /** Ensures the list of duration metrics is large enough for number of registered keepalives. */
    private void ensureDurationPerNumOfKeepaliveSize() {
        if (mNumActiveKeepalive < 0 || mNumRegisteredKeepalive < 0) {
            throw new IllegalStateException(
                    "Number of active or registered keepalives is negative");
        }
        if (mNumActiveKeepalive > mNumRegisteredKeepalive) {
            throw new IllegalStateException(
                    "Number of active keepalives greater than registered keepalives");
        }

        while (mDurationPerNumOfKeepalive.size() <= mNumRegisteredKeepalive) {
            final DurationForNumOfKeepalive.Builder durationForNumOfKeepalive =
                    DurationForNumOfKeepalive.newBuilder();
            durationForNumOfKeepalive.setNumOfKeepalive(mDurationPerNumOfKeepalive.size());
            durationForNumOfKeepalive.setKeepaliveRegisteredDurationsMsec(0);
            durationForNumOfKeepalive.setKeepaliveActiveDurationsMsec(0);

            mDurationPerNumOfKeepalive.add(durationForNumOfKeepalive);
        }
    }

    /**
     * Updates the durations metrics to the given time. This should always be called before making a
     * change to mNumRegisteredKeepalive or mNumActiveKeepalive to keep the duration metrics
     * correct.
     *
     * @param timeNow a timestamp obtained using Dependencies.getUptimeMillis
     */
    private void updateDurationsPerNumOfKeepalive(long timeNow) {
        if (mDurationPerNumOfKeepalive.size() < mNumRegisteredKeepalive) {
            Log.e(TAG, "Unexpected jump in number of registered keepalive");
        }
        ensureDurationPerNumOfKeepaliveSize();

        final int durationIncrease = (int) (timeNow - mTimestampSinceLastUpdateDurations);
        final DurationForNumOfKeepalive.Builder durationForNumOfRegisteredKeepalive =
                mDurationPerNumOfKeepalive.get(mNumRegisteredKeepalive);

        durationForNumOfRegisteredKeepalive.setKeepaliveRegisteredDurationsMsec(
                durationForNumOfRegisteredKeepalive.getKeepaliveRegisteredDurationsMsec()
                        + durationIncrease);

        final DurationForNumOfKeepalive.Builder durationForNumOfActiveKeepalive =
                mDurationPerNumOfKeepalive.get(mNumActiveKeepalive);

        durationForNumOfActiveKeepalive.setKeepaliveActiveDurationsMsec(
                durationForNumOfActiveKeepalive.getKeepaliveActiveDurationsMsec()
                        + durationIncrease);

        mTimestampSinceLastUpdateDurations = timeNow;
    }

    private int getCarrierId(Network network) {
        if (mTelephonyManager != null) {
            // Try to get the correct subscription id.
            if (mConnectivityManager != null) {
                final NetworkCapabilities networkCapabilities =
                        mConnectivityManager.getNetworkCapabilities(network);
                if (networkCapabilities != null) {
                    final NetworkSpecifier networkSpecifier =
                            networkCapabilities.getNetworkSpecifier();
                    if (networkSpecifier instanceof TelephonyNetworkSpecifier) {
                        final int subId =
                                ((TelephonyNetworkSpecifier) networkSpecifier).getSubscriptionId();
                        final TelephonyManager tmPerSubId =
                                mTelephonyManager.createForSubscriptionId(subId);
                        return tmPerSubId.getSimCarrierId();
                    }
                }
            }

            return mTelephonyManager.getSimCarrierId();
        }
        return TelephonyManager.UNKNOWN_CARRIER_ID;
    }

    private int getTransportTypes(Network network) {
        if (mConnectivityManager != null) {
            final NetworkCapabilities networkCapabilities =
                    mConnectivityManager.getNetworkCapabilities(network);
            if (networkCapabilities != null) {
                // Cast to int to follow proto definition.
                return (int) BitUtils.packBits(networkCapabilities.getTransportTypes());
            }
        }
        // No transport type
        return 0;
    }

    /** Inform the KeepaliveStatsTracker a keepalive has just started and is active. */
    public void onStartKeepalive(Network network, int slot, int intervalSeconds) {
        if (mKeepaliveCarrierStatsPerKeepalive.containsKey(new Pair<>(network, slot))) {
            Log.e(TAG, "Attempt to start keepalive stats on a known network, slot pair");
            return;
        }
        final long timeNow = mDependencies.getUptimeMillis();
        updateDurationsPerNumOfKeepalive(timeNow);

        mNumRegisteredKeepalive++;
        mNumActiveKeepalive++;

        mKeepaliveCarrierStatsPerKeepalive.put(
                new Pair<>(network, slot),
                new KeepaliveCarrierStats(
                        getCarrierId(network),
                        getTransportTypes(network),
                        intervalSeconds,
                        timeNow));
    }

    /** Inform the KeepaliveStatsTracker a keepalive has just been paused. */
    public void onPauseKeepalive(Network network, int slot) {
        if (!mKeepaliveCarrierStatsPerKeepalive.containsKey(new Pair<>(network, slot))) {
            Log.e(TAG, "Attempt to pause keepalive on an unknown network, slot pair");
            return;
        }
        final long timeNow = mDependencies.getUptimeMillis();
        updateDurationsPerNumOfKeepalive(timeNow);

        mNumActiveKeepalive--;

        mKeepaliveCarrierStatsPerKeepalive
                .get(new Pair<>(network, slot))
                .updateLiftimeStatsAndSetActive(timeNow, /* active= */ false);
    }

    /** Inform the KeepaliveStatsTracker a keepalive has just been resumed. */
    public void onResumeKeepalive(Network network, int slot) {
        if (!mKeepaliveCarrierStatsPerKeepalive.containsKey(new Pair<>(network, slot))) {
            Log.e(TAG, "Attempt to resume keepalive on an unknown network, slot pair");
            return;
        }
        final long timeNow = mDependencies.getUptimeMillis();
        updateDurationsPerNumOfKeepalive(timeNow);

        mNumActiveKeepalive++;
        mKeepaliveCarrierStatsPerKeepalive
                .get(new Pair<>(network, slot))
                .updateLiftimeStatsAndSetActive(timeNow, /* active= */ true);
    }

    /** Inform the KeepaliveStatsTracker a keepalive has just been stopped. */
    public void onStopKeepalive(Network network, int slot) {
        if (!mKeepaliveCarrierStatsPerKeepalive.containsKey(new Pair<>(network, slot))) {
            Log.e(TAG, "Attempt to stop keepalive on an unknown network, slot pair");
            return;
        }
        final long timeNow = mDependencies.getUptimeMillis();
        updateDurationsPerNumOfKeepalive(timeNow);

        mNumRegisteredKeepalive--;
        final KeepaliveCarrierStats keepaliveCarrierStats =
                mKeepaliveCarrierStatsPerKeepalive.get(new Pair<>(network, slot));
        if (keepaliveCarrierStats.isActive()) mNumActiveKeepalive--;
        mKeepaliveCarrierStatsPerKeepalive
                .get(new Pair<>(network, slot))
                .updateLiftimeStatsAndStop(timeNow);
    }

    /**
     * Builds DailykeepaliveInfoReported and writes to statsd.
     *
     * @returns the DailykeepaliveInfoReported proto that was written
     */
    public DailykeepaliveInfoReported writeKeepaliveMetrics() {
        final long timeNow = mDependencies.getUptimeMillis();
        updateDurationsPerNumOfKeepalive(timeNow);

        final DurationPerNumOfKeepalive.Builder durationPerNumOfKeepalive =
                DurationPerNumOfKeepalive.newBuilder();

        mDurationPerNumOfKeepalive.forEach(
                durationForNumOfKeepalive ->
                        durationPerNumOfKeepalive.addDurationForNumOfKeepalive(
                                durationForNumOfKeepalive));

        final KeepaliveLifetimePerCarrier.Builder keepaliveLifetimePerCarrier =
                KeepaliveLifetimePerCarrier.newBuilder();

        // Map to aggregate the KeepaliveLifetimeForCarrier stats using <carrierId, <transportType,
        // IntervalsMsec>> as the key.
        final HashMap<Pair<Pair<Integer, Integer>, Integer>, KeepaliveLifetimeForCarrier.Builder>
                aggregateKeepaliveLifetime = new HashMap<>();

        for (final KeepaliveCarrierStats keepaliveCarrierStats :
                mKeepaliveCarrierStatsPerKeepalive.values()) {

            // Ensure lifetime durations are correct up to now.
            keepaliveCarrierStats.updateLifetimeStats(timeNow);

            // Aggregate the stats into aggregateKeepaliveLifetime
            final Pair<Pair<Integer, Integer>, Integer> key =
                    new Pair<>(
                            new Pair<>(
                                    keepaliveCarrierStats.carrierId,
                                    keepaliveCarrierStats.transportTypes),
                            keepaliveCarrierStats.intervalMsec);
            final KeepaliveLifetimeForCarrier.Builder keepaliveLifetimeForCarrier =
                    aggregateKeepaliveLifetime.getOrDefault(
                            key,
                            KeepaliveLifetimeForCarrier.newBuilder()
                                    .setCarrierId(keepaliveCarrierStats.carrierId)
                                    .setTransportTypes(keepaliveCarrierStats.transportTypes)
                                    .setIntervalsMsec(keepaliveCarrierStats.intervalMsec));
            keepaliveLifetimeForCarrier.setLifetimeMsec(
                    keepaliveLifetimeForCarrier.getLifetimeMsec()
                            + keepaliveCarrierStats.getLifetimeMsec());
            keepaliveLifetimeForCarrier.setActiveLifetimeMsec(
                    keepaliveLifetimeForCarrier.getActiveLifetimeMsec()
                            + keepaliveCarrierStats.getActiveLifetimeMsec());

            aggregateKeepaliveLifetime.put(key, keepaliveLifetimeForCarrier);
        }

        // Write keepalive carrier stats to the proto
        for (final KeepaliveLifetimeForCarrier.Builder keepaliveLifetimeForCarrier :
                aggregateKeepaliveLifetime.values()) {
            keepaliveLifetimePerCarrier.addKeepaliveLifetimeForCarrier(keepaliveLifetimeForCarrier);
        }

        final DailykeepaliveInfoReported.Builder dailyKeepaliveInfoReported =
                DailykeepaliveInfoReported.newBuilder();

        // TODO(b/273451360): fill all the other values and write to ConnectivityStatsLog.
        dailyKeepaliveInfoReported.setDurationPerNumOfKeepalive(durationPerNumOfKeepalive);
        dailyKeepaliveInfoReported.setKeepaliveLifetimePerCarrier(keepaliveLifetimePerCarrier);

        reset();

        return dailyKeepaliveInfoReported.build();
    }
    /** Resets the stored metrics but maintains the state of keepalives */
    public void reset() {
        mDurationPerNumOfKeepalive.clear();
        ensureDurationPerNumOfKeepaliveSize();

        // Remove stopped keepalives
        mKeepaliveCarrierStatsPerKeepalive.entrySet().stream()
                .filter(entry -> !entry.getValue().isStopped())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        // Reset the stats for exisiting keepalives
        mKeepaliveCarrierStatsPerKeepalive
                .values()
                .forEach(keepaliveCarrierStats -> keepaliveCarrierStats.resetLifetimeStats());
    }
}
