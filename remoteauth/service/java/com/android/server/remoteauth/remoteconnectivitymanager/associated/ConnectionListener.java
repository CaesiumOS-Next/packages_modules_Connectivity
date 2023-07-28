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

import java.util.function.Function;

/**
 * Interface to listen for connection requests from the primary device.
 */
public interface ConnectionListener {
    /**
     * Connection listener callbacks. Triggered after connection listener receives an incoming
     * connection request.
     */
    abstract class ConnectionListenerCallback {
        /**
         * Callback triggered after accpeting the connection. Called only once.
         *
         * @param messenger Messenger object.
         * @hide
         */
        public void onConnectionAccepted(Messenger messenger) {
        }

        /**
         * Callback triggered after rejecting the connection. Called only once.
         */
        public void onConnectionRejected() {
        }
    }

    /**
     * API to start listening for incoming connection requests.
     *
     * @param maybeAcceptConnection Function to accept a connection interface.
     * @hide
     */
    void start(Function<ConnectionInfo, EventListener> maybeAcceptConnection);

    /**
     * API to stop listening to incoming connection requests.
     */
    void stop();
}
