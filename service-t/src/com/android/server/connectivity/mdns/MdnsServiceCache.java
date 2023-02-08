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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Network;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MdnsServiceCache {
    private static final String TAG = MdnsServiceCache.class.getSimpleName();

    @GuardedBy("this")
    private final ArrayMap<Pair<String, Network>, Map<String, MdnsResponse>> mServices =
            new ArrayMap<>();
    private final Handler mHandler;
    private final Dependencies mDeps;

    public MdnsServiceCache(@NonNull Looper looper) {
        this(looper, new Dependencies());
    }

    MdnsServiceCache(@NonNull Looper looper, @NonNull Dependencies deps) {
        mDeps = deps;
        mHandler = deps.makeHandler(looper);
    }

    /**
     * Dependencies of MdnsServiceCache, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        public Handler makeHandler(@NonNull Looper looper) {
            return new Handler(looper);
        }

        public long getCheckTtlExpiredServicesDelay() {
            return 1000L;
        }
    }

    @NonNull
    public synchronized List<MdnsResponse> getCachedServices(
            @NonNull Pair<String, Network> perNetworkServiceType) {
        final List<MdnsResponse> services = new ArrayList<>();
        final ArrayMap<String, MdnsResponse> responses = (ArrayMap<String, MdnsResponse>)
                mServices.getOrDefault(perNetworkServiceType, null);
        if (responses != null) {
            for (int i = 0; i < responses.size(); i++) {
                services.add(responses.valueAt(i));
            }
        }
        return services;
    }

    @Nullable
    public synchronized MdnsResponse getCachedService(@NonNull String serviceName,
            @NonNull Pair<String, Network> perNetworkServiceType) {
        final Map<String, MdnsResponse> responses =
                mServices.getOrDefault(perNetworkServiceType, null);
        if (responses == null) {
            return null;
        }
        return responses.getOrDefault(serviceName, null);
    }

    public synchronized void addService(@NonNull String serviceName,
            @NonNull Pair<String, Network> perNetworkServiceType, @NonNull MdnsResponse response) {
        if (mServices.size() == 0) {
            mHandler.postDelayed(this::checkTtlExpiredServices, 0 /* what */,
                    mDeps.getCheckTtlExpiredServicesDelay());
        }

        Map<String, MdnsResponse> responses =
                mServices.getOrDefault(perNetworkServiceType, null);
        // First response for the service type.
        if (responses == null) {
            responses = new ArrayMap<>();
            responses.put(serviceName, response);
            mServices.put(perNetworkServiceType, responses);
            return;
        }

        // Ignore existed response.
        if (responses.getOrDefault(serviceName, null) != null) {
            Log.d(TAG, "Service[Name:" + serviceName + ", Type:" + perNetworkServiceType
                    + "] existed.");
            return;
        }
        responses.put(serviceName, response);
    }

    @Nullable
    public synchronized MdnsResponse removeService(@NonNull String serviceName,
            @NonNull Pair<String, Network> perNetworkServiceType) {
        Map<String, MdnsResponse> responses =
                mServices.getOrDefault(perNetworkServiceType, null);
        if (responses == null) {
            return null;
        }
        return responses.remove(serviceName);
    }

    private synchronized void checkTtlExpiredServices() {
        for (int i = 0; i < mServices.size(); i++) {
            final ArrayMap<String, MdnsResponse> responses = (ArrayMap<String, MdnsResponse>)
                    mServices.valueAt(i);
            for (int j = 0; j < responses.size(); j++) {
                final MdnsResponse response = responses.valueAt(j);
                if (response.hasServiceRecord()
                        && response.getServiceRecord()
                        .getRemainingTTL(SystemClock.elapsedRealtime()) == 0) {
                    // Remove the ttl expired service.
                    responses.removeAt(j);
                    // TODO: Notify clients
                }
            }
            // Remove the perNetworkServiceType if no response.
            if (responses.size() == 0) {
                mServices.removeAt(i);
            }
        }
        if (mServices.size() > 0) {
            mHandler.postDelayed(this::checkTtlExpiredServices, 0 /* what */,
                    mDeps.getCheckTtlExpiredServicesDelay());
        }
    }
}
