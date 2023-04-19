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
import android.annotation.Nullable;
import android.content.Context;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkSpecifier;
import android.net.TelephonyNetworkSpecifier;
import android.os.Handler;
import android.os.SystemClock;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.metrics.DailykeepaliveInfoReported;
import com.android.metrics.DurationForNumOfKeepalive;
import com.android.metrics.DurationPerNumOfKeepalive;
import com.android.metrics.KeepaliveLifetimeForCarrier;
import com.android.metrics.KeepaliveLifetimePerCarrier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;

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

    @NonNull private final Context mContext;
    @NonNull private final Handler mConnectivityServiceHandler;
    private final SubscriptionManager mSubscriptionManager;

    private final Dependencies mDependencies;

    // Mapping of subId to carrierId. Updates are received from OnSubscriptionsChangedListener
    private final SparseIntArray mCachedCarrierIdPerSubId = new SparseIntArray();

    // Class to store network information, lifetime durations and active state of a keepalive.
    private static final class KeepaliveStats {
        // The carrier ID for a keepalive, or TelephonyManager.UNKNOWN_CARRIER_ID(-1) if not set.
        public final int carrierId;
        // The transport types of the underlying network for each keepalive. A network may include
        // multiple transport types. Each transport type is represented by a different bit, defined
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
        private boolean mKeepaliveActive = true;

        public int getLifetimeMsec() {
            return mLifetimeMsec;
        }

        public int getActiveLifetimeMsec() {
            return mActiveLifetimeMsec;
        }

        public boolean isKeepaliveActive() {
            return mKeepaliveActive;
        }

        KeepaliveStats(int carrierId, int transportTypes, int intervalSeconds, long timeNow) {
            this.carrierId = carrierId;
            this.transportTypes = transportTypes;
            this.intervalMsec = intervalSeconds * 1000;
            mTimestampSinceLastUpdateStats = timeNow;
        }

        // Updates the lifetime metrics to the given time and sets the active state. This should be
        // called whenever the active state of the keepalive changes.
        public void updateLifetimeStatsAndSetActive(long timeNow, boolean keepaliveActive) {
            final int durationIncrease = (int) (timeNow - mTimestampSinceLastUpdateStats);
            mLifetimeMsec += durationIncrease;
            if (mKeepaliveActive) mActiveLifetimeMsec += durationIncrease;

            mTimestampSinceLastUpdateStats = timeNow;
            mKeepaliveActive = keepaliveActive;
        }

        // Resets the lifetime metrics but does not reset the active/stopped state of the keepalive.
        // This also updates the time to timeNow, ensuring stats will start from this time.
        public void resetLifetimeStats(long timeNow) {
            mLifetimeMsec = 0;
            mActiveLifetimeMsec = 0;
            mTimestampSinceLastUpdateStats = timeNow;
        }
    }

    // List of duration stats metric where the index is the number of concurrent keepalives.
    // Each DurationForNumOfKeepalive message stores a registered duration and an active duration.
    // Registered duration is the total time spent with mNumRegisteredKeepalive == index.
    // Active duration is the total time spent with mNumActiveKeepalive == index.
    private final List<DurationForNumOfKeepalive.Builder> mDurationPerNumOfKeepalive =
            new ArrayList<>();

    // SparseArray with a key of netId to a sparse array of KeepaliveStats for each slot of the
    // network. (netId -> (slot -> KeepaliveStats))
    private final SparseArray<SparseArray<KeepaliveStats>> mKeepaliveStatsPerNetwork =
            new SparseArray<>();

    private boolean isKnownKeepalive(int netId, int slot) {
        return mKeepaliveStatsPerNetwork.contains(netId)
                && mKeepaliveStatsPerNetwork.get(netId).contains(slot);
    }

    // Class to act as the key to aggregate the KeepaliveLifetimeForCarrier stats.
    private static final class AggregateKey {
        public final int carrierId;
        public final int transportTypes;
        public final int intervalMsec;

        AggregateKey(int carrierId, int transportTypes, int intervalMsec) {
            this.carrierId = carrierId;
            this.transportTypes = transportTypes;
            this.intervalMsec = intervalMsec;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (!(o instanceof AggregateKey)) return false;
            final AggregateKey other = (AggregateKey) o;

            return this.carrierId == other.carrierId
                    && this.transportTypes == other.transportTypes
                    && this.intervalMsec == other.intervalMsec;
        }

        @Override
        public int hashCode() {
            return Objects.hash(carrierId, transportTypes, intervalMsec);
        }
    }

    // Map to aggregate the KeepaliveLifetimeForCarrier stats using AggregateKey as the key.
    final Map<AggregateKey, KeepaliveLifetimeForCarrier.Builder> mAggregateKeepaliveLifetime =
            new HashMap<>();

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

    public KeepaliveStatsTracker(@NonNull Context context, @NonNull Handler handler) {
        this(context, handler, new Dependencies());
    }

    @VisibleForTesting
    public KeepaliveStatsTracker(
            @NonNull Context context,
            @NonNull Handler handler,
            @NonNull Dependencies dependencies) {
        mContext = Objects.requireNonNull(context);
        mDependencies = Objects.requireNonNull(dependencies);
        mConnectivityServiceHandler = Objects.requireNonNull(handler);

        mSubscriptionManager = context.getSystemService(SubscriptionManager.class);

        mTimestampSinceLastUpdateDurations = mDependencies.getUptimeMillis();

        registerOnSubscriptionsChangedListener();
    }

    private void registerOnSubscriptionsChangedListener() {
        if (mSubscriptionManager == null) return;

        mSubscriptionManager.addOnSubscriptionsChangedListener(
                Executors.newSingleThreadExecutor(),
                new SubscriptionManager.OnSubscriptionsChangedListener() {
                    @Override
                    public void onSubscriptionsChanged() {
                        final List<SubscriptionInfo> activeSubInfoList =
                                mSubscriptionManager.getActiveSubscriptionInfoList();

                        mConnectivityServiceHandler.post(() -> {
                            mCachedCarrierIdPerSubId.clear();
                            for (SubscriptionInfo subInfo : activeSubInfoList) {
                                mCachedCarrierIdPerSubId.put(
                                        subInfo.getSubscriptionId(),
                                        subInfo.getCarrierId());
                            }
                        });
                    }
                });
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

    private int getCarrierId(@NonNull NetworkCapabilities networkCapabilities) {
        if (!networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return TelephonyManager.UNKNOWN_CARRIER_ID;
        }

        // Try to get the correct subscription id.
        final NetworkSpecifier networkSpecifier = networkCapabilities.getNetworkSpecifier();
        if (networkSpecifier instanceof TelephonyNetworkSpecifier) {
            final int subId = ((TelephonyNetworkSpecifier) networkSpecifier).getSubscriptionId();
            return mCachedCarrierIdPerSubId.get(subId, TelephonyManager.UNKNOWN_CARRIER_ID);
        }

        // Use default subscriptionId.
        return mCachedCarrierIdPerSubId.get(
                SubscriptionManager.getDefaultSubscriptionId(),
                TelephonyManager.UNKNOWN_CARRIER_ID);
    }

    private int getTransportTypes(@NonNull NetworkCapabilities networkCapabilities) {
        // Cast to int to follow proto definition.
        return (int) networkCapabilities.getTransportTypesInternal();
    }

    /** Inform the KeepaliveStatsTracker a keepalive has just started and is active. */
    public void onStartKeepalive(
            @NonNull Network network,
            int slot,
            @NonNull NetworkCapabilities nc,
            int intervalSeconds) {
        final int netId = network.netId;
        if (isKnownKeepalive(netId, slot)) {
            Log.e(TAG, "Attempt to start keepalive stats on a known network, slot pair");
            return;
        }
        final long timeNow = mDependencies.getUptimeMillis();
        updateDurationsPerNumOfKeepalive(timeNow);

        mNumRegisteredKeepalive++;
        mNumActiveKeepalive++;

        if (!mKeepaliveStatsPerNetwork.contains(netId)) {
            mKeepaliveStatsPerNetwork.put(netId, new SparseArray());
        }
        mKeepaliveStatsPerNetwork
                .get(netId)
                .put(
                        slot,
                        new KeepaliveStats(
                                getCarrierId(nc), getTransportTypes(nc), intervalSeconds, timeNow));
    }

    /** Inform the KeepaliveStatsTracker a keepalive has just been paused. */
    public void onPauseKeepalive(@NonNull Network network, int slot) {
        final int netId = network.netId;
        if (!isKnownKeepalive(netId, slot)) {
            Log.e(TAG, "Attempt to pause keepalive on an unknown network, slot pair");
            return;
        }
        final long timeNow = mDependencies.getUptimeMillis();
        updateDurationsPerNumOfKeepalive(timeNow);

        mNumActiveKeepalive--;

        mKeepaliveStatsPerNetwork
                .get(netId)
                .get(slot)
                .updateLifetimeStatsAndSetActive(timeNow, /* keepaliveActive= */ false);
    }

    /** Inform the KeepaliveStatsTracker a keepalive has just been resumed. */
    public void onResumeKeepalive(@NonNull Network network, int slot) {
        final int netId = network.netId;
        if (!isKnownKeepalive(netId, slot)) {
            Log.e(TAG, "Attempt to resume keepalive on an unknown network, slot pair");
            return;
        }
        final long timeNow = mDependencies.getUptimeMillis();
        updateDurationsPerNumOfKeepalive(timeNow);

        mNumActiveKeepalive++;
        mKeepaliveStatsPerNetwork
                .get(netId)
                .get(slot)
                .updateLifetimeStatsAndSetActive(timeNow, /* keepaliveActive= */ true);
    }

    /** Inform the KeepaliveStatsTracker a keepalive has just been stopped. */
    public void onStopKeepalive(@NonNull Network network, int slot) {
        final int netId = network.netId;
        if (!isKnownKeepalive(netId, slot)) {
            Log.e(TAG, "Attempt to stop keepalive on an unknown network, slot pair");
            return;
        }
        final long timeNow = mDependencies.getUptimeMillis();
        updateDurationsPerNumOfKeepalive(timeNow);

        mNumRegisteredKeepalive--;
        final KeepaliveStats keepaliveStats = mKeepaliveStatsPerNetwork.get(netId).get(slot);
        if (keepaliveStats.isKeepaliveActive()) mNumActiveKeepalive--;

        keepaliveStats.updateLifetimeStatsAndSetActive(timeNow, /* keepaliveActive= */ false);
        // add to the aggregate since it will be removed.
        addToAggregateKeepaliveLifetime(keepaliveStats, timeNow);
        // free up the slot.
        mKeepaliveStatsPerNetwork.get(netId).remove(slot);
        if (mKeepaliveStatsPerNetwork.get(netId).size() == 0) {
            mKeepaliveStatsPerNetwork.remove(netId);
        }
    }

    // Updates and adds the lifetime metric of keepaliveStats to the aggregate. This also resets the
    // stored lifetime metrics to avoid double counting so the KeepaliveStats may be reused.
    private void addToAggregateKeepaliveLifetime(KeepaliveStats keepaliveStats, long timeNow) {

        // Ensure lifetime durations are correct up to now.
        keepaliveStats.updateLifetimeStatsAndSetActive(timeNow, keepaliveStats.isKeepaliveActive());

        final AggregateKey key =
                new AggregateKey(
                        keepaliveStats.carrierId,
                        keepaliveStats.transportTypes,
                        keepaliveStats.intervalMsec);

        KeepaliveLifetimeForCarrier.Builder keepaliveLifetimeForCarrier =
                mAggregateKeepaliveLifetime.get(key);

        if (keepaliveLifetimeForCarrier == null) {
            keepaliveLifetimeForCarrier =
                    KeepaliveLifetimeForCarrier.newBuilder()
                            .setCarrierId(keepaliveStats.carrierId)
                            .setTransportTypes(keepaliveStats.transportTypes)
                            .setIntervalsMsec(keepaliveStats.intervalMsec);
            mAggregateKeepaliveLifetime.put(key, keepaliveLifetimeForCarrier);
        }

        keepaliveLifetimeForCarrier.setLifetimeMsec(
                keepaliveLifetimeForCarrier.getLifetimeMsec() + keepaliveStats.getLifetimeMsec());
        keepaliveLifetimeForCarrier.setActiveLifetimeMsec(
                keepaliveLifetimeForCarrier.getActiveLifetimeMsec()
                        + keepaliveStats.getActiveLifetimeMsec());

        // Reset the stats to avoid double counting.
        keepaliveStats.resetLifetimeStats(timeNow);
    }

    /**
     * Builds and returns DailykeepaliveInfoReported proto.
     *
     * @return the DailykeepaliveInfoReported proto that was built.
     */
    public DailykeepaliveInfoReported buildKeepaliveMetrics() {
        final long timeNow = mDependencies.getUptimeMillis();
        return buildKeepaliveMetrics(timeNow);
    }

    /** Updates the metrics to timeNow and builds and returns DailykeepaliveInfoReported proto. */
    private DailykeepaliveInfoReported buildKeepaliveMetrics(long timeNow) {

        updateDurationsPerNumOfKeepalive(timeNow);

        final DurationPerNumOfKeepalive.Builder durationPerNumOfKeepalive =
                DurationPerNumOfKeepalive.newBuilder();

        mDurationPerNumOfKeepalive.forEach(
                durationForNumOfKeepalive ->
                        durationPerNumOfKeepalive.addDurationForNumOfKeepalive(
                                durationForNumOfKeepalive));

        final KeepaliveLifetimePerCarrier.Builder keepaliveLifetimePerCarrier =
                KeepaliveLifetimePerCarrier.newBuilder();

        for (int i = 0; i < mKeepaliveStatsPerNetwork.size(); i++) {
            final SparseArray<KeepaliveStats> keepaliveStatsForNetwork =
                    mKeepaliveStatsPerNetwork.valueAt(i);
            for (int j = 0; j < keepaliveStatsForNetwork.size(); j++) {
                final KeepaliveStats keepaliveStats = keepaliveStatsForNetwork.valueAt(j);
                addToAggregateKeepaliveLifetime(keepaliveStats, timeNow);
            }
        }

        // Fill keepalive carrier stats to the proto
        mAggregateKeepaliveLifetime
                .values()
                .forEach(
                        keepaliveLifetimeForCarrier ->
                                keepaliveLifetimePerCarrier.addKeepaliveLifetimeForCarrier(
                                        keepaliveLifetimeForCarrier));

        final DailykeepaliveInfoReported.Builder dailyKeepaliveInfoReported =
                DailykeepaliveInfoReported.newBuilder();

        // TODO(b/273451360): fill all the other values and write to ConnectivityStatsLog.
        dailyKeepaliveInfoReported.setDurationPerNumOfKeepalive(durationPerNumOfKeepalive);
        dailyKeepaliveInfoReported.setKeepaliveLifetimePerCarrier(keepaliveLifetimePerCarrier);

        return dailyKeepaliveInfoReported.build();
    }

    /**
     * Builds and resets the stored metrics. Similar to buildKeepaliveMetrics but also resets the
     * metrics while maintaining the state of the keepalives.
     *
     * @return the DailykeepaliveInfoReported proto that was built.
     */
    public DailykeepaliveInfoReported buildAndResetMetrics() {
        final long timeNow = mDependencies.getUptimeMillis();

        final DailykeepaliveInfoReported metrics = buildKeepaliveMetrics(timeNow);

        mDurationPerNumOfKeepalive.clear();

        ensureDurationPerNumOfKeepaliveSize();
        mTimestampSinceLastUpdateDurations = timeNow;

        mAggregateKeepaliveLifetime.clear();
        // Reset the stats for existing keepalives
        for (int i = 0; i < mKeepaliveStatsPerNetwork.size(); i++) {
            final SparseArray<KeepaliveStats> keepaliveStatsForNetwork =
                    mKeepaliveStatsPerNetwork.valueAt(i);
            for (int j = 0; j < keepaliveStatsForNetwork.size(); j++) {
                final KeepaliveStats keepaliveStats = keepaliveStatsForNetwork.valueAt(j);
                keepaliveStats.resetLifetimeStats(timeNow);
            }
        }

        return metrics;
    }
}
