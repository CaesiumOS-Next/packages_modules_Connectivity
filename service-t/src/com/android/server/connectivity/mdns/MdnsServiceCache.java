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

import com.android.internal.annotations.GuardedBy;

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

    // TODO: check ttl expiration for each service and notify to the clients.
}
