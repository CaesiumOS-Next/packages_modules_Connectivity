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

/** Interface to listen for incoming messages from the primary device. */
public interface MessageListener {
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
