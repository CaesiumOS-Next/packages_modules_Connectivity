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

package com.android.server.connectivity.mdns;

import android.annotation.NonNull;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.connectivity.mdns.util.MdnsLogger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Instance of this class sends and receives mDNS packets of a given service type and invoke
 * registered {@link MdnsServiceBrowserListener} instances.
 */
// TODO(b/177655645): Resolve nullness suppression.
@SuppressWarnings("nullness")
public class MdnsServiceTypeClient {
    private static final int DEFAULT_MTU = 1500;
    private static final MdnsLogger LOGGER = new MdnsLogger("MdnsServiceTypeClient");

    private final String mServiceType;
    private final String[] mServiceTypeLabels;
    private final MdnsSocketClient mSocketClient;
    private final ScheduledExecutorService mExecutor;
    private final Object mLock = new Object();
    private final Set<MdnsServiceBrowserListener> mListeners = new ArraySet<>();
    private final Map<String, MdnsResponse> mInstanceNameToResponse = new HashMap<>();

    // The session ID increases when startSendAndReceive() is called where we schedule a
    // QueryTask for
    // new subtypes. It stays the same between packets for same subtypes.
    private long mCurrentSessionId = 0;

    @GuardedBy("lock")
    private Future<?> mRequestTaskFuture;

    /**
     * Constructor of {@link MdnsServiceTypeClient}.
     *
     * @param socketClient Sends and receives mDNS packet.
     * @param executor     A {@link ScheduledExecutorService} used to schedule query tasks.
     */
    public MdnsServiceTypeClient(
            @NonNull String serviceType,
            @NonNull MdnsSocketClient socketClient,
            @NonNull ScheduledExecutorService executor) {
        mServiceType = serviceType;
        mSocketClient = socketClient;
        mExecutor = executor;
        mServiceTypeLabels = TextUtils.split(serviceType, "\\.");
    }

    private static MdnsServiceInfo buildMdnsServiceInfoFromResponse(
            @NonNull MdnsResponse response, @NonNull String[] serviceTypeLabels) {
        String[] hostName = response.getServiceRecord().getServiceHost();
        int port = response.getServiceRecord().getServicePort();

        String ipv4Address = null;
        String ipv6Address = null;
        if (response.hasInet4AddressRecord()) {
            ipv4Address = response.getInet4AddressRecord().getInet4Address().getHostAddress();
        }
        if (response.hasInet6AddressRecord()) {
            ipv6Address = response.getInet6AddressRecord().getInet6Address().getHostAddress();
        }
        // TODO: Throw an error message if response doesn't have Inet6 or Inet4 address.
        return new MdnsServiceInfo(
                response.getServiceInstanceName(),
                serviceTypeLabels,
                response.getSubtypes(),
                hostName,
                port,
                ipv4Address,
                ipv6Address,
                response.getTextRecord().getStrings());
    }

    /**
     * Registers {@code listener} for receiving discovery event of mDNS service instances, and
     * starts
     * (or continue) to send mDNS queries periodically.
     *
     * @param listener      The {@link MdnsServiceBrowserListener} to register.
     * @param searchOptions {@link MdnsSearchOptions} contains the list of subtypes to discover.
     */
    public void startSendAndReceive(
            @NonNull MdnsServiceBrowserListener listener,
            @NonNull MdnsSearchOptions searchOptions) {
        synchronized (mLock) {
            if (!mListeners.contains(listener)) {
                mListeners.add(listener);
                for (MdnsResponse existingResponse : mInstanceNameToResponse.values()) {
                    if (existingResponse.isComplete()) {
                        listener.onServiceFound(
                                buildMdnsServiceInfoFromResponse(existingResponse,
                                        mServiceTypeLabels));
                    }
                }
            }
            // Cancel the next scheduled periodical task.
            if (mRequestTaskFuture != null) {
                mRequestTaskFuture.cancel(true);
            }
            // Keep tracking the ScheduledFuture for the task so we can cancel it if caller is not
            // interested anymore.
            mRequestTaskFuture =
                    mExecutor.submit(
                            new QueryTask(
                                    new QueryTaskConfig(
                                            searchOptions.getSubtypes(),
                                            searchOptions.isPassiveMode(),
                                            ++mCurrentSessionId)));
        }
    }

    /**
     * Unregisters {@code listener} from receiving discovery event of mDNS service instances.
     *
     * @param listener The {@link MdnsServiceBrowserListener} to unregister.
     * @return {@code true} if no listener is registered with this client after unregistering {@code
     * listener}. Otherwise returns {@code false}.
     */
    public boolean stopSendAndReceive(@NonNull MdnsServiceBrowserListener listener) {
        synchronized (mLock) {
            mListeners.remove(listener);
            if (mListeners.isEmpty() && mRequestTaskFuture != null) {
                mRequestTaskFuture.cancel(true);
                mRequestTaskFuture = null;
            }
            return mListeners.isEmpty();
        }
    }

