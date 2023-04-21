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

package com.android.server.connectivity.mdns;

import static com.android.server.connectivity.mdns.MdnsSocketProvider.ensureRunningOnHandlerThread;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The {@link MdnsServiceCache} manages the service which discovers from each socket and cache these
 * services to reduce duplicated queries.
 *
 * <p>This class is not thread safe, it is intended to be used only from the looper thread.
 *  However, the constructor is an exception, as it is called on another thread;
 *  therefore for thread safety all members of this class MUST either be final or initialized
 *  to their default value (0, false or null).
 */
public class MdnsServiceCache {
    // RFC6762.10 the recommended TTL value for Multicast DNS resource records with a host name as
    // the resource record's name (e.g., A, AAAA, HINFO) or a host name contained within the
    // resource record's rdata (e.g., SRV, reverse mapping PTR record) SHOULD be 120 seconds.
    private static final long DEFAULT_TTL_TIME_MS = 120000L;
    private static class CacheKey {
        @NonNull final String mLowercaseServiceType;
        @Nullable final Network mNetwork;

        CacheKey(@NonNull String serviceType, @Nullable Network network) {
            mLowercaseServiceType = serviceType.toLowerCase();
            mNetwork = network;
        }

        @Override public int hashCode() {
            return Objects.hash(mLowercaseServiceType, mNetwork);
        }
        @Override public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof CacheKey)) {
                return false;
            }
            return Objects.equals(mLowercaseServiceType, ((CacheKey) other).mLowercaseServiceType)
                    && Objects.equals(mNetwork, ((CacheKey) other).mNetwork);
        }
    }
    /**
     * A map of cached services. Key is composed of service name, type and network. Value is the
     * service which use the service type to discover from each socket.
     */
    private final ArrayMap<CacheKey, TreeSet<MdnsResponse>> mCachedServices = new ArrayMap<>();
    /**
     * A map of service expire callbacks. Key is the callback listener and value is the listened
     * service type.
     */
    private final ArrayMap<ServiceExpiredCallback, CacheKey> mCallbacks = new ArrayMap<>();
    private final Handler mHandler;
    private final MdnsResponseDecoder.Clock mClock;

    public MdnsServiceCache(@NonNull Looper looper) {
        this(looper, new MdnsResponseDecoder.Clock());
    }

    @VisibleForTesting
    MdnsServiceCache(@NonNull Looper looper, @NonNull MdnsResponseDecoder.Clock clock) {
        mHandler = new Handler(looper);
        mClock = clock;
        mHandler.postDelayed(this::checkAndNotifyTtlExpiredServices, DEFAULT_TTL_TIME_MS);
    }

    /**
     * Get the cache services which are queried from given service type and network.
     *
     * @param serviceType the target service type.
     * @param network the target network
     * @return the set of services which matches the given service type.
     */
    @NonNull
    public Set<MdnsResponse> getCachedServices(@NonNull String serviceType,
            @Nullable Network network) {
        ensureRunningOnHandlerThread(mHandler);
        final CacheKey key = new CacheKey(serviceType, network);
        return mCachedServices.containsKey(key)
                ? Collections.unmodifiableSet(mCachedServices.get(key))
                : new ArraySet<>();
    }

    private MdnsResponse findMatchedResponse(@NonNull Set<MdnsResponse> responses,
            @NonNull String serviceName) {
        for (MdnsResponse response : responses) {
            if (serviceName.equalsIgnoreCase(response.getServiceInstanceName())) {
                return response;
            }
        }
        return null;
    }

    /**
     * Get the cache service.
     *
     * @param serviceName the target service name.
     * @param serviceType the target service type.
     * @param network the target network
     * @return the service which matches given conditions.
     */
    @Nullable
    public MdnsResponse getCachedService(@NonNull String serviceName,
            @NonNull String serviceType, @Nullable Network network) {
        ensureRunningOnHandlerThread(mHandler);
        final Set<MdnsResponse> responses = mCachedServices.get(new CacheKey(serviceType, network));
        if (responses == null) {
            return null;
        }
        final MdnsResponse response = findMatchedResponse(responses, serviceName);
        return response != null ? new MdnsResponse(response) : null;
    }

    /**
     * Put a service.
     *
     * @param serviceType the service type.
     * @param network the target network.
     * @param response the response of the discovered service.
     */
    public void addService(@NonNull String serviceType, @Nullable Network network,
            @NonNull MdnsResponse response) {
        ensureRunningOnHandlerThread(mHandler);
        final Set<MdnsResponse> responses = mCachedServices.computeIfAbsent(
                new CacheKey(serviceType, network),
                key -> new TreeSet<>(new ServiceTtlComparator(mClock)));
        // Remove existing service if present.
        final MdnsResponse existing =
                findMatchedResponse(responses, response.getServiceInstanceName());
        responses.remove(existing);
        responses.add(response);
    }

    /**
     * Remove a service which matches the given service name, type and network.
     *
     * @param serviceName the target service name.
     * @param serviceType the target service type.
     * @param network the target network.
     */
    @Nullable
    public MdnsResponse removeService(@NonNull String serviceName, @NonNull String serviceType,
            @Nullable Network network) {
        ensureRunningOnHandlerThread(mHandler);
        final Set<MdnsResponse> responses = mCachedServices.get(new CacheKey(serviceType, network));
        if (responses == null) {
            return null;
        }
        final Iterator<MdnsResponse> iterator = responses.iterator();
        while (iterator.hasNext()) {
            final MdnsResponse response = iterator.next();
            if (serviceName.equalsIgnoreCase(response.getServiceInstanceName())) {
                iterator.remove();
                return response;
            }
        }
        return null;
    }

    /**
     * Register a callback to listen service expiration.
     *
     * @param serviceType the target service type.
     * @param network the target network.
     * @param callback the callback that notify the service is expired.
     */
    public void registerServiceExpiredCallback(@NonNull String serviceType,
            @Nullable Network network, @NonNull ServiceExpiredCallback callback) {
        ensureRunningOnHandlerThread(mHandler);
        mCallbacks.put(callback, new CacheKey(serviceType, network));
    }

    /*** Unregister the service expired callback. */
    public void unregisterServiceExpiredCallback(
            @NonNull ServiceExpiredCallback callback) {
        ensureRunningOnHandlerThread(mHandler);
        mCallbacks.remove(callback);
    }

    private void notifyServiceExpired(@NonNull CacheKey cacheKey,
            @NonNull MdnsResponse response) {
        for (int i = 0; i < mCallbacks.size(); i++) {
            final CacheKey listenedCacheKey = mCallbacks.valueAt(i);
            if (cacheKey.equals(listenedCacheKey)) {
                mCallbacks.keyAt(i).onServiceExpired(response);
            }
        }
    }

    /*** Check whether the ttl time is expired on each service and notify to the listeners */
    @VisibleForTesting
    void checkAndNotifyTtlExpiredServices() {
        final long now = mClock.elapsedRealtime();
        long nextCheckDelay = DEFAULT_TTL_TIME_MS;
        for (int i = 0; i < mCachedServices.size(); i++) {
            final CacheKey key = mCachedServices.keyAt(i);
            final TreeSet<MdnsResponse> responses = mCachedServices.valueAt(i);
            final Iterator<MdnsResponse> iterator = responses.iterator();
            while (iterator.hasNext()) {
                final MdnsResponse response = iterator.next();
                if (response.hasServiceRecord()
                        && response.getServiceRecord().getRemainingTTL(now) == 0) {
                    // Remove the ttl expired service.
                    iterator.remove();
                    notifyServiceExpired(key, response);
                }
            }

            if (responses.isEmpty()) {
                // Remove the serviceType if no response.
                mCachedServices.removeAt(i);
            } else {
                // Update the next check delay
                final long ttl = responses.first().getServiceRecord().getRemainingTTL(now);
                if (ttl < nextCheckDelay) {
                    // Don't trigger expiration check frequently. Consider a tolerance of +50ms.
                    nextCheckDelay = ttl < 50L ? 50L : ttl;
                }
            }
        }
        mHandler.postDelayed(this::checkAndNotifyTtlExpiredServices, nextCheckDelay);
    }

    /*** Comparator for MdnsResponse's order */
    private static class ServiceTtlComparator implements Comparator<MdnsResponse> {
        private final MdnsResponseDecoder.Clock mClock;

        ServiceTtlComparator(@NonNull MdnsResponseDecoder.Clock clock) {
            mClock = clock;
        }

        @Override
        public int compare(MdnsResponse o1, MdnsResponse o2) {
            // Can't do comparison if any of response or service record is null.
            if (o1 == null || o2 == null
                    || !o1.hasServiceRecord() || !o2.hasServiceRecord()) {
                return -1;
            }

            final long now = mClock.elapsedRealtime();
            final long diff = (o1.getServiceRecord().getRemainingTTL(now)
                    - o2.getServiceRecord().getRemainingTTL(now));
            // Order by remaining ttl time
            if (diff != 0L) {
                return (int) diff;
            }
            // If the remaining ttl time is same then check the service instance name to determine
            // whether they are same response.
            return o1.getServiceInstanceName().equals(o2.getServiceInstanceName()) ? 0 : -1;
        }
    }

    /*** Callbacks for listening service expiration */
    public interface ServiceExpiredCallback {
        /*** Notify the service is expired */
        default void onServiceExpired(@NonNull MdnsResponse response) { }
    }
}
