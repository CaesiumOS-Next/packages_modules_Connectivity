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

/**
 * Class to encapsulate the connection information.
 */
public final class ConnectionInfo {
    private ConnectionType mConnectionType;
    private String mPeerAddress;
    private int mConnectionId;

    public enum ConnectionType {
        UNKNOWN,
        BLUETOOTH,
        BLUETOOTH_LE,
    }

    public ConnectionInfo(ConnectionType connectionType, int connectionId,
                          String peerAddress) {
        this.mConnectionType = connectionType;
        this.mConnectionId = connectionId;
        this.mPeerAddress = peerAddress;
    }

    public int getConnectionId() {
        return this.mConnectionId;
    }

    public String getPeerAddress() {
        return this.mPeerAddress;
    }

    public ConnectionType getConnectionType() {
        return this.mConnectionType;
    }

    /**
     * Builder class for Connection Info.
     */
    public static class Builder {
        private ConnectionType mConnectionType;
        private String mPeerAddress;
        private int mConnectionId;

        private Builder() {
        }

        /**
         * Static method to create a new builder
         */
        public static Builder newInstance() {
            return new Builder();
        }

        /**
         * Sets the type of connection.
         *
         * @param connectionType Type of the connection. One of {@link ConnectionType}.
         */
        @NonNull
        public Builder setConnectionType(ConnectionType connectionType) {
            mConnectionType = connectionType;
            return this;
        }

        /**
         * Sets the peer address.
         *
         * @param peerAddress Mac address of the peer.
         */
        @NonNull
        public Builder setPeerAddress(String peerAddress) {
            mPeerAddress = peerAddress;
            return this;
        }

        /**
         * Sets the connection id.
         *
         * @param connectionId
         */
        @NonNull
        public Builder setConnectionId(int connectionId) {
            mConnectionId = connectionId;
            return this;
        }

        /**
         * Builds a ConnectionInfo object.
         */
        @NonNull
        public ConnectionInfo build() {
            return new ConnectionInfo(this.mConnectionType, this.mConnectionId, this.mPeerAddress);
        }
    }
}
