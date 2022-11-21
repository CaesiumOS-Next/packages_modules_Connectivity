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
    private final ConnectivityStateSample.Builder mStatsBuilder =
            ConnectivityStateSample.newBuilder();
    private final NetworkCountPerTransports.Builder mNetworkCountPerTransportsBuilder =
            NetworkCountPerTransports.newBuilder();
    private final ConnectionDurationPerTransports.Builder mConnDurationPerTransBuilder =
            ConnectionDurationPerTransports.newBuilder();
    private final NetworkRequestCount.Builder mNetworkRequestCountBuilder =
            NetworkRequestCount.newBuilder();
    private final NetworkList.Builder mNetworkListBuilder = NetworkList.newBuilder();

    /**
     * Write the number of network count on each list of transports.
     */
    public void setNetworkCountForTransports(final int transportTypes, final int networkCount) {
        final NetworkCountForTransports.Builder netCountForTransBuilder =
                NetworkCountForTransports.newBuilder()
                .setTransportTypes(transportTypes)
                .setNetworkCount(networkCount);

        mNetworkCountPerTransportsBuilder.addNetworkCountForTransports(netCountForTransBuilder);
    }

   /**
     * Write the connection duration in seconds and list of transports.
     */
    public void setConnDurationForTransports(final int transportTypes, final int durationSec) {
        final ConnectionDurationForTransports.Builder connDuForTransBuilder =
                ConnectionDurationForTransports.newBuilder()
                .setTransportTypes(transportTypes)
                .setDurationSec(durationSec);

        mConnDurationPerTransBuilder.addConnectionDurationForTransports(connDuForTransBuilder);
    }

    /**
     * Write the network request count & request type.
     */
    public void setRequestCountForType(final RequestType requestType, final int requestCount) {
        final RequestCountForType.Builder requestCountForTypeBuilder =
                RequestCountForType.newBuilder().setRequestType(requestType)
                .setRequestCount(requestCount);

        mNetworkRequestCountBuilder.addRequestCountForType(requestCountForTypeBuilder);
    }

    /**
     * Write the information about a network.
     */
    public void setNetworkDescription(final int transportTypes, final MeteredState meteredState,
            final ValidatedState validatedState, final long scorePolicies, final long capabilities,
            final int enterpriseId) {
        final NetworkDescription.Builder networkDescriptionBuilder = NetworkDescription.newBuilder()
                .setTransportTypes(transportTypes)
                .setMeteredState(meteredState)
                .setValidatedState(validatedState)
                .setScorePolicies(scorePolicies)
                .setCapabilities(capabilities)
                .setEnterpriseId(enterpriseId);

        mNetworkListBuilder.addNetworkDescription(networkDescriptionBuilder);
    }

    /**
     * Write the ConnectivityStateSample proto into statsd.
     */
    public StatsEvent buildStatsEvent() {
        mStatsBuilder.setNetworkCountPerTransports(mNetworkCountPerTransportsBuilder);
        mStatsBuilder.setConnectionDurationPerTransports(mConnDurationPerTransBuilder);
        mStatsBuilder.setNetworkRequestCount(mNetworkRequestCountBuilder);
        mStatsBuilder.setNetworks(mNetworkListBuilder);

        final ConnectivityStateSample mStats = mStatsBuilder.build();
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

