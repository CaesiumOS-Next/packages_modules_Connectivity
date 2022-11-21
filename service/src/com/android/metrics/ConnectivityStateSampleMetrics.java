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

import android.stats.connectivity.MeteredState;
import android.stats.connectivity.RequestType;
import android.stats.connectivity.ValidatedState;
import android.util.StatsEvent;

import com.android.server.ConnectivityStatsLog;

/**
 * Class to record the DefaultNetworkRematchInfoMetrics into statsd.
 * 1. Fill in DefaultNetworkRematchInfoMetrics proto.
 * 2. Write the DefaultNetworkRematchInfoMetrics proto into statsd.
 * @hide
 */
public class ConnectivityStateSampleMetrics {
    private static final ConnectivityStateSample.Builder sStatsBuilder =
            ConnectivityStateSample.newBuilder();
    private static final NetworkCountPerTransports.Builder sNetworkCountPerTransportsBuilder =
            NetworkCountPerTransports.newBuilder();
    private static final ConnectionDurationPerTransports.Builder sConnDurationPerTransBuilder =
            ConnectionDurationPerTransports.newBuilder();
    private static final NetworkRequestCount.Builder sNetworkRequestCountBuilder =
            NetworkRequestCount.newBuilder();
    private static final NetworkList.Builder sNetworkListBuilder = NetworkList.newBuilder();

    /**
     * Write the number of network count on each list of transports.
     */
    public void setNetworkCountForTransports(final int transportTypes, final int networkCount) {
        final NetworkCountForTransports.Builder netCountForTransBuilder =
                NetworkCountForTransports.newBuilder()
                .setTransportTypes(transportTypes)
                .setNetworkCount(networkCount);

        sNetworkCountPerTransportsBuilder.addNetworkCountForTransports(netCountForTransBuilder);
    }

   /**
     * Write the number of network count and transport type.
     */
    public void setConnDurationForTransports(final int transportTypes, final int durationSec) {
        final ConnectionDurationForTransports.Builder connDuForTransBuilder =
                ConnectionDurationForTransports.newBuilder()
                .setTransportTypes(transportTypes)
                .setDurationSec(durationSec);

        sConnDurationPerTransBuilder.addConnectionDurationForTransports(connDuForTransBuilder);
    }

    /**
     * Write detail description of the old or new network.
     */
    public void setRequestCountForType(final RequestType requestType, final int requestCount) {
        final RequestCountForType.Builder requestCountForTypeBuilder =
                RequestCountForType.newBuilder().setRequestType(requestType)
                .setRequestCount(requestCount);

        sNetworkRequestCountBuilder.addRequestCountForType(requestCountForTypeBuilder);
    }

    /**
     * Write network request count & request type.
     */
    public void setRequestCountForType(final int transportTypes, final MeteredState meteredState,
            final ValidatedState validatedState, final long scorePolicies, final long capabilities,
            final int enterpriseId) {
        final NetworkDescription.Builder networkDescriptionBuilder = NetworkDescription.newBuilder()
                .setTransportTypes(transportTypes)
                .setMeteredState(meteredState)
                .setValidatedState(validatedState)
                .setScorePolicies(scorePolicies)
                .setCapabilities(capabilities)
                .setEnterpriseId(enterpriseId);

        sNetworkListBuilder.addNetworkDescription(networkDescriptionBuilder);
    }

    /**
     * Write the ConnectivityStateSample proto into statsd.
     */
    public static StatsEvent buildStatsEvent() {
        sStatsBuilder.setNetworkCountPerTransports(sNetworkCountPerTransportsBuilder);
        sStatsBuilder.setConnectionDurationPerTransports(sConnDurationPerTransBuilder);
        sStatsBuilder.setNetworkRequestCount(sNetworkRequestCountBuilder);
        sStatsBuilder.setNetworks(sNetworkListBuilder);

        final ConnectivityStateSample mStats = sStatsBuilder.build();
        final byte[] networkCountPerTransports =
                mStats.getNetworkCountPerTransports().toByteArray();
        final byte[] connectionDurationPerTransports =
                mStats.getConnectionDurationPerTransports().toByteArray();
        final byte[] networkRequestCount = mStats.getNetworkRequestCount().toByteArray();
        final byte[] networks = mStats.getNetworks().toByteArray();
        return ConnectivityStatsLog.buildStatsEvent(
                ConnectivityStatsLog.CONNECTIVITY_STATE_SAMPLE,
                networkCountPerTransports,
                connectionDurationPerTransports,
                networkRequestCount,
                networks);
    }
}

