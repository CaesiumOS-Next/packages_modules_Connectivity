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

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

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
    private final ArrayMap<String, List<MdnsResponse>> mCachedServices = new ArrayMap<>();

    /**
     * A map of service expire callbacks. Key is the callback listener and value is the listened
     * service type.
     */
    @GuardedBy("this")
    private final ArrayMap<ServiceExpireCallback, String> mCallbacks = new ArrayMap<>();

    private final Handler mHandler;
    private final Dependencies mDeps;

    public MdnsServiceCache(@NonNull Looper looper) {
        this(looper, new Dependencies());
    }

    MdnsServiceCache(@NonNull Looper looper, @NonNull Dependencies deps) {
        mDeps = deps;
        mHandler = new Handler(looper);
    }

    /*** Dependencies of MdnsServiceCache, for injection in tests. */
    @VisibleForTesting
    public static class Dependencies {
        /*** Get the delay for next checking ttl expired services. */
        public long getCheckTtlExpiredServicesDelay() {
            return 1000L;
        }
    }

    /**
     * Get the cache services which are queried from given service type and network.
     *
     * @param serviceType the target service type.
     * @param network the target network
     * @return the list of services which matches the given service type and network.
     */
    @NonNull
    public synchronized List<MdnsResponse> getCachedServices(@NonNull String serviceType,
            @Nullable Network network) {
        final List<MdnsResponse> services = new ArrayList<>();
        final List<MdnsResponse> responses = mCachedServices.getOrDefault(serviceType, null);
        if (responses != null) {
            for (MdnsResponse response : responses) {
                if (isNetworkMatched(network, response.getNetwork())) {
                    services.add(response);
                }
            }
        }
        return services;
    }

    private boolean hasSameNetworkAndServiceName(@Nullable Network network,
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
        final List<MdnsResponse> responses = mCachedServices.getOrDefault(serviceType, null);
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
        // Start the service expiration check
        if (mCachedServices.size() == 0) {
            mHandler.postDelayed(this::checkTtlExpiredServices, 0 /* what */,
                    mDeps.getCheckTtlExpiredServicesDelay());
        }

        List<MdnsResponse> responses = mCachedServices.getOrDefault(serviceType, null);
        // First response for the service type.
        if (responses == null) {
            responses = new ArrayList<>();
            responses.add(newResponse);
            mCachedServices.put(serviceType, responses);
            return;
        }

        // Ignore existed response.
        for (MdnsResponse response : responses) {
            if (hasSameNetworkAndServiceName(
                    newResponse.getNetwork(), newResponse.getServiceInstanceName(), response)) {
                return;
            }
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
        final List<MdnsResponse> responses = mCachedServices.getOrDefault(serviceType, null);
        if (responses == null) {
            return null;
        }
        for (int i = 0; i < responses.size(); i++) {
            final MdnsResponse response = responses.get(i);
            if (hasSameNetworkAndServiceName(network, serviceName, response)) {
                return responses.remove(i);
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

    private void notifyServiceExpired(@NonNull String serviceType, @NonNull MdnsResponse response) {
        for (int i = 0; i < mCallbacks.size(); i++) {
            final String listenedServiceType = mCallbacks.valueAt(i);
            if (serviceType.equals(listenedServiceType)) {
                mCallbacks.keyAt(i).onServiceExpired(response);
            }
        }
    }

    private synchronized void checkTtlExpiredServices() {
        for (int i = 0; i < mCachedServices.size(); i++) {
            final String serviceType = mCachedServices.keyAt(i);
            final List<MdnsResponse> responses = mCachedServices.valueAt(i);
            final List<MdnsResponse> copyResponses = new ArrayList<>(responses);
            for (int j = 0; j < copyResponses.size(); j++) {
                final MdnsResponse response = copyResponses.get(j);
                if (response.hasServiceRecord()
                        && response.getServiceRecord()
                        .getRemainingTTL(SystemClock.elapsedRealtime()) == 0) {
                    // Remove the ttl expired service.
                    responses.remove(j);
                    notifyServiceExpired(serviceType, response);
                }
            }
            // Remove the serviceType if no response.
            if (responses.size() == 0) {
                mCachedServices.removeAt(i);
            }
        }
        if (mCachedServices.size() > 0) {
            // TODO: use the shortest ttl time of existed service to be the next check time.
            mHandler.postDelayed(this::checkTtlExpiredServices, 0 /* what */,
                    mDeps.getCheckTtlExpiredServicesDelay());
        }
    }

    /*** Callbacks for listening service expire */
    public interface ServiceExpireCallback {
        /*** Notify the service is expired */
        default void onServiceExpired(@NonNull MdnsResponse response) {
        }
    }
}
