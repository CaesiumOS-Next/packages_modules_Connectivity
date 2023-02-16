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

import static com.android.server.connectivity.mdns.MdnsSocketProvider.isNetworkMatched;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.annotations.GuardedBy;

import java.util.Comparator;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

/**
 * The {@link MdnsServiceCache} manages the service which discovers from each socket and cache these
 * services to reduce duplicated queries.
 *
 * <p>This class is not thread safe.
 */
public class MdnsServiceCache {
    /**
     * A map of cached services. Key is the service type and value is the service which use the
     * service type to discover from each socket.
     */
    @GuardedBy("this")
    private final ArrayMap<String, TreeSet<MdnsResponse>> mCachedServices = new ArrayMap<>();

    /**
     * A map of service expire callbacks. Key is the callback listener and value is the listened
     * service type.
     */
    @GuardedBy("this")
    private final ArrayMap<ServiceExpireCallback, String> mCallbacks = new ArrayMap<>();

    private final Handler mHandler;

    public MdnsServiceCache(@NonNull Looper looper) {
        mHandler = new Handler(looper);
    }

    /**
     * Get the cache services which are queried from given service type and network.
     *
     * @param serviceType the target service type.
     * @param network the target network
     * @return the list of services which matches the given service type and network.
     */
    @NonNull
    public synchronized Set<MdnsResponse> getCachedServices(@NonNull String serviceType,
            @Nullable Network network) {
        final Set<MdnsResponse> services = new ArraySet<>();
        final Set<MdnsResponse> responses = mCachedServices.get(serviceType);
        if (responses != null) {
            for (MdnsResponse response : responses) {
                if (isNetworkMatched(network, response.getNetwork())) {
                    services.add(response);
                }
            }
        }
        return services;
    }

    private static boolean hasSameNetworkAndServiceName(@Nullable Network network,
            @NonNull String serviceName, @NonNull MdnsResponse response) {
        if (isNetworkMatched(network, response.getNetwork())
                && serviceName.equals(response.getServiceInstanceName())) {
            return true;
        }
        return false;
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
    public synchronized MdnsResponse getCachedService(@NonNull String serviceName,
            @NonNull String serviceType, @Nullable Network network) {
        final Set<MdnsResponse> responses = mCachedServices.get(serviceType);
        if (responses == null) {
            return null;
        }
        for (MdnsResponse response : responses) {
            if (hasSameNetworkAndServiceName(network, serviceName, response)) {
                return response;
            }
        }
        return null;
    }

    /**
     * Add a new service.
     *
     * @param serviceType the service type.
     * @param newResponse the response of the discovered new service.
     */
    public synchronized void addService(@NonNull String serviceType,
            @NonNull MdnsResponse newResponse) {
        // Start the service expiration check with new response's ttl time.
        if (mCachedServices.size() == 0) {
            mHandler.post(this::checkTtlExpiredServices);
        }

        TreeSet<MdnsResponse> responses = mCachedServices.get(serviceType);
        // First response for the service type.
        if (responses == null) {
            responses = new TreeSet<>(new ServiceTtlComparator());
            responses.add(newResponse);
            mCachedServices.put(serviceType, responses);
            return;
        }

        // Ignore existed response.
        if (responses.contains(newResponse)) {
            return;
        }
        responses.add(newResponse);
    }

    /**
     * Remove a service which matches the given service name, type and network.
     *
     * @param serviceName the target service name.
     * @param serviceType the target service type.
     * @param network the target network.
     */
    @Nullable
    public synchronized MdnsResponse removeService(@NonNull String serviceName,
            @NonNull String serviceType, @Nullable Network network) {
        final Set<MdnsResponse> responses = mCachedServices.get(serviceType);
        if (responses == null) {
            return null;
        }
        final Iterator<MdnsResponse> iterator = responses.iterator();
        while (iterator.hasNext()) {
            final MdnsResponse response = iterator.next();
            if (hasSameNetworkAndServiceName(network, serviceName, response)) {
                iterator.remove();
                return response;
            }
        }
        return null;
    }

    /**
     * Register a callback to listen service expire.
     *
     * @param serviceType the target service type.
     * @param callback the callback that notify the service expire.
     */
    public synchronized void registerServiceExpireCallback(@NonNull String serviceType,
            @NonNull ServiceExpireCallback callback) {
        mCallbacks.put(callback, serviceType);
    }

    /*** Unregister the service expire callback. */
    public synchronized void unregisterServiceExpireCallback(
            @NonNull ServiceExpireCallback callback) {
        mCallbacks.remove(callback);
    }

    private synchronized void notifyServiceExpired(@NonNull String serviceType,
            @NonNull MdnsResponse response) {
        for (int i = 0; i < mCallbacks.size(); i++) {
            final String listenedServiceType = mCallbacks.valueAt(i);
            if (serviceType.equals(listenedServiceType)) {
                mCallbacks.keyAt(i).onServiceExpired(response);
            }
        }
    }

    private synchronized void checkTtlExpiredServices() {
        final long now = SystemClock.elapsedRealtime();
        long nextCheckDelay = 120000L;
        for (int i = 0; i < mCachedServices.size(); i++) {
            final String serviceType = mCachedServices.keyAt(i);
            final TreeSet<MdnsResponse> responses = mCachedServices.valueAt(i);
            final Iterator<MdnsResponse> iterator = responses.iterator();
            while (iterator.hasNext()) {
                final MdnsResponse response = iterator.next();
                if (response.hasServiceRecord()
                        && response.getServiceRecord().getRemainingTTL(now) == 0) {
                    // Remove the ttl expired service.
                    iterator.remove();
                    notifyServiceExpired(serviceType, response);
                }
            }

            if (responses.size() == 0) {
                // Remove the serviceType if no response.
                mCachedServices.removeAt(i);
            } else {
                // Update the next check delay
                final long ttl = responses.first().getServiceRecord().getRemainingTTL(now);
                if (ttl < nextCheckDelay) {
                    nextCheckDelay = ttl;
                }
            }
        }
        if (mCachedServices.size() > 0) {
            mHandler.postDelayed(this::checkTtlExpiredServices, 0 /* what */, nextCheckDelay);
        }
    }

    /*** Callbacks for listening service expire */
    public interface ServiceExpireCallback {
        /*** Notify the service is expired */
        default void onServiceExpired(@NonNull MdnsResponse response) { }
    }

    /*** Comparator for MdnsResponse's order */
    private static class ServiceTtlComparator implements Comparator<MdnsResponse> {
        @Override
        public int compare(MdnsResponse o1, MdnsResponse o2) {
            final long now = SystemClock.elapsedRealtime();
            final long diff = (o1.getServiceRecord().getRemainingTTL(now)
                    - o2.getServiceRecord().getRemainingTTL(now));
            // Rule 1: order by remaining ttl time
            if (diff != 0L) {
                return (int) diff;
            }
            // Rule 2: order by receipt time.
            return o1.getServiceRecord().getReceiptTime() < o2.getServiceRecord().getReceiptTime()
                    ?  -1 : 1;
        }
    }
}
