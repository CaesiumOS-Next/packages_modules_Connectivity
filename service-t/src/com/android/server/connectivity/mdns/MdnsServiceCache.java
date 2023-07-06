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

import static com.android.server.connectivity.mdns.MdnsResponse.EXPIRATION_NEVER;
import static com.android.server.connectivity.mdns.util.MdnsUtils.ensureRunningOnHandlerThread;
import static com.android.server.connectivity.mdns.util.MdnsUtils.equalsIgnoreDnsCase;
import static com.android.server.connectivity.mdns.util.MdnsUtils.toDnsLowerCase;

import static java.lang.Math.min;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Handler;
import android.os.Looper;
import android.util.ArrayMap;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.connectivity.mdns.util.MdnsUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

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
    private static class CacheKey {
        @NonNull final String mLowercaseServiceType;
        @NonNull final SocketKey mSocketKey;

        CacheKey(@NonNull String serviceType, @NonNull SocketKey socketKey) {
            mLowercaseServiceType = toDnsLowerCase(serviceType);
            mSocketKey = socketKey;
        }

        @Override public int hashCode() {
            return Objects.hash(mLowercaseServiceType, mSocketKey);
        }

        @Override public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof CacheKey)) {
                return false;
            }
            return Objects.equals(mLowercaseServiceType, ((CacheKey) other).mLowercaseServiceType)
                    && Objects.equals(mSocketKey, ((CacheKey) other).mSocketKey);
        }
    }
    /**
     * A map of cached services. Key is composed of service type and socket. Value is the list of
     * services which are discovered from the given CacheKey. The lists are sorted by expiration
     * time (earliest first) that can progress the expiration check efficiently.
     */
    @NonNull
    private final ArrayMap<CacheKey, List<MdnsResponse>> mCachedServices = new ArrayMap<>();
    /**
     * A map of service expire callbacks. Key is the callback listener and value is the listened
     * service type.
     */
    @NonNull
    private final ArrayMap<ServiceExpiredCallback, CacheKey> mCallbacks = new ArrayMap<>();
    @NonNull
    private final Handler mHandler;
    @NonNull
    private final MdnsUtils.Clock mClock;
    private long mNextExpirationTime = EXPIRATION_NEVER;

    public MdnsServiceCache(@NonNull Looper looper) {
        this(looper, new MdnsUtils.Clock());
    }

    @VisibleForTesting
    MdnsServiceCache(@NonNull Looper looper, @NonNull MdnsUtils.Clock clock) {
        mHandler = new Handler(looper);
        mClock = clock;
    }

    /**
     * Get the cache services which are queried from given service type and socket.
     *
     * @param serviceType the target service type.
     * @param socketKey the target socket
     * @return the list of services which matches the given service type.
     */
    @NonNull
    public List<MdnsResponse> getCachedServices(@NonNull String serviceType,
            @NonNull SocketKey socketKey) {
        ensureRunningOnHandlerThread(mHandler);
        final CacheKey key = new CacheKey(serviceType, socketKey);
        final long now = mClock.elapsedRealtime();
        // Remove and notify the expired services.
        for (MdnsResponse previousResponse : removeExpiredServices(key, now)) {
            notifyServiceExpired(key, previousResponse, null /* newResponse */);
        }

        return mCachedServices.containsKey(key)
                ? Collections.unmodifiableList(new ArrayList<>(mCachedServices.get(key)))
                : Collections.emptyList();
    }

    /**
     * Find a matched response for given service name
     *
     * @param responses the responses to be searched.
     * @param serviceName the target service name
     * @return the response which matches the given service name or null if not found.
     */
    public static MdnsResponse findMatchedResponse(@NonNull List<MdnsResponse> responses,
            @NonNull String serviceName) {
        for (MdnsResponse response : responses) {
            if (equalsIgnoreDnsCase(serviceName, response.getServiceInstanceName())) {
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
     * @param socketKey the target socket
     * @return the service which matches given conditions.
     */
    @Nullable
    public MdnsResponse getCachedService(@NonNull String serviceName,
            @NonNull String serviceType, @NonNull SocketKey socketKey) {
        ensureRunningOnHandlerThread(mHandler);
        final CacheKey key = new CacheKey(serviceType, socketKey);
        final long now = mClock.elapsedRealtime();
        // Remove and notify the expired services.
        for (MdnsResponse previousResponse : removeExpiredServices(key, now)) {
            notifyServiceExpired(key, previousResponse, null /* newResponse */);
        }

        final List<MdnsResponse> responses = mCachedServices.get(key);
        if (responses == null) {
            return null;
        }
        final MdnsResponse response = findMatchedResponse(responses, serviceName);
        return response != null ? new MdnsResponse(response) : null;
    }

    static void insertResponseAndSortList(
            List<MdnsResponse> responses, MdnsResponse response, long now) {
        // binarySearch returns "the index of the search key, if it is contained in the list;
        // otherwise, (-(insertion point) - 1)"
        final int searchRes = Collections.binarySearch(responses, response,
                // Sort the list by ttl.
                (o1, o2) -> Long.compare(o1.getMinRemainingTtl(now), o2.getMinRemainingTtl(now)));
        responses.add(searchRes >= 0 ? searchRes : (-searchRes - 1), response);
    }

    /**
     * Add or update a service.
     *
     * @param serviceType the service type.
     * @param socketKey the target socket
     * @param response the response of the discovered service.
     */
    public void addOrUpdateService(@NonNull String serviceType, @NonNull SocketKey socketKey,
            @NonNull MdnsResponse response) {
        ensureRunningOnHandlerThread(mHandler);
        final List<MdnsResponse> responses = mCachedServices.computeIfAbsent(
                new CacheKey(serviceType, socketKey), key -> new ArrayList<>());
        // Remove existing service if present.
        final MdnsResponse existing =
                findMatchedResponse(responses, response.getServiceInstanceName());
        responses.remove(existing);

        // Insert and sort service and
        final long now = mClock.elapsedRealtime();
        insertResponseAndSortList(responses, response, now);

        // Update the next expiration check time when a new service is added.
        mNextExpirationTime = getNextExpirationTime(now);
    }

    /**
     * Remove a service which matches the given service name, type and socket.
     *
     * @param serviceName the target service name.
     * @param serviceType the target service type.
     * @param socketKey the target socket.
     */
    @Nullable
    public MdnsResponse removeService(@NonNull String serviceName, @NonNull String serviceType,
            @NonNull SocketKey socketKey) {
        ensureRunningOnHandlerThread(mHandler);
        final CacheKey cacheKey = new CacheKey(serviceType, socketKey);
        final List<MdnsResponse> responses = mCachedServices.get(cacheKey);
        if (responses == null) {
            return null;
        }
        final Iterator<MdnsResponse> iterator = responses.iterator();
        MdnsResponse removedResponse = null;
        while (iterator.hasNext()) {
            final MdnsResponse response = iterator.next();
            if (equalsIgnoreDnsCase(serviceName, response.getServiceInstanceName())) {
                iterator.remove();
                removedResponse = response;
                break;
            }
        }
        // Remove the serviceType if no response.
        if (responses.isEmpty()) {
            mCachedServices.remove(cacheKey);
        }
        // Update the next expiration check time when a service is removed.
        mNextExpirationTime = getNextExpirationTime(mClock.elapsedRealtime());
        return removedResponse;
    }

    private long getNextExpirationTime(long now) {
        long minRemainingTtl = EXPIRATION_NEVER;
        for (int i = 0; i < mCachedServices.size(); i++) {
            minRemainingTtl = min(minRemainingTtl,
                    // The empty lists are not kept in the map, so there's always at least one
                    // element in the list. Therefore, it's fine to get the first element without a
                    // null check.
                    mCachedServices.valueAt(i).get(0).getMinRemainingTtl(now));
        }
        return now + minRemainingTtl;
    }

    /**
     * Register a callback to listen to service expiration.
     *
     * <p> Registering the same callback instance twice is a no-op, since MdnsServiceTypeClient
     * relies on this.
     *
     * @param serviceType the target service type.
     * @param socketKey the target socket.
     * @param callback the callback that notify the service is expired.
     */
    public void registerServiceExpiredCallback(@NonNull String serviceType,
            @NonNull SocketKey socketKey, @NonNull ServiceExpiredCallback callback) {
        ensureRunningOnHandlerThread(mHandler);
        mCallbacks.put(callback, new CacheKey(serviceType, socketKey));
    }

    /**
     * Unregister the service expired callback.
     *
     * @param callback the callback that is registered to listen service expiration before.
     */
    public void unregisterServiceExpiredCallback(
            @NonNull ServiceExpiredCallback callback) {
        ensureRunningOnHandlerThread(mHandler);
        mCallbacks.remove(callback);
    }

    private void notifyServiceExpired(@NonNull CacheKey cacheKey,
            @NonNull MdnsResponse previousResponse, @Nullable MdnsResponse newResponse) {
        for (int i = 0; i < mCallbacks.size(); i++) {
            final CacheKey listenedCacheKey = mCallbacks.valueAt(i);
            if (cacheKey.equals(listenedCacheKey)) {
                final ServiceExpiredCallback callback = mCallbacks.keyAt(i);
                mHandler.post(() -> callback.onServiceRecordExpired(previousResponse, newResponse));
            }
        }
    }

    private List<MdnsResponse> removeExpiredServices(@NonNull CacheKey cacheKey, long now) {
        final List<MdnsResponse> responses = mCachedServices.get(cacheKey);
        if (responses == null) return Collections.emptyList();

        final List<MdnsResponse> removedResponses = new ArrayList<>();
        final Iterator<MdnsResponse> iterator = responses.iterator();
        while (iterator.hasNext()) {
            final MdnsResponse response = iterator.next();
            // TODO: Check other records (A, AAAA, TXT) ttl time and remove the record if it's
            //  expired. Then send service update notification.
            if (!response.hasServiceRecord() || response.getMinRemainingTtl(now) > 0) {
                // The responses are sorted by the service record ttl time. Break out of loop
                // early if service is not expired or no service record.
                break;
            }
            // Remove the ttl expired service.
            iterator.remove();
            removedResponses.add(response);
        }
        // Remove the serviceType if no response.
        if (responses.isEmpty()) {
            mCachedServices.remove(cacheKey);
        }
        return removedResponses;
    }

    /**
     * Check whether the ttl time is expired on each service and notify to the listeners
     */
    public void maybeRemoveExpiredServices() {
        ensureRunningOnHandlerThread(mHandler);
        final long now = mClock.elapsedRealtime();
        if (now < mNextExpirationTime) {
            // Skip the check if ttl time is not expired.
            return;
        }

        for (int i = mCachedServices.size() - 1; i >= 0; i--) {
            final CacheKey cacheKey = mCachedServices.keyAt(i);
            for (MdnsResponse previousResponse : removeExpiredServices(cacheKey, now)) {
                notifyServiceExpired(cacheKey, previousResponse, null /* newResponse */);
            }
        }
        // Update next expiration time.
        mNextExpirationTime = getNextExpirationTime(now);
    }

    /*** Callbacks for listening service expiration */
    public interface ServiceExpiredCallback {
        /*** Notify the service is expired */
        default void onServiceRecordExpired(@NonNull MdnsResponse previousResponse,
                @Nullable MdnsResponse newResponse) { }
    }
}
