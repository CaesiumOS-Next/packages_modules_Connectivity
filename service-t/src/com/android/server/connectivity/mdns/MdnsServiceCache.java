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
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.annotations.GuardedBy;

import java.util.Iterator;
import java.util.Set;

/**
 * The {@link MdnsServiceCache} manages the service which discovers from each socket and cache these
 * services to reduce duplicated queries.
 *
 * <p>This class is not thread safe.
 */
public class MdnsServiceCache {
    private static final String TAG = MdnsServiceCache.class.getSimpleName();
    /**
     * A map of cached services. Key is the service type and value is the service which use the
     * service type to discover from each socket.
     */
    @GuardedBy("this")
    private final ArrayMap<String, Set<MdnsResponse>> mCachedServices = new ArrayMap<>();

    /**
     * Get the cache services which are queried from given service type and network.
     *
     * @param serviceType the target service type.
     * @param network the target network
     * @return the set of services which matches the given service type.
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

    private MdnsResponse findMatchedResponse(@NonNull Set<MdnsResponse> responses,
            @NonNull String serviceName, @Nullable Network network) {
        for (MdnsResponse response : responses) {
            if (hasSameNetworkAndServiceName(network, serviceName, response)) {
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
    public synchronized MdnsResponse getCachedService(@NonNull String serviceName,
            @NonNull String serviceType, @Nullable Network network) {
        final Set<MdnsResponse> responses = mCachedServices.get(serviceType);
        if (responses == null) {
            return null;
        }
        return findMatchedResponse(responses, serviceName, network);
    }

    /**
     * Put a service.
     *
     * @param serviceType the service type.
     * @param response the response of the discovered service.
     */
    public synchronized void addService(@NonNull String serviceType,
            @NonNull MdnsResponse response) {
        Set<MdnsResponse> responses = mCachedServices.get(serviceType);
        // First response for the service type.
        if (responses == null) {
            responses = new ArraySet<>();
            responses.add(response);
            mCachedServices.put(serviceType, responses);
            return;
        }
        // Remove existing service if present.
        final MdnsResponse existing = findMatchedResponse(
                responses, response.getServiceInstanceName(), response.getNetwork());
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

    // TODO: check ttl expiration for each service and notify to the clients.
}
