/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.metrics;

import static com.android.server.ConnectivityStatsLog.CONNECTIVITY_STATE_SAMPLE;

import android.app.StatsManager;
import android.content.Context;
import android.stats.connectivity.MeteredState;
import android.stats.connectivity.RequestType;
import android.stats.connectivity.ValidatedState;
import android.util.Log;
import android.util.StatsEvent;

import com.android.internal.annotations.GuardedBy;
import com.android.modules.utils.BackgroundThread;

import java.util.List;

/**
* Implements statsd pullers Or pulls for ConnectivityService.
*
* <p>This class registers all connectivity metrics pullers to statsd.
*/
public class MetricsController implements StatsManager.StatsPullAtomCallback {
    private static final String TAG = MetricsController.class.getSimpleName();

    @GuardedBy("mConnectivityStateMetrics")
    private final ConnectivityStateSampleMetrics mConnectivityStateMetrics =
            new ConnectivityStateSampleMetrics();

    final StatsManager mStatsManager;
    public MetricsController(Context context) {
        mStatsManager = context.getSystemService(StatsManager.class);
        if (mStatsManager != null) {
            registerAtomPuller(CONNECTIVITY_STATE_SAMPLE);
        }
    }

    /** Registers a pulled atom ID {@code atomId} for pulling. */
    private void registerAtomPuller(int atomId) {
        mStatsManager.setPullAtomCallback(atomId, null, BackgroundThread.getExecutor(), this);
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link StatsManager#PULL_SUCCESS} with list of atoms (potentially empty) if pull
     *     succeeded, {@link StatsManager#PULL_SKIP} if pull was too frequent or atom ID is
     *     unexpected.
     */
    @Override
    public int onPullAtom(int atomTag, List<StatsEvent> data) {
        switch (atomTag) {
            case CONNECTIVITY_STATE_SAMPLE:
                data.add(recordMetricsConnectivityStateSampleStats());
                return StatsManager.PULL_SUCCESS;
            default:
                Log.e(TAG, "Unexpected atom tag: " + atomTag);
                return StatsManager.PULL_SKIP;
        }
    }

    /**
     * Write the number of network count on each list of transports.
     */
    public void recordMetricsNetworkCount(int transportTypes, int networkCount) {
        synchronized (mConnectivityStateMetrics) {
            mConnectivityStateMetrics.setNetworkCountForTransports(transportTypes, transportTypes);
        }
    }

   /**
     * Write the connection duration in seconds and list of transports.
     */
    public void recordMetricsConnDuration(int transportTypes, int durationSec) {
        synchronized (mConnectivityStateMetrics) {
            mConnectivityStateMetrics.setConnDurationForTransports(transportTypes, durationSec);
        }
    }

    /**
     * Write the network request count & request type.
     */
    public void recordMetricsRequestCount(RequestType requestType, int requestCount) {
        synchronized (mConnectivityStateMetrics) {
            mConnectivityStateMetrics.setRequestCountForType(requestType, requestCount);
        }
    }


    /**
     * Write the information about a network.
     */
    public void recordMetricsNetworkDescription(int transportTypes, MeteredState meteredState,
            ValidatedState validatedState, long scorePolicies, long capabilities,
            int enterpriseId) {
        synchronized (mConnectivityStateMetrics) {
            mConnectivityStateMetrics.setNetworkDescription(transportTypes, meteredState,
                    validatedState, scorePolicies, capabilities, enterpriseId);
        }
    }

    /**
     * Write the ConnectivityStateSample proto into statsd.
     */
    public StatsEvent recordMetricsConnectivityStateSampleStats() {
        synchronized (mConnectivityStateMetrics) {
            return mConnectivityStateMetrics.buildStatsEvent();
        }
    }
}
