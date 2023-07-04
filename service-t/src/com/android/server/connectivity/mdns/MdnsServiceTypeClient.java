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

package com.android.server.connectivity.mdns;

import static com.android.server.connectivity.mdns.MdnsServiceCache.ServiceExpiredCallback;
import static com.android.server.connectivity.mdns.MdnsServiceCache.findMatchedResponse;
import static com.android.server.connectivity.mdns.util.MdnsUtils.ensureRunningOnHandlerThread;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.CollectionUtils;
import com.android.net.module.util.SharedLog;
import com.android.server.connectivity.mdns.util.MdnsUtils;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Instance of this class sends and receives mDNS packets of a given service type and invoke
 * registered {@link MdnsServiceBrowserListener} instances.
 */
public class MdnsServiceTypeClient {

    private static final int DEFAULT_MTU = 1500;
    @VisibleForTesting
    static final int EVENT_START_QUERYTASK = 1;
    private final String serviceType;
    private final String[] serviceTypeLabels;
    private final MdnsSocketClientBase socketClient;
    private final MdnsResponseDecoder responseDecoder;
    private final ScheduledExecutorService executor;
    @NonNull private final SocketKey socketKey;
    @NonNull private final SharedLog sharedLog;
    @NonNull private final Handler handler;
    @NonNull private final Dependencies dependencies;
    /**
     * The service caches for each socket. It should be accessed from looper thread only.
     */
    @NonNull private final MdnsServiceCache serviceCache;
    @NonNull private final ServiceExpiredCallback serviceExpiredCallback;
    private final ArrayMap<MdnsServiceBrowserListener, MdnsSearchOptions> listeners =
            new ArrayMap<>();
    private final boolean removeServiceAfterTtlExpires =
            MdnsConfigs.removeServiceAfterTtlExpires();
    private final MdnsResponseDecoder.Clock clock;

    @Nullable private MdnsSearchOptions searchOptions;

    // The session ID increases when startSendAndReceive() is called where we schedule a
    // QueryTask for
    // new subtypes. It stays the same between packets for same subtypes.
    private long currentSessionId = 0;