    public String[] getServiceTypeLabels() {
        return mServiceTypeLabels;
    }

    public synchronized void processResponse(@NonNull MdnsResponse response) {
        if (response.isGoodbye()) {
            onGoodbyeReceived(response.getServiceInstanceName());
        } else {
            onResponseReceived(response);
        }
    }

    public synchronized void onFailedToParseMdnsResponse(int receivedPacketNumber, int errorCode) {
        for (MdnsServiceBrowserListener listener : mListeners) {
            listener.onFailedToParseMdnsResponse(receivedPacketNumber, errorCode);
        }
    }

    private void onResponseReceived(@NonNull MdnsResponse response) {
        MdnsResponse currentResponse;
        currentResponse = mInstanceNameToResponse.get(response.getServiceInstanceName());

        boolean newServiceFound = false;
        boolean existingServiceChanged = false;
        if (currentResponse == null) {
            newServiceFound = true;
            currentResponse = response;
            mInstanceNameToResponse.put(response.getServiceInstanceName(), currentResponse);
        } else if (currentResponse.mergeRecordsFrom(response)) {
            existingServiceChanged = true;
        }
        if (!currentResponse.isComplete() || (!newServiceFound && !existingServiceChanged)) {
            return;
        }
        MdnsServiceInfo serviceInfo =
                buildMdnsServiceInfoFromResponse(currentResponse, mServiceTypeLabels);

        for (MdnsServiceBrowserListener listener : mListeners) {
            if (newServiceFound) {
                listener.onServiceFound(serviceInfo);
            } else {
                listener.onServiceUpdated(serviceInfo);
            }
        }
    }

    private void onGoodbyeReceived(@NonNull String serviceInstanceName) {
        mInstanceNameToResponse.remove(serviceInstanceName);
        for (MdnsServiceBrowserListener listener : mListeners) {
            listener.onServiceRemoved(serviceInstanceName);
        }
    }

    @VisibleForTesting
    MdnsPacketWriter createMdnsPacketWriter() {
        return new MdnsPacketWriter(DEFAULT_MTU);
    }

    // A configuration for the PeriodicalQueryTask that contains parameters to build a query packet.
    // Call to getConfigForNextRun returns a config that can be used to build the next query task.
    @VisibleForTesting
    static class QueryTaskConfig {
        private static final int INITIAL_TIME_BETWEEN_BURSTS_MS =
                (int) MdnsConfigs.initialTimeBetweenBurstsMs();
        private static final int TIME_BETWEEN_BURSTS_MS = (int) MdnsConfigs.timeBetweenBurstsMs();
        private static final int QUERIES_PER_BURST = (int) MdnsConfigs.queriesPerBurst();
        private static final int TIME_BETWEEN_QUERIES_IN_BURST_MS =
                (int) MdnsConfigs.timeBetweenQueriesInBurstMs();
        private static final int QUERIES_PER_BURST_PASSIVE_MODE =
                (int) MdnsConfigs.queriesPerBurstPassive();
        private static final int UNSIGNED_SHORT_MAX_VALUE = 65536;
        // The following fields are used by QueryTask so we need to test them.
        @VisibleForTesting
        final List<String> subtypes;
        private final boolean mAlwaysAskForUnicastResponse =
                MdnsConfigs.alwaysAskForUnicastResponseInEachBurst();
        private final boolean mUsePassiveMode;
        private final long mSessionId;
        @VisibleForTesting
        int transactionId;
        @VisibleForTesting
        boolean expectUnicastResponse;
        private int mQueriesPerBurst;
        private int mTimeBetweenBurstsInMs;
        private int mBurstCounter;
        private int mTimeToRunNextTaskInMs;
        private boolean mIsFirstBurst;

