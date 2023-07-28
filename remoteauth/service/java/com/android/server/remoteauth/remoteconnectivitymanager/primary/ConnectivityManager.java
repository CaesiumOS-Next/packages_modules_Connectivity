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

package com.google.android.server.remote_auth.remoteconnectivitymanager;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.time.Duration;

/**
 * Interface to start/stop discovery and trigger a connection to the associated device.
 */
public interface ConnectivityManager {
    Duration DISCOVERY_TIMEOUT_SECS = Duration.ofSeconds(120);

    /**
     * API to start discovery.
     * <p>
     * Discovery continues until stopped using {@link stopDiscovery} or times out.
     *
     * @param discoveryFilter
     * @param discoveredDeviceReceiver
     */
    void startDiscovery(@NonNull DiscoveryFilter discoveryFilter,
                        @NonNull DiscoveredDeviceReceiver discoveredDeviceReceiver);

    /**
     * API to stop discovery.
     */
    void stopDiscovery();

    /**
     * API to initiate a connection with the peer device.
     *
     * @param discoveredDevice Device discovered using {@link startDiscovery}.
     * @param eventListener    Listens for the events on this connection.
     * @return {@link Messenger} object.
     * @throws ConnectionException in case connection cannot be established.
     */
    @Nullable
    Messenger connect(@NonNull DiscoveredDevice discoveredDevice,
                      @NonNull EventListener eventListener);

    enum ReasonCode {
        REASON_UNKNOWN,
        CONNECTION_TIMEDOUT,
        CONNECTION_REFUSED,
        DEVICE_UNAVAILABLE,
    }
}