    private class QueryTaskHandler extends Handler {
        QueryTaskHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_START_QUERYTASK:
                    handleStartQueryTask((QueryTaskConfig) msg.obj);
                    break;
                default:
                    sharedLog.e("Unrecognized event " + msg.what);
                    break;
            }
        }
    }

    /**
     * Dependencies of MdnsServiceTypeClient, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        /**
         * @see Handler#sendMessageDelayed(Message, long)
         */
        public void sendMessageDelayed(@NonNull Handler handler, @NonNull Message message,
                long delayMillis) {
            handler.sendMessageDelayed(message, delayMillis);
        }

        /**
         * @see Handler#removeMessages(int)
         */
        public void removeMessages(@NonNull Handler handler, int what) {
            handler.removeMessages(what);
        }


        /**
         * @see Handler#post(Runnable)
         */
        public void post(@NonNull Handler handler, @NonNull Runnable runnable) {
            handler.post(runnable);
        }
    }

    /**
     * Constructor of {@link MdnsServiceTypeClient}.
     *
     * @param socketClient Sends and receives mDNS packet.
     * @param executor         A {@link ScheduledExecutorService} used to schedule query tasks.
     */
    public MdnsServiceTypeClient(
            @NonNull String serviceType,
            @NonNull MdnsSocketClientBase socketClient,
            @NonNull ScheduledExecutorService executor,
            @NonNull SocketKey socketKey,
            @NonNull SharedLog sharedLog,
            @NonNull Looper looper,
            @NonNull MdnsServiceCache serviceCache) {
        this(serviceType, socketClient, executor, new MdnsResponseDecoder.Clock(), socketKey,
                sharedLog, looper, new Dependencies(), serviceCache);
    }

    @VisibleForTesting
    public MdnsServiceTypeClient(
            @NonNull String serviceType,
            @NonNull MdnsSocketClientBase socketClient,
            @NonNull ScheduledExecutorService executor,
            @NonNull MdnsResponseDecoder.Clock clock,
            @NonNull SocketKey socketKey,
            @NonNull SharedLog sharedLog,
            @NonNull Looper looper,
            @NonNull Dependencies dependencies,
            @NonNull MdnsServiceCache serviceCache) {
        this.serviceType = serviceType;
        this.socketClient = socketClient;
        this.executor = executor;
        this.serviceTypeLabels = TextUtils.split(serviceType, "\\.");
        this.responseDecoder = new MdnsResponseDecoder(clock, serviceTypeLabels);
        this.clock = clock;
        this.socketKey = socketKey;
        this.sharedLog = sharedLog;
        this.handler = new QueryTaskHandler(looper);
        this.dependencies = dependencies;
        this.serviceCache = serviceCache;
        this.serviceExpiredCallback = new ServiceExpiredCallback() {
            @Override
            public void onServiceRecordExpired(@NonNull MdnsResponse previousResponse,
                    @Nullable MdnsResponse newResponse) {
                for (int i = 0; i < listeners.size(); i++) {
                    if (!responseMatchesOptions(previousResponse, listeners.valueAt(i))) {
                        continue;
                    }
                    final MdnsServiceBrowserListener listener = listeners.keyAt(i);
                    final String serviceInstanceName = previousResponse.getServiceInstanceName();
                    if (serviceInstanceName != null) {
                        final MdnsServiceInfo serviceInfo = buildMdnsServiceInfoFromResponse(
                                previousResponse, serviceTypeLabels);
                        if (previousResponse.isComplete()
                                && (newResponse == null || !newResponse.isComplete())) {
                            sharedLog.log("TTL expired. onServiceRemoved: " + serviceInfo);
                            listener.onServiceRemoved(serviceInfo);
                        }
                        if (newResponse == null) {
                            sharedLog.log("TTL expired. onServiceNameRemoved: " + serviceInfo);
                            listener.onServiceNameRemoved(serviceInfo);
                        }
                    }
                }
            }
        };
    }

    private static MdnsServiceInfo buildMdnsServiceInfoFromResponse(
            @NonNull MdnsResponse response, @NonNull String[] serviceTypeLabels) {
        String[] hostName = null;
        int port = 0;
        if (response.hasServiceRecord()) {
            hostName = response.getServiceRecord().getServiceHost();
            port = response.getServiceRecord().getServicePort();
        }

        final List<String> ipv4Addresses = new ArrayList<>();
        final List<String> ipv6Addresses = new ArrayList<>();
        if (response.hasInet4AddressRecord()) {
            for (MdnsInetAddressRecord inetAddressRecord : response.getInet4AddressRecords()) {
                final Inet4Address inet4Address = inetAddressRecord.getInet4Address();
                ipv4Addresses.add((inet4Address == null) ? null : inet4Address.getHostAddress());
            }
        }
        if (response.hasInet6AddressRecord()) {
            for (MdnsInetAddressRecord inetAddressRecord : response.getInet6AddressRecords()) {
                final Inet6Address inet6Address = inetAddressRecord.getInet6Address();
                ipv6Addresses.add((inet6Address == null) ? null : inet6Address.getHostAddress());
            }
        }
        String serviceInstanceName = response.getServiceInstanceName();
        if (serviceInstanceName == null) {
            throw new IllegalStateException(
                    "mDNS response must have non-null service instance name");
        }
        List<String> textStrings = null;
        List<MdnsServiceInfo.TextEntry> textEntries = null;
        if (response.hasTextRecord()) {
            textStrings = response.getTextRecord().getStrings();
            textEntries = response.getTextRecord().getEntries();
        }
        // TODO: Throw an error message if response doesn't have Inet6 or Inet4 address.
        return new MdnsServiceInfo(
                serviceInstanceName,
                serviceTypeLabels,
                response.getSubtypes(),
                hostName,
                port,
                ipv4Addresses,
                ipv6Addresses,
                textStrings,
                textEntries,
                response.getInterfaceIndex(),
                response.getNetwork());
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
        ensureRunningOnHandlerThread(handler);
        this.searchOptions = searchOptions;
        boolean hadReply = false;
        if (listeners.put(listener, searchOptions) == null) {
            for (MdnsResponse existingResponse :
                    serviceCache.getCachedServices(serviceType, socketKey)) {
                if (!responseMatchesOptions(existingResponse, searchOptions)) continue;
                final MdnsServiceInfo info =
                        buildMdnsServiceInfoFromResponse(existingResponse, serviceTypeLabels);
                listener.onServiceNameDiscovered(info);
                if (existingResponse.isComplete()) {
                    listener.onServiceFound(info);
                    hadReply = true;
                }
            }
        }
        // Cancel the next scheduled periodical task.
        dependencies.removeMessages(handler, EVENT_START_QUERYTASK);
        // Keep tracking the ScheduledFuture for the task so we can cancel it if caller is not
        // interested anymore.
        final QueryTaskConfig taskConfig = new QueryTaskConfig(
                searchOptions.getSubtypes(),
                searchOptions.isPassiveMode(),
                searchOptions.onlyUseIpv6OnIpv6OnlyNetworks(),
                currentSessionId,
                socketKey);
        if (hadReply) {
            scheduleNextRun(taskConfig);
        } else {
            handleStartQueryTask(taskConfig);
        }
        if (shouldRemoveServiceAfterTtlExpires()) {
            serviceCache.registerServiceExpiredCallback(
                    serviceType, socketKey, serviceExpiredCallback);
        }
    }

    @GuardedBy("lock")
    private void cancelRequestTaskLocked() {
        dependencies.removeMessages(handler, EVENT_START_QUERYTASK);
        ++currentSessionId;
    }

    private void handleStartQueryTask(@NonNull QueryTaskConfig config) {
        // QueryTask should be run immediately after being created (not be scheduled in advance).
        // Because the result of "makeResponsesForResolveIfUnknown" depends on answers that were
        // received before it is called, so to take into account all answers before sending the
        // query, it needs to be called just before sending it.
        final List<MdnsResponse> servicesToResolve = makeResponsesForResolve(config.socketKey);
        final Future<?> unusedFuture = executor.submit(new QueryTask(config, servicesToResolve,
                servicesToResolve.size() < listeners.size() /* sendDiscoveryQueries */));
    }

    private boolean responseMatchesOptions(@NonNull MdnsResponse response,
            @NonNull MdnsSearchOptions options) {
        final boolean matchesInstanceName = options.getResolveInstanceName() == null
                // DNS is case-insensitive, so ignore case in the comparison
                || MdnsUtils.equalsIgnoreDnsCase(options.getResolveInstanceName(),
                response.getServiceInstanceName());

        // If discovery is requiring some subtypes, the response must have one that matches a
        // requested one.
        final List<String> responseSubtypes = response.getSubtypes() == null
                ? Collections.emptyList() : response.getSubtypes();
        final boolean matchesSubtype = options.getSubtypes().size() == 0
                || CollectionUtils.any(options.getSubtypes(), requiredSub ->
                CollectionUtils.any(responseSubtypes, actualSub ->
                        MdnsUtils.equalsIgnoreDnsCase(
                                MdnsConstants.SUBTYPE_PREFIX + requiredSub, actualSub)));

        return matchesInstanceName && matchesSubtype;
    }

    /**
     * Unregisters {@code listener} from receiving discovery event of mDNS service instances.
     *
     * @param listener The {@link MdnsServiceBrowserListener} to unregister.
     * @return {@code true} if no listener is registered with this client after unregistering {@code
     * listener}. Otherwise returns {@code false}.
     */
    public boolean stopSendAndReceive(@NonNull MdnsServiceBrowserListener listener) {
        ensureRunningOnHandlerThread(handler);
        if (listeners.remove(listener) == null) {
            return listeners.isEmpty();
        }
        if (listeners.isEmpty()) {
            cancelRequestTaskLocked();
            serviceCache.unregisterServiceExpiredCallback(serviceExpiredCallback);
        }
        return listeners.isEmpty();
    }

    /**
     * Process an incoming response packet.
     */
    public synchronized void processResponse(@NonNull MdnsPacket packet,
            @NonNull SocketKey socketKey) {
        ensureRunningOnHandlerThread(handler);
        // Augment the list of current known responses, and generated responses for resolve
        // requests if there is no known response
        final List<MdnsResponse> cachedList =
                serviceCache.getCachedServices(serviceType, socketKey);
        final List<MdnsResponse> currentList = new ArrayList<>(cachedList);
        List<MdnsResponse> additionalResponses = makeResponsesForResolve(socketKey);
        for (MdnsResponse additionalResponse : additionalResponses) {
            final String serviceInstanceName = additionalResponse.getServiceInstanceName();
            if (findMatchedResponse(cachedList, serviceInstanceName) == null) {
                currentList.add(additionalResponse);
            }
        }
        final Pair<ArraySet<MdnsResponse>, ArrayList<MdnsResponse>> augmentedResult =
                responseDecoder.augmentResponses(packet, currentList,
                        socketKey.getInterfaceIndex(), socketKey.getNetwork());

        final ArraySet<MdnsResponse> modifiedResponse = augmentedResult.first;
        final ArrayList<MdnsResponse> allResponses = augmentedResult.second;

        for (MdnsResponse response : allResponses) {
            final String serviceInstanceName = response.getServiceInstanceName();
            if (modifiedResponse.contains(response)) {
                if (response.isGoodbye()) {
                    onGoodbyeReceived(serviceInstanceName);
                } else {
                    onResponseModified(response);
                }
            } else if (findMatchedResponse(cachedList, serviceInstanceName) != null) {
                // If the response is not modified and already in the cache. The cache will
                // need to be updated to refresh the last receipt time.
                serviceCache.addOrUpdateService(serviceType, socketKey, response);
            }
        }
    }

    /**
     * Notify parsing response is failed.
     */
    public void onFailedToParseMdnsResponse(int receivedPacketNumber, int errorCode) {
        ensureRunningOnHandlerThread(handler);
        for (int i = 0; i < listeners.size(); i++) {
            listeners.keyAt(i).onFailedToParseMdnsResponse(receivedPacketNumber, errorCode);
        }
    }

    /** Notify all services are removed because the socket is destroyed. */
    public void notifySocketDestroyed() {
        ensureRunningOnHandlerThread(handler);
        for (MdnsResponse response : serviceCache.getCachedServices(serviceType, socketKey)) {
            final String name = response.getServiceInstanceName();
            if (name == null) continue;
            for (int i = 0; i < listeners.size(); i++) {
                if (!responseMatchesOptions(response, listeners.valueAt(i))) continue;
                final MdnsServiceBrowserListener listener = listeners.keyAt(i);
                final MdnsServiceInfo serviceInfo =
                        buildMdnsServiceInfoFromResponse(response, serviceTypeLabels);
                if (response.isComplete()) {
                    sharedLog.log("Socket destroyed. onServiceRemoved: " + name);
                    listener.onServiceRemoved(serviceInfo);
                }
                sharedLog.log("Socket destroyed. onServiceNameRemoved: " + name);
                listener.onServiceNameRemoved(serviceInfo);
            }
        }
        cancelRequestTaskLocked();
    }

    private void onResponseModified(@NonNull MdnsResponse response) {
        final String serviceInstanceName = response.getServiceInstanceName();
        final MdnsResponse currentResponse =
                serviceCache.getCachedService(serviceInstanceName, serviceType, socketKey);

        boolean newServiceFound = false;
        boolean serviceBecomesComplete = false;
        if (currentResponse == null) {
            newServiceFound = true;
            if (serviceInstanceName != null) {
                serviceCache.addOrUpdateService(serviceType, socketKey, response);
            }
        } else {
            boolean before = currentResponse.isComplete();
            serviceCache.addOrUpdateService(serviceType, socketKey, response);
            boolean after = response.isComplete();
            serviceBecomesComplete = !before && after;
        }
        sharedLog.i(String.format(
                "Handling response from service: %s, newServiceFound: %b, serviceBecomesComplete:"
                        + " %b, responseIsComplete: %b",
                serviceInstanceName, newServiceFound, serviceBecomesComplete,
                response.isComplete()));
        MdnsServiceInfo serviceInfo =
                buildMdnsServiceInfoFromResponse(response, serviceTypeLabels);

        for (int i = 0; i < listeners.size(); i++) {
            if (!responseMatchesOptions(response, listeners.valueAt(i))) continue;
            final MdnsServiceBrowserListener listener = listeners.keyAt(i);
            if (newServiceFound) {
                sharedLog.log("onServiceNameDiscovered: " + serviceInfo);
                listener.onServiceNameDiscovered(serviceInfo);
            }

            if (response.isComplete()) {
                if (newServiceFound || serviceBecomesComplete) {
                    sharedLog.log("onServiceFound: " + serviceInfo);
                    listener.onServiceFound(serviceInfo);
                } else {
                    sharedLog.log("onServiceUpdated: " + serviceInfo);
                    listener.onServiceUpdated(serviceInfo);
                }
            }
        }
    }

    private void onGoodbyeReceived(@Nullable String serviceInstanceName) {
        final MdnsResponse response =
                serviceCache.removeService(serviceInstanceName, serviceType, socketKey);
        if (response == null) {
            return;
        }
        for (int i = 0; i < listeners.size(); i++) {
            if (!responseMatchesOptions(response, listeners.valueAt(i))) continue;
            final MdnsServiceBrowserListener listener = listeners.keyAt(i);
            final MdnsServiceInfo serviceInfo =
                    buildMdnsServiceInfoFromResponse(response, serviceTypeLabels);
            if (response.isComplete()) {
                sharedLog.log("onServiceRemoved: " + serviceInfo);
                listener.onServiceRemoved(serviceInfo);
            }
            sharedLog.log("onServiceNameRemoved: " + serviceInfo);
            listener.onServiceNameRemoved(serviceInfo);
        }
    }

    private boolean shouldRemoveServiceAfterTtlExpires() {
        if (removeServiceAfterTtlExpires) {
            return true;
        }
        return searchOptions != null && searchOptions.removeExpiredService();
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
        private final boolean alwaysAskForUnicastResponse =
                MdnsConfigs.alwaysAskForUnicastResponseInEachBurst();
        private final boolean usePassiveMode;
        private final boolean onlyUseIpv6OnIpv6OnlyNetworks;
        private final long sessionId;
        @VisibleForTesting
        int transactionId;
        @VisibleForTesting
        boolean expectUnicastResponse;
        private int queriesPerBurst;
        private int timeBetweenBurstsInMs;
        private int burstCounter;
        private int timeToRunNextTaskInMs;
        private boolean isFirstBurst;
        @NonNull private final SocketKey socketKey;

        QueryTaskConfig(@NonNull Collection<String> subtypes,
                boolean usePassiveMode,
                boolean onlyUseIpv6OnIpv6OnlyNetworks,
                long sessionId,
                @Nullable SocketKey socketKey) {
            this.usePassiveMode = usePassiveMode;
            this.onlyUseIpv6OnIpv6OnlyNetworks = onlyUseIpv6OnIpv6OnlyNetworks;
            this.subtypes = new ArrayList<>(subtypes);
            this.queriesPerBurst = QUERIES_PER_BURST;
            this.burstCounter = 0;
            this.transactionId = 1;
            this.expectUnicastResponse = true;
            this.isFirstBurst = true;
            this.sessionId = sessionId;
            // Config the scan frequency based on the scan mode.
            if (this.usePassiveMode) {
                // In passive scan mode, sends a single burst of QUERIES_PER_BURST queries, and then
                // in each TIME_BETWEEN_BURSTS interval, sends QUERIES_PER_BURST_PASSIVE_MODE
                // queries.
                this.timeBetweenBurstsInMs = TIME_BETWEEN_BURSTS_MS;
            } else {
                // In active scan mode, sends a burst of QUERIES_PER_BURST queries,
                // TIME_BETWEEN_QUERIES_IN_BURST_MS apart, then waits for the scan interval, and
                // then repeats. The scan interval starts as INITIAL_TIME_BETWEEN_BURSTS_MS and
                // doubles until it maxes out at TIME_BETWEEN_BURSTS_MS.
                this.timeBetweenBurstsInMs = INITIAL_TIME_BETWEEN_BURSTS_MS;
            }
            this.socketKey = socketKey;
        }

        QueryTaskConfig getConfigForNextRun() {
            if (++transactionId > UNSIGNED_SHORT_MAX_VALUE) {
                transactionId = 1;
            }
            // Only the first query expects uni-cast response.
            expectUnicastResponse = false;
            if (++burstCounter == queriesPerBurst) {
                burstCounter = 0;

                if (alwaysAskForUnicastResponse) {
                    expectUnicastResponse = true;
                }
                // In passive scan mode, sends a single burst of QUERIES_PER_BURST queries, and
                // then in each TIME_BETWEEN_BURSTS interval, sends QUERIES_PER_BURST_PASSIVE_MODE
                // queries.
                if (isFirstBurst) {
                    isFirstBurst = false;
                    if (usePassiveMode) {
                        queriesPerBurst = QUERIES_PER_BURST_PASSIVE_MODE;
                    }
                }
                // In active scan mode, sends a burst of QUERIES_PER_BURST queries,
                // TIME_BETWEEN_QUERIES_IN_BURST_MS apart, then waits for the scan interval, and
                // then repeats. The scan interval starts as INITIAL_TIME_BETWEEN_BURSTS_MS and
                // doubles until it maxes out at TIME_BETWEEN_BURSTS_MS.
                timeToRunNextTaskInMs = timeBetweenBurstsInMs;
                if (timeBetweenBurstsInMs < TIME_BETWEEN_BURSTS_MS) {
                    timeBetweenBurstsInMs = Math.min(timeBetweenBurstsInMs * 2,
                            TIME_BETWEEN_BURSTS_MS);
                }
            } else {
                timeToRunNextTaskInMs = TIME_BETWEEN_QUERIES_IN_BURST_MS;
            }
            return this;
        }
    }

    private List<MdnsResponse> makeResponsesForResolve(@NonNull SocketKey socketKey) {
        final List<MdnsResponse> resolveResponses = new ArrayList<>();
        for (int i = 0; i < listeners.size(); i++) {
            final String resolveName = listeners.valueAt(i).getResolveInstanceName();
            if (resolveName == null) {
                continue;
            }
            MdnsResponse knownResponse =
                    serviceCache.getCachedService(resolveName, serviceType, socketKey);
            if (knownResponse == null) {
                final ArrayList<String> instanceFullName = new ArrayList<>(
                        serviceTypeLabels.length + 1);
                instanceFullName.add(resolveName);
                instanceFullName.addAll(Arrays.asList(serviceTypeLabels));
                knownResponse = new MdnsResponse(
                        0L /* lastUpdateTime */, instanceFullName.toArray(new String[0]),
                        socketKey.getInterfaceIndex(), socketKey.getNetwork());
            }
            resolveResponses.add(knownResponse);
        }
        return resolveResponses;
    }

    // A FutureTask that enqueues a single query, and schedule a new FutureTask for the next task.
    private class QueryTask implements Runnable {

        private final QueryTaskConfig config;
        private final List<MdnsResponse> servicesToResolve = new ArrayList<>();
        private final boolean sendDiscoveryQueries;

        QueryTask(@NonNull QueryTaskConfig config, @NonNull List<MdnsResponse> servicesToResolve,
                boolean sendDiscoveryQueries) {
            this.config = config;
            this.servicesToResolve.addAll(servicesToResolve);
            this.sendDiscoveryQueries = sendDiscoveryQueries;
        }

        @Override
        public void run() {
            final Pair<Integer, List<String>> result = new EnqueueMdnsQueryCallable(
                    socketClient,
                    createMdnsPacketWriter(),
                    serviceType,
                    config.subtypes,
                    config.expectUnicastResponse,
                    config.transactionId,
                    config.socketKey,
                    config.onlyUseIpv6OnIpv6OnlyNetworks,
                    sendDiscoveryQueries,
                    servicesToResolve,
                    clock).call();

            if (MdnsConfigs.useSessionIdToScheduleMdnsTask()) {
                // In case that the task is not canceled successfully, use session ID to check
                // if this task should continue to schedule more.
                if (config.sessionId != currentSessionId) {
                    return;
                }
            }

            dependencies.post(handler, () -> {
                if ((result != null)) {
                    for (int i = 0; i < listeners.size(); i++) {
                        listeners.keyAt(i).onDiscoveryQuerySent(result.second, result.first);
                    }
                }
                scheduleNextRun(this.config);
            });
        }
    }

    @NonNull
    private void scheduleNextRun(@NonNull QueryTaskConfig lastRunConfig) {
        QueryTaskConfig config = lastRunConfig.getConfigForNextRun();
        dependencies.sendMessageDelayed(
                handler,
                handler.obtainMessage(EVENT_START_QUERYTASK, config),
                config.timeToRunNextTaskInMs);
    }
}