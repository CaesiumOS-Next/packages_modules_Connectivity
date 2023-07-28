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
import android.os.Parcel;
import android.os.Parcelable;

/** Class to encapsulate the connection information. */
public final class ConnectionInfo implements Parcelable {
    // This is the association id for CDM connection.
    private int mConnectionId;
    private String mPeerAddress;
    private @ConnectionType int mConnectionType;

    @IntDef({
        ConnectionType.UNKNOWN,
        ConnectionType.BLUETOOTH,
        ConnectionType.BLUETOOTH_LE,
    })
    public @interface ConnectionType {
        int UNKNOWN = 0;
        int BLUETOOTH = 1;
        int BLUETOOTH_LE = 2;
    }

    public ConnectionInfo(
            @ConnectionType int connectionType, int connectionId, String peerAddress) {
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

    public @ConnectionType int getConnectionType() {
        return this.mConnectionType;
    }

    /** Used to read ConnectionInfo from a Parcel. */
    @NonNull
    public static final Parcelable.Creator<ConnectionInfo> CREATOR =
            new Parcelable.Creator<ConnectionInfo>() {
                public ConnectionInfo createFromParcel(Parcel in) {
                    Builder builder = Builder.newInstance();
                    builder.setConnectionId(in.readInt());
                    builder.setPeerAddress(in.readString());
                    builder.setConnectionType(in.readInt());
                    return builder.build();
                }

                @Override
                public ConnectionInfo[] newArray(int size) {
                    return new ConnectionInfo[size];
                }
            };

    /** No special parcel contents */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Flatten this ConnectionInfo in to a Parcel.
     *
     * @param dest The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written.
     */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mConnectionId);
        dest.writeString(mPeerAddress);
        dest.writeInt(mConnectionType);
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

    /** Returns true if this ConnectionInfo object is equals to other. */
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
         * @param connectionType Type of the connection. One of {@link ConnectionType}.
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
