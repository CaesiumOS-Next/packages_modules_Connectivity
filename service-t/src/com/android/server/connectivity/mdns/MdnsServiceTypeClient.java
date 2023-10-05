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

import static com.android.server.connectivity.mdns.MdnsServiceCache.findMatchedResponse;
import static com.android.server.connectivity.mdns.util.MdnsUtils.Clock;
import static com.android.server.connectivity.mdns.util.MdnsUtils.ensureRunningOnLooperThread;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Looper;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.CollectionUtils;
import com.android.net.module.util.SharedLog;
import com.android.server.connectivity.mdns.util.MdnsUtils;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Instance of this class sends and receives mDNS packets of a given service type and invoke
 * registered {@link MdnsServiceBrowserListener} instances.
 */
public class MdnsServiceTypeClient {

    private static final String TAG = MdnsServiceTypeClient.class.getSimpleName();
    private static final int DEFAULT_MTU = 1500;
    @VisibleForTesting
    static final int EVENT_START_QUERYTASK = 1;
    static final int EVENT_QUERY_RESULT = 2;
    static final int INVALID_TRANSACTION_ID = -1;

    private final String serviceType;
    private final String[] serviceTypeLabels;
    private final MdnsSocketClientBase socketClient;
    private final MdnsResponseDecoder responseDecoder;
    private final ScheduledExecutorService executor;
    @NonNull private final SocketKey socketKey;
    @NonNull private final SharedLog sharedLog;
    @NonNull private final Looper looper;
    @NonNull private final MdnsQueryScheduler mdnsQueryScheduler;
    @NonNull private final Dependencies dependencies;
    /**
     * The service caches for each socket. It should be accessed from looper thread only.
     */
    @NonNull private final MdnsServiceCache serviceCache;
    @NonNull private final MdnsQuerier mdnsQuerier;
    private final ArrayMap<MdnsServiceBrowserListener, MdnsSearchOptions> listeners =
            new ArrayMap<>();
    private final boolean removeServiceAfterTtlExpires =
            MdnsConfigs.removeServiceAfterTtlExpires();
    private final Clock clock;

    @Nullable private MdnsSearchOptions searchOptions;

    // The session ID increases when startSendAndReceive() is called where we schedule a
    // QueryTask for
    // new subtypes. It stays the same between packets for same subtypes.
    private long currentSessionId = 0;
    private long lastSentTime;

