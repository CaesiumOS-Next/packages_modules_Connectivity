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
    static final int INVALID_TRANSACTION_ID = -1;

    private final String serviceType;
    private final String[] serviceTypeLabels;
    private final MdnsSocketClientBase socketClient;
    private final MdnsResponseDecoder responseDecoder;
    private final ScheduledExecutorService executor;
    @NonNull private final SocketKey socketKey;
    @NonNull private final SharedLog sharedLog;
    @NonNull private final Looper looper;
    /**
     * The service caches for each socket. It should be accessed from looper thread only.
     */
    @NonNull private final MdnsServiceCache serviceCache;
    @NonNull private final MdnsServiceCache.CacheKey cacheKey;
    @NonNull private final ServiceExpiredCallback serviceExpiredCallback =
            new ServiceExpiredCallback() {
                @Override
                public void onServiceRecordExpired(@NonNull MdnsResponse previousResponse,
                        @Nullable MdnsResponse newResponse) {
                    notifyRemovedServiceToListeners(previousResponse, "Service record expired");
                }
            };
    @NonNull private final MdnsQuerier mdnsQuerier;
    private final ArrayMap<MdnsServiceBrowserListener, MdnsSearchOptions> listeners =
            new ArrayMap<>();
    private final boolean removeServiceAfterTtlExpires =
            MdnsConfigs.removeServiceAfterTtlExpires();
    private final Clock clock;

    @Nullable private MdnsSearchOptions searchOptions;
    @NonNull private final MdnsQuerier.QueryCallback queryCallback =
            (transactionId, subtypes) -> {
                if ((transactionId != INVALID_TRANSACTION_ID)) {
                    for (int i = 0; i < listeners.size(); i++) {
                        listeners.keyAt(i).onDiscoveryQuerySent(subtypes,
                                transactionId);
                    }
                }
                tryRemoveServiceAfterTtlExpires();
            };

    /**
     * Dependencies of MdnsServiceTypeClient, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        /**
         * @see MdnsQuerier
         */
        public MdnsQuerier makeMdnsQuerier(@NonNull String serviceType,
                @NonNull MdnsSocketClientBase socketClient,
                @NonNull ScheduledExecutorService executor, @NonNull Clock clock,
                @NonNull SharedLog sharedLog, @NonNull Looper looper,
                @NonNull MdnsServiceCache serviceCache, @NonNull MdnsServiceCache.CacheKey cacheKey,
                @NonNull MdnsQuerier.QueryCallback callback, @NonNull MdnsSender sender) {
            return new MdnsQuerier(serviceType, socketClient, executor, clock, sharedLog, looper,
                    serviceCache, cacheKey, callback, sender);
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
        this.serviceCache = serviceCache;
        this.cacheKey = new MdnsServiceCache.CacheKey(serviceType, socketKey);
        this.mdnsQuerier = dependencies.makeMdnsQuerier(serviceType, socketClient, executor, clock,
                sharedLog, looper, serviceCache, cacheKey, queryCallback, sender);
    }

    /**
     * Do the cleanup of the MdnsServiceTypeClient
     */
    private void shutDown() {
        mdnsQuerier.cancelAll();
        serviceCache.unregisterServiceExpiredCallback(cacheKey);
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

    private List<String> makeResolveInstanceNames() {
        final List<String> resolveInstanceNames = new ArrayList<>();
        for (int i = 0; i < listeners.size(); i++) {
            final String resolveName = listeners.valueAt(i).getResolveInstanceName();
            resolveInstanceNames.add(resolveName);
        }
        return resolveInstanceNames;
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
                    serviceCache.getCachedServices(cacheKey)) {
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
        mdnsQuerier.cancelAll();
        // Keep tracking the ScheduledFuture for the task so we can cancel it if caller is not
        // interested anymore.
        final QueryTaskConfig taskConfig = new QueryTaskConfig(
                searchOptions.getSubtypes(),
                searchOptions.isPassiveMode(),
                searchOptions.onlyUseIpv6OnIpv6OnlyNetworks(),
                searchOptions.numOfQueriesBeforeBackoff(),
                socketKey);
        mdnsQuerier.scheduleQueryTask(taskConfig, makeResolveInstanceNames(), hadReply);
        serviceCache.registerServiceExpiredCallback(cacheKey, serviceExpiredCallback);
    }

    /**
     * Get the executor service.
     */
    public ScheduledExecutorService getExecutor() {
        return executor;
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
            shutDown();
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
        final List<MdnsResponse> cachedList = serviceCache.getCachedServices(cacheKey);
        final List<MdnsResponse> currentList = new ArrayList<>(cachedList);
        final List<MdnsResponse> additionalResponses = serviceCache.makeResponsesForResolve(
                makeResolveInstanceNames(), cacheKey, serviceTypeLabels);
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
                serviceCache.addOrUpdateService(cacheKey, response);
            }
        }
        mdnsQuerier.rescheduleQueryTaskIfNecessary();
    }

    public synchronized void onFailedToParseMdnsResponse(int receivedPacketNumber, int errorCode) {
        ensureRunningOnLooperThread(looper);
        for (int i = 0; i < listeners.size(); i++) {
            listeners.keyAt(i).onFailedToParseMdnsResponse(receivedPacketNumber, errorCode);
        }
    }

    private void notifyRemovedServiceToListeners(@NonNull MdnsResponse response,
            @NonNull String message) {
        for (int i = 0; i < listeners.size(); i++) {
            if (!responseMatchesOptions(response, listeners.valueAt(i))) continue;
            final MdnsServiceBrowserListener listener = listeners.keyAt(i);
            if (response.getServiceInstanceName() != null) {
                final MdnsServiceInfo serviceInfo = buildMdnsServiceInfoFromResponse(
                        response, serviceTypeLabels);
                if (response.isComplete()) {
                    sharedLog.log(message + ". onServiceRemoved: " + serviceInfo);
                    listener.onServiceRemoved(serviceInfo);
                }
                sharedLog.log(message + ". onServiceNameRemoved: " + serviceInfo);
                listener.onServiceNameRemoved(serviceInfo);
            }
        }
    }

    /** Notify all services are removed because the socket is destroyed. */
    public void notifySocketDestroyed() {
        ensureRunningOnLooperThread(looper);
        for (MdnsResponse response : serviceCache.getCachedServices(cacheKey)) {
            final String name = response.getServiceInstanceName();
            if (name == null) continue;
            notifyRemovedServiceToListeners(response, "Socket destroyed");
        }
        shutDown();
    }

    private void onResponseModified(@NonNull MdnsResponse response) {
        final String serviceInstanceName = response.getServiceInstanceName();
        final MdnsResponse currentResponse =
                serviceCache.getCachedService(serviceInstanceName, cacheKey);

        boolean newServiceFound = false;
        boolean serviceBecomesComplete = false;
        if (currentResponse == null) {
            newServiceFound = true;
            if (serviceInstanceName != null) {
                serviceCache.addOrUpdateService(cacheKey, response);
            }
        } else {
            boolean before = currentResponse.isComplete();
            serviceCache.addOrUpdateService(cacheKey, response);
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
                serviceCache.removeService(serviceInstanceName, cacheKey);
        if (response == null) {
            return;
        }
        notifyRemovedServiceToListeners(response, "Goodbye received");
    }

    private boolean shouldRemoveServiceAfterTtlExpires() {
        if (removeServiceAfterTtlExpires) {
            return true;
        }
        return searchOptions != null && searchOptions.removeExpiredService();
    }

    private void tryRemoveServiceAfterTtlExpires() {
        if (!shouldRemoveServiceAfterTtlExpires()) return;

        final Iterator<MdnsResponse> iter = serviceCache.getCachedServices(cacheKey).iterator();
        while (iter.hasNext()) {
            MdnsResponse existingResponse = iter.next();
            if (existingResponse.hasServiceRecord()
                    && existingResponse.getServiceRecord()
                    .getRemainingTTL(clock.elapsedRealtime()) == 0) {
                serviceCache.removeService(existingResponse.getServiceInstanceName(), cacheKey);
                notifyRemovedServiceToListeners(existingResponse, "TTL expired");
            }
        }
    }
}