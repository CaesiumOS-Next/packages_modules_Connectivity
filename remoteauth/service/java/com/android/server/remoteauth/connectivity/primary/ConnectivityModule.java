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

package com.google.android.server.remoteauth.connectivity;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.time.Duration;

/**
 * Interface to start/stop discovery and trigger a connection to the associated device.
 */
public interface ConnectivityModule {
    Duration DISCOVERY_TIMEOUT_SECS = Duration.ofSeconds(120);

    /**
     * API to start discovery.
     * <p>
     * Discovery continues until stopped using {@link stopDiscovery} or times out.
     *
     * @param discoveryFilter
     * @param discoveredDeviceReceiver
     * @hide
     */
    void startDiscovery(@NonNull DiscoveryFilter discoveryFilter,
                        @NonNull DiscoveredDeviceReceiver discoveredDeviceReceiver);

    /**
     * API to stop discovery.
     * @hide
     */
    void stopDiscovery();

    /**
     * API to initiate a connection with the peer device.
     *
     * @param connectionInfo of the device discovered using {@link startDiscovery}.
     * @return {@link Connection} object or null connection is not established.
     * @throws ConnectionException in case connection cannot be established.
     * @hide
     */
    @Nullable
    Connection connect(@NonNull ConnectionInfo connectionInfo);

    /**
     * Enum with reason codes for connect fail.
     */
    enum ReasonCode {
        REASON_UNKNOWN,
        CONNECTION_TIMEDOUT,
        CONNECTION_REFUSED,
        DEVICE_UNAVAILABLE,
    }
}
