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

/**
 * Interface representing a connection.
 */
public interface Connection {
    /**
     * ErrorCode codes of the sent message.
     */
    enum ErrorCode {
        /**
         * Unknown error.
         */
        UNKNOWN(0),
        /**
         * Message was sent successfully.
         */
        OK(1),
        /**
         * Timeout occurred while waiting for response from the peer.
         */
        DEADLINE_EXCEEDED(2),
        /**
         * Device became unavailable while sending the message.
         */
        DEVICE_UNAVAILABLE(3);

        private final int mValue;

        ErrorCode(int value) {
            this.mValue = value;
        }
    }

    /**
     * Callback for clients to get the response of sendRequest. {@link onSuccess} is called if the
     * peer device responds with Status::OK, otherwise runs the {@link onFailure} callback.
     */
    abstract class RemoteResponse {
        /**
         * Called on a success.
         * @param buffer response from the device.
         */
        public void onSuccess(byte[] buffer) {}

        /**
         * Called when message sending fails.
         * @param errorCode indicating the error.
         */
        public void onFailure(ErrorCode errorCode) {}
    }

    /**
     * API to send a request to the peer.
     * @param request byte array to be send to the peer device.
     * @param remoteResponse callback to be run when the peer response is received or if an error
     *                       occurred.
     * @hide
     */
    void sendRequest(byte[] request, RemoteResponse remoteResponse);

    /**
     * API to trigger a disconnect with the peer device.
     * @hide
     */
    void disconnect();

    /**
     * API to get the connection information.
     *
     * @return A connection information object.
     * @hide
     */
    ConnectionInfo getConnectionInfo();
}