        QueryTaskConfig(@NonNull Collection<String> subtypes, boolean usePassiveMode,
                long sessionId) {
            mUsePassiveMode = usePassiveMode;
            this.subtypes = new ArrayList<>(subtypes);
            mQueriesPerBurst = QUERIES_PER_BURST;
            mBurstCounter = 0;
            this.transactionId = 1;
            this.expectUnicastResponse = true;
            mIsFirstBurst = true;
            mSessionId = sessionId;
            // Config the scan frequency based on the scan mode.
            if (mUsePassiveMode) {
                // In passive scan mode, sends a single burst of QUERIES_PER_BURST queries, and then
                // in each TIME_BETWEEN_BURSTS interval, sends QUERIES_PER_BURST_PASSIVE_MODE
                // queries.
                mTimeBetweenBurstsInMs = TIME_BETWEEN_BURSTS_MS;
            } else {
                // In active scan mode, sends a burst of QUERIES_PER_BURST queries,
                // TIME_BETWEEN_QUERIES_IN_BURST_MS apart, then waits for the scan interval, and
                // then repeats. The scan interval starts as INITIAL_TIME_BETWEEN_BURSTS_MS and
                // doubles until it maxes out at TIME_BETWEEN_BURSTS_MS.
                mTimeBetweenBurstsInMs = INITIAL_TIME_BETWEEN_BURSTS_MS;
            }
        }

        QueryTaskConfig getConfigForNextRun() {
            if (++transactionId > UNSIGNED_SHORT_MAX_VALUE) {
                transactionId = 1;
            }
            // Only the first query expects uni-cast response.
            expectUnicastResponse = false;
            if (++mBurstCounter == mQueriesPerBurst) {
                mBurstCounter = 0;

                if (mAlwaysAskForUnicastResponse) {
                    expectUnicastResponse = true;
                }
                // In passive scan mode, sends a single burst of QUERIES_PER_BURST queries, and
                // then in each TIME_BETWEEN_BURSTS interval, sends QUERIES_PER_BURST_PASSIVE_MODE
                // queries.
                if (mIsFirstBurst) {
                    mIsFirstBurst = false;
                    if (mUsePassiveMode) {
                        mQueriesPerBurst = QUERIES_PER_BURST_PASSIVE_MODE;
                    }
                }
                // In active scan mode, sends a burst of QUERIES_PER_BURST queries,
                // TIME_BETWEEN_QUERIES_IN_BURST_MS apart, then waits for the scan interval, and
                // then repeats. The scan interval starts as INITIAL_TIME_BETWEEN_BURSTS_MS and
                // doubles until it maxes out at TIME_BETWEEN_BURSTS_MS.
                mTimeToRunNextTaskInMs = mTimeBetweenBurstsInMs;
                if (mTimeBetweenBurstsInMs < TIME_BETWEEN_BURSTS_MS) {
                    mTimeBetweenBurstsInMs = Math.min(mTimeBetweenBurstsInMs * 2,
                            TIME_BETWEEN_BURSTS_MS);
                }
            } else {
                mTimeToRunNextTaskInMs = TIME_BETWEEN_QUERIES_IN_BURST_MS;
            }
            return this;
        }
    }

    // A FutureTask that enqueues a single query, and schedule a new FutureTask for the next task.
    private class QueryTask implements Runnable {

        private final QueryTaskConfig mConfig;

        QueryTask(@NonNull QueryTaskConfig config) {
            mConfig = config;
        }

        @Override
        public void run() {
            Pair<Integer, List<String>> result;
            try {
                result =
                        new EnqueueMdnsQueryCallable(
                                mSocketClient,
                                createMdnsPacketWriter(),
                                mServiceType,
                                mConfig.subtypes,
                                mConfig.expectUnicastResponse,
                                mConfig.transactionId)
                                .call();
            } catch (Exception e) {
                LOGGER.e(String.format("Failed to run EnqueueMdnsQueryCallable for subtype: %s",
                        TextUtils.join(",", mConfig.subtypes)), e);
                result = null;
            }
            synchronized (mLock) {
                if (MdnsConfigs.useSessionIdToScheduleMdnsTask()) {
                    // In case that the task is not canceled successfully, use session ID to check
                    // if this task should continue to schedule more.
                    if (mConfig.mSessionId != mCurrentSessionId) {
                        return;
                    }
                }

                if (MdnsConfigs.shouldCancelScanTaskWhenFutureIsNull()) {
                    if (mRequestTaskFuture == null) {
                        // If mRequestTaskFuture is set to null, the task is cancelled. We can't use
                        // isCancelled() here because this QueryTask is different from the future
                        // that is returned from executor.schedule(). See b/71646910.
                        return;
                    }
                }
                if ((result != null)) {
                    for (MdnsServiceBrowserListener listener : mListeners) {
                        listener.onDiscoveryQuerySent(result.second, result.first);
                    }
                }
                QueryTaskConfig config = mConfig.getConfigForNextRun();
                mRequestTaskFuture =
                        mExecutor.schedule(
                                new QueryTask(config), config.mTimeToRunNextTaskInMs,
                                TimeUnit.MILLISECONDS);
            }
        }
    }
}
