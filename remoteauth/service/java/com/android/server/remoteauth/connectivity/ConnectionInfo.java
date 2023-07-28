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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Encapsulates the connection information.
 *
 * Connection information captures the details of underlying connection such as connection id,
 * type of connection and peer device mac address.
 * @hide
 */
public final class ConnectionInfo {
    // This is the association id for CDM connection.
    private int mConnectionId;
    private String mPeerAddress;
    private @ConnectionType int mConnectionType;

    /**
     * Used to denote an unsupported connection type.
     */
    public static final int UNKNOWN = 0;
    /**
     * Used to indicate a BLUETOOTH connection type.
     */
    public static final int BLUETOOTH = 1;
    /**
     * Used to indicate a BLUETOOTH LE connection type.
     */
    public static final int BLUETOOTH_LE = 2;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({UNKNOWN, BLUETOOTH, BLUETOOTH_LE})
    public @interface ConnectionType {};

    public ConnectionInfo(
            @ConnectionType int connectionType, int connectionId, String peerAddress) {
        this.mConnectionType = connectionType;
        this.mConnectionId = connectionId;
        this.mPeerAddress = peerAddress;
    }

    /**
     * Returns the connection id.
     *
     * @return connection id.
     */
    public int getConnectionId() {
        return this.mConnectionId;
    }

    /**
     * Returns the peer address.
     *
     * @return peer address.
     */
    public String getPeerAddress() {
        return this.mPeerAddress;
    }

    /**
     * Returns connection type.
     *
     * @return type of {@link ConnectionType}
     */
    public @ConnectionType int getConnectionType() {
        return this.mConnectionType;
    }

    /** Returns a string representation of ConnectionInfo. */
    @Override
    public String toString() {
        return "ConnectionInfo["
                + "connectionId= "
                + mConnectionId
                + ", peerAddress= "
                + mPeerAddress
                + ", connectionType= "
                + mConnectionType
                + "]";
    }

    /** Returns true if this ConnectionInfo object is equal to the other. */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ConnectionInfo)) {
            return false;
        }

        ConnectionInfo other = (ConnectionInfo) o;
        return mConnectionType == other.getConnectionType()
                && mConnectionId == other.getConnectionId()
                && mPeerAddress.equals(other.getPeerAddress());
    }

    /** Returns the hashcode of this object */
    @Override
    public int hashCode() {
        return Objects.hash(mConnectionId, mConnectionType, mPeerAddress);
    }

    /** Builder class for Connection Info. */
    public static class Builder {
        private @ConnectionType int mConnectionType;
        private String mPeerAddress;
        private int mConnectionId;

        private Builder() {}

        /** Static method to create a new builder */
        public static Builder newInstance() {
            return new Builder();
        }

        /**
         * Sets the type of connection.
         *
         * @param connectionType Type of the connection.
         */
        @NonNull
        public Builder setConnectionType(@ConnectionType int connectionType) {
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
         * @param connectionId identifying the connection.
         */
        @NonNull
        public Builder setConnectionId(int connectionId) {
            mConnectionId = connectionId;
            return this;
        }

        /** Builds a ConnectionInfo object. */
        @NonNull
        public ConnectionInfo build() {
            return new ConnectionInfo(this.mConnectionType, this.mConnectionId, this.mPeerAddress);
        }
    }
}
