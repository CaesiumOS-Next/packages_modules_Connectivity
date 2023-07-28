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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;

import java.time.Duration;

/** Interface to start/stop discovery and trigger a connection to the associated device. */
public interface ConnectivityModule {
    Duration DISCOVERY_TIMEOUT_SECS = Duration.ofMinutes(2);

    /**
     * API to start discovery.
     *
     * <p>Discovery continues until stopped using {@link stopDiscovery} or times out.
     *
     * @param discoveryFilter to filter for devices during discovery.
     * @param discoveredDeviceReceiver callback to run when device is found or lost.
     * @hide
     */
    void startDiscovery(
            @NonNull DiscoveryFilter discoveryFilter,
            @NonNull DiscoveredDeviceReceiver discoveredDeviceReceiver);

    /**
     * API to stop discovery.
     *
     * @hide
     */
    void stopDiscovery();

    /** Reason codes for connect failure. */
    @IntDef({
            ReasonCode.REASON_UNKNOWN,
            ReasonCode.CONNECTION_TIMED_OUT,
            ReasonCode.CONNECTION_REFUSED,
            ReasonCode.DEVICE_UNAVAILABLE
    })
    public @interface ReasonCode {
        int REASON_UNKNOWN = 0;
        int CONNECTION_TIMED_OUT = 1;
        int CONNECTION_REFUSED = 2;
        int DEVICE_UNAVAILABLE = 3;
    }

    /**
     * API to initiate a connection with the peer device.
     *
     * @param connectionInfo of the device discovered using {@link startDiscovery}.
     * @param eventListener to listen for events from the underlying transport.
     * @return {@link Connection} object or null connection is not established.
     * @throws ConnectionException in case connection cannot be established.
     * @hide
     */
    @Nullable
    Connection connect(
            @NonNull ConnectionInfo connectionInfo, @NonNull EventListener eventListener);

    /**
     * Class representing the message received callback. Clients should implement this callback to
     * receive messages from the peer device.
     */
    abstract class MessageReceiver {
        /**
         * API to receive message from the peer device. Clients can set empty buffer as an ACK to
         * the request.
         *
         * @param messageIn message from peer device.
         * @param responseCallback {@link ResponseCallback} callback to send the message.
         * @hide
         */
        public void onMessageReceived(byte[] messageIn, ResponseCallback responseCallback) {}
    }

    /**
     * API to start listening for incoming messages. Runs MessageReceiver callback.
     *
     * @param messageReceiver to receive messages.
     * @throws AssertionError if a listener is already configured.
     * @hide
     */
    void startListening(MessageReceiver messageReceiver);

    /**
     * API to stop listening to incoming messages.
     *
     * @hide
     */
    void stopListening();
}