    /**
     * Dependencies of MdnsServiceTypeClient, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
    }

    public class QueryInfo implements MdnsPacketRepeater.Request {
        private final MdnsQueryScheduler.ScheduledQueryTaskArgs taskArgs;

        public QueryInfo(@NonNull MdnsQueryScheduler.ScheduledQueryTaskArgs taskArgs) {
            this.taskArgs = taskArgs;
        }

        @NonNull
        @Override
        public MdnsPacket getPacket(int index) {
            final List<MdnsRecord> questions = new ArrayList<>();
            final List<MdnsResponse> servicesToResolve = makeResponsesForResolve(socketKey);
            final boolean sendDiscoveryQueries = servicesToResolve.size() < listeners.size();
            final boolean unicast = taskArgs.config.expectUnicastResponse;

            if (sendDiscoveryQueries) {
                // Base service type
                questions.add(new MdnsPointerRecord(serviceTypeLabels, unicast));
                if (!taskArgs.config.subtypes.isEmpty()) {
                    for (String subtype : taskArgs.config.subtypes) {
                        String[] labels = new String[serviceTypeLabels.length + 2];
                        labels[0] = MdnsConstants.SUBTYPE_PREFIX + subtype;
                        labels[1] = MdnsConstants.SUBTYPE_LABEL;
                        System.arraycopy(serviceTypeLabels, 0, labels, 2, serviceTypeLabels.length);
                        questions.add(new MdnsPointerRecord(labels, unicast));
                    }
                }
            }

            // List of (name, type) to query
            final long now = clock.elapsedRealtime();
            for (MdnsResponse response : servicesToResolve) {
                final String[] serviceName = response.getServiceName();
                if (serviceName == null) continue;
                boolean renewTxt = !response.hasTextRecord() || MdnsUtils.isRecordRenewalNeeded(
                        response.getTextRecord(), now);
                boolean renewSrv = !response.hasServiceRecord() || MdnsUtils.isRecordRenewalNeeded(
                        response.getServiceRecord(), now);
                if (renewSrv && renewTxt) {
                    questions.add(new MdnsAnyRecord(serviceName, unicast));
                } else {
                    if (renewTxt) {
                        questions.add(new MdnsTextRecord(serviceName, unicast));
                    }
                    if (renewSrv) {
                        questions.add(new MdnsServiceRecord(serviceName, unicast));
                        // The hostname is not yet known, so queries for address records will be
                        // sent the next time the EnqueueMdnsQueryCallable is enqueued if the reply
                        // does not contain them. In practice, advertisers should include the
                        // address records when queried for SRV, although it's not a MUST
                        // requirement (RFC6763 12.2).
                    } else if (!response.hasInet4AddressRecord()
                            && !response.hasInet6AddressRecord()) {
                        final String[] host = response.getServiceRecord().getServiceHost();
                        questions.add(new MdnsInetAddressRecord(host, MdnsRecord.TYPE_A, unicast));
                        questions.add(new MdnsInetAddressRecord(
                                host, MdnsRecord.TYPE_AAAA, unicast));
                    }
                }
            }

            final MdnsPacket mdnsPacket = new MdnsPacket(
                    taskArgs.config.transactionId,
                    MdnsConstants.FLAGS_QUERY,
                    questions,
                    Collections.emptyList(), /* answers */
                    Collections.emptyList(), /* authorityRecords */
                    Collections.emptyList() /* additionalRecords */);
            return mdnsPacket;
        }

        @Override
        public long getDelayMs(int nextIndex) {
            final long now = clock.elapsedRealtime();
            lastSentTime = now;
            final long minRemainingTtl = getMinRemainingTtl(now);
            MdnsQueryScheduler.ScheduledQueryTaskArgs args =
                    mdnsQueryScheduler.scheduleNextRun(
                            taskArgs.config,
                            minRemainingTtl,
                            now,
                            lastSentTime,
                            taskArgs.sessionId
                    );
            return calculateTimeToNextTask(args, now, sharedLog);
        }

        @Override
        public int getNumSends() {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean onlyUseIpv6OnIpv6OnlyNetworks() {
            return searchOptions.onlyUseIpv6OnIpv6OnlyNetworks();
        }

        @Override
        public boolean expectUnicastResponse() {
            return taskArgs.config.expectUnicastResponse;
        }
    }

    private class QueryingCallback implements MdnsPacketRepeater.PacketRepeaterCallback<QueryInfo> {
        @Override
        public void onSent(int index, @NonNull QueryInfo info, int sentPacketCount) {
            if (info.taskArgs.sessionId != currentSessionId) {
                return;
            }

            for (int i = 0; i < listeners.size(); i++) {
                listeners.keyAt(i).onDiscoveryQuerySent(
                        info.taskArgs.config.subtypes, info.taskArgs.config.transactionId);
            }

            tryRemoveServiceAfterTtlExpires();
        }
    }

    private class MdnsQuerier extends MdnsPacketRepeater<QueryInfo> {
        MdnsQuerier(@NonNull Looper looper,
                @NonNull MdnsSender sender,
                @Nullable PacketRepeaterCallback<QueryInfo> cb,
                @NonNull SharedLog sharedLog) {
            super(looper, sender, cb, sharedLog);
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
            @NonNull MdnsServiceCache serviceCache,
            @NonNull MdnsSender sender) {
        this(serviceType, socketClient, executor, new Clock(), socketKey, sharedLog, looper,
                new Dependencies(), serviceCache, sender);
    }

    @VisibleForTesting
    public MdnsServiceTypeClient(
            @NonNull String serviceType,
            @NonNull MdnsSocketClientBase socketClient,
            @NonNull ScheduledExecutorService executor,
            @NonNull Clock clock,
            @NonNull SocketKey socketKey,
            @NonNull SharedLog sharedLog,
            @NonNull Looper looper,
            @NonNull Dependencies dependencies,
            @NonNull MdnsServiceCache serviceCache,
            @NonNull MdnsSender sender) {
        this.serviceType = serviceType;
        this.socketClient = socketClient;
        this.executor = executor;
        this.serviceTypeLabels = TextUtils.split(serviceType, "\\.");
        this.responseDecoder = new MdnsResponseDecoder(clock, serviceTypeLabels);
        this.clock = clock;
        this.socketKey = socketKey;
        this.sharedLog = sharedLog;
        this.looper = looper;
        this.dependencies = dependencies;
        this.serviceCache = serviceCache;
        this.mdnsQueryScheduler = new MdnsQueryScheduler();
        this.mdnsQuerier = new MdnsQuerier(looper, sender, new QueryingCallback(), sharedLog);
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
    @SuppressWarnings("FutureReturnValueIgnored")
    public void startSendAndReceive(
            @NonNull MdnsServiceBrowserListener listener,
            @NonNull MdnsSearchOptions searchOptions) {
        ensureRunningOnLooperThread(looper);
        this.searchOptions = searchOptions;
        boolean hadReply = false;
        if (listeners.put(listener, searchOptions) == null) {
            for (MdnsResponse existingResponse :
                    serviceCache.getCachedServices(serviceType, socketKey)) {
                if (!responseMatchesOptions(existingResponse, searchOptions)) continue;
                final MdnsServiceInfo info =
                        buildMdnsServiceInfoFromResponse(existingResponse, serviceTypeLabels);
                listener.onServiceNameDiscovered(info, true /* isServiceFromCache */);
                if (existingResponse.isComplete()) {
                    listener.onServiceFound(info, true /* isServiceFromCache */);
                    hadReply = true;
                }
            }
        }
        // Remove the next scheduled periodical task.
        removeScheduledTask();
        mdnsQueryScheduler.cancelScheduledRun();
        // Keep tracking the ScheduledFuture for the task so we can cancel it if caller is not
        // interested anymore.
        final QueryTaskConfig taskConfig = new QueryTaskConfig(
                searchOptions.getSubtypes(),
                searchOptions.isPassiveMode(),
                searchOptions.onlyUseIpv6OnIpv6OnlyNetworks(),
                searchOptions.numOfQueriesBeforeBackoff(),
                socketKey);
        final long now = clock.elapsedRealtime();
        if (lastSentTime == 0) {
            lastSentTime = now;
        }
        final long minRemainingTtl = getMinRemainingTtl(now);
        if (hadReply) {
            MdnsQueryScheduler.ScheduledQueryTaskArgs args =
                    mdnsQueryScheduler.scheduleNextRun(
                            taskConfig,
                            minRemainingTtl,
                            now,
                            lastSentTime,
                            currentSessionId
                    );
            mdnsQuerier.startSending((int) currentSessionId, new QueryInfo(args),
                    calculateTimeToNextTask(args, now, sharedLog));
        } else {
            final MdnsQueryScheduler.ScheduledQueryTaskArgs args =
                    mdnsQueryScheduler.scheduleFirstRun(
                            taskConfig, now, minRemainingTtl, currentSessionId);
            mdnsQuerier.startSending((int) currentSessionId, new QueryInfo(args),
                    calculateTimeToNextTask(args, now, sharedLog));
        }
    }

    /**
     * Get the executor service.
     */
    public ScheduledExecutorService getExecutor() {
        return executor;
    }

    private void removeScheduledTask() {
        mdnsQuerier.stop((int) currentSessionId);
        sharedLog.log("Stop MdnsQuerier, current session: " + currentSessionId);
        ++currentSessionId;
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
        ensureRunningOnLooperThread(looper);
        if (listeners.remove(listener) == null) {
            return listeners.isEmpty();
        }
        if (listeners.isEmpty()) {
            removeScheduledTask();
            mdnsQueryScheduler.cancelScheduledRun();
        }
        return listeners.isEmpty();
    }

    /**
     * Process an incoming response packet.
     */
    public synchronized void processResponse(@NonNull MdnsPacket packet,
            @NonNull SocketKey socketKey) {
        ensureRunningOnLooperThread(looper);
        // Augment the list of current known responses, and generated responses for resolve
        // requests if there is no known response
        final List<MdnsResponse> cachedList =
                serviceCache.getCachedServices(serviceType, socketKey);
        final List<MdnsResponse> currentList = new ArrayList<>(cachedList);
        List<MdnsResponse> additionalResponses = makeResponsesForResolve(socketKey);
        for (MdnsResponse additionalResponse : additionalResponses) {
            if (findMatchedResponse(
                    cachedList, additionalResponse.getServiceInstanceName()) == null) {
                currentList.add(additionalResponse);
            }
        }
        final Pair<Set<MdnsResponse>, ArrayList<MdnsResponse>> augmentedResult =
                responseDecoder.augmentResponses(packet, currentList,
                        socketKey.getInterfaceIndex(), socketKey.getNetwork());

        final Set<MdnsResponse> modifiedResponse = augmentedResult.first;
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
        if (mdnsQuerier.hasTaskId((int) currentSessionId)) {
            final long now = clock.elapsedRealtime();
            final long minRemainingTtl = getMinRemainingTtl(now);
            MdnsQueryScheduler.ScheduledQueryTaskArgs args =
                    mdnsQueryScheduler.maybeRescheduleCurrentRun(now, minRemainingTtl,
                            lastSentTime, currentSessionId + 1);
            if (args != null) {
                removeScheduledTask();
                mdnsQuerier.startSending((int) currentSessionId, new QueryInfo(args),
                        calculateTimeToNextTask(args, now, sharedLog));
            }
        }
    }

    public synchronized void onFailedToParseMdnsResponse(int receivedPacketNumber, int errorCode) {
        ensureRunningOnLooperThread(looper);
        for (int i = 0; i < listeners.size(); i++) {
            listeners.keyAt(i).onFailedToParseMdnsResponse(receivedPacketNumber, errorCode);
        }
    }

    /** Notify all services are removed because the socket is destroyed. */
    public void notifySocketDestroyed() {
        ensureRunningOnLooperThread(looper);
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
        removeScheduledTask();
        mdnsQueryScheduler.cancelScheduledRun();
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
                listener.onServiceNameDiscovered(serviceInfo, false /* isServiceFromCache */);
            }

            if (response.isComplete()) {
                if (newServiceFound || serviceBecomesComplete) {
                    sharedLog.log("onServiceFound: " + serviceInfo);
                    listener.onServiceFound(serviceInfo, false /* isServiceFromCache */);
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

    private void tryRemoveServiceAfterTtlExpires() {
        if (!shouldRemoveServiceAfterTtlExpires()) return;

        Iterator<MdnsResponse> iter =
                serviceCache.getCachedServices(serviceType, socketKey).iterator();
        while (iter.hasNext()) {
            MdnsResponse existingResponse = iter.next();
            final String serviceInstanceName = existingResponse.getServiceInstanceName();
            if (existingResponse.hasServiceRecord()
                    && existingResponse.getServiceRecord()
                    .getRemainingTTL(clock.elapsedRealtime()) == 0) {
                serviceCache.removeService(serviceInstanceName, serviceType, socketKey);
                for (int i = 0; i < listeners.size(); i++) {
                    if (!responseMatchesOptions(existingResponse, listeners.valueAt(i))) {
                        continue;
                    }
                    final MdnsServiceBrowserListener listener = listeners.keyAt(i);
                    if (serviceInstanceName != null) {
                        final MdnsServiceInfo serviceInfo = buildMdnsServiceInfoFromResponse(
                                existingResponse, serviceTypeLabels);
                        if (existingResponse.isComplete()) {
                            sharedLog.log("TTL expired. onServiceRemoved: " + serviceInfo);
                            listener.onServiceRemoved(serviceInfo);
                        }
                        sharedLog.log("TTL expired. onServiceNameRemoved: " + serviceInfo);
                        listener.onServiceNameRemoved(serviceInfo);
                    }
                }
            }
        }
    }

    private long getMinRemainingTtl(long now) {
        long minRemainingTtl = Long.MAX_VALUE;
        for (MdnsResponse response : serviceCache.getCachedServices(serviceType, socketKey)) {
            if (!response.isComplete()) {
                continue;
            }
            long remainingTtl =
                    response.getServiceRecord().getRemainingTTL(now);
            // remainingTtl is <= 0 means the service expired.
            if (remainingTtl <= 0) {
                return 0;
            }
            if (remainingTtl < minRemainingTtl) {
                minRemainingTtl = remainingTtl;
            }
        }
        return minRemainingTtl == Long.MAX_VALUE ? 0 : minRemainingTtl;
    }

    private static long calculateTimeToNextTask(MdnsQueryScheduler.ScheduledQueryTaskArgs args,
            long now, SharedLog sharedLog) {
        long timeToNextTasksWithBackoffInMs = Math.max(args.timeToRun - now, 0);
        sharedLog.log(String.format("Next run: sessionId: %d, in %d ms",
                args.sessionId, timeToNextTasksWithBackoffInMs));
        return timeToNextTasksWithBackoffInMs;
    }
}