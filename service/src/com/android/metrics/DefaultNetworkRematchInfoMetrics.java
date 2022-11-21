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

import android.os.SystemClock;
import android.stats.connectivity.MeteredState;
import android.stats.connectivity.RematchReason;
import android.stats.connectivity.ValidatedState;

import com.android.server.ConnectivityStatsLog;
import com.android.util.Stopwatch;


/**
 * Class to record the DefaultNetworkRematchInfo into statsd.
 * 1. Fill in DefaultNetworkRematchInfoMetrics proto.
 * 2. Write the DefaultNetworkRematchInfoMetrics proto into statsd.
 * @hide
 */
public class DefaultNetworkRematchInfoMetrics {
    private final DefaultNetworkRematchInfo.Builder mStatsBuilder =
            DefaultNetworkRematchInfo.newBuilder();
    private final NetworkDescription.Builder mNetworkDescriptionOldBuilder =
            NetworkDescription.newBuilder();
    private final NetworkDescription.Builder mNetworkDescriptionNewBuilder =
            NetworkDescription.newBuilder();
    private final Stopwatch mWatch = new Stopwatch().start();

    /**
     * Reset this DefaultNetworkRematchInfoMetrics and start collecting duration and metrics.
     *
     * <p>This must be called when network connection session starts.
     */
    public void reset() {
        mStatsBuilder.clear();
        mNetworkDescriptionOldBuilder.clear();
        mNetworkDescriptionNewBuilder.clear();
        mStatsBuilder.setSessionId(SystemClock.elapsedRealtime());
        mWatch.restart();
    }

    /**
     * Write detail description of the old or new network.
     */
    public void setNetworkDescription(final boolean isNewNetwork, final int transportTypes,
            final MeteredState meteredState, final ValidatedState validatedState,
            final long scorePolicies, final long capabilities, int enterpriseId) {

        if (isNewNetwork) {
            mNetworkDescriptionNewBuilder.setTransportTypes(transportTypes)
                    .setMeteredState(meteredState)
                    .setValidatedState(validatedState)
                    .setScorePolicies(scorePolicies)
                    .setCapabilities(capabilities)
                    .setEnterpriseId(enterpriseId);
        } else {
            mNetworkDescriptionOldBuilder.setTransportTypes(transportTypes)
                    .setMeteredState(meteredState)
                    .setValidatedState(validatedState)
                    .setScorePolicies(scorePolicies)
                    .setCapabilities(capabilities)
                    .setEnterpriseId(enterpriseId);
        }
    }

    /**
     * Write DefaultNetworkRematchInfo into statsd.
     */
    public DefaultNetworkRematchInfo statsWrite(RematchReason rematchReason) {
        mStatsBuilder.setOldNetwork(mNetworkDescriptionOldBuilder);
        mStatsBuilder.setNewNetwork(mNetworkDescriptionNewBuilder);
        mStatsBuilder.setRematchReason(rematchReason);
        mStatsBuilder.setTimeDurationOnOldNetworkSec(mWatch.stopInSec());
        final DefaultNetworkRematchInfo Stats = mStatsBuilder.build();
        final byte[] OldNetwork = Stats.getOldNetwork().toByteArray();
        final byte[] NewNetwork = Stats.getNewNetwork().toByteArray();

        ConnectivityStatsLog.write(ConnectivityStatsLog.DEFAULT_NETWORK_REMATCH_INFO,
                Stats.getSessionId(),
                OldNetwork,
                NewNetwork,
                Stats.getRematchReason().getNumber(),
                Stats.getTimeDurationOnOldNetworkSec());
        mWatch.reset();
        return Stats;
    }
}

