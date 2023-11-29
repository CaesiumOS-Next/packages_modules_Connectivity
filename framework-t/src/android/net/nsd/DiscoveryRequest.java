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

package android.net.nsd;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Network;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.Objects;

/**
 * Encapsulates parameters for {@link NsdManager#discoverServices}.
 */
@FlaggedApi(NsdFlags.NSD_SUBTYPES_SUPPORT_ENABLED)
public final class DiscoveryRequest implements Parcelable {
    @NonNull
    public static final Creator<DiscoveryRequest> CREATOR =
            new Creator<>() {
                @Override
                public DiscoveryRequest createFromParcel(Parcel in) {
                    String serviceType = in.readString();
                    String subtype = (String) in.readValue(null);
                    int protocolType = in.readInt();
                    Network network = (Network)in.readValue(null);
                    return new DiscoveryRequest(serviceType, subtype, protocolType, network);
                }

                @Override
                public DiscoveryRequest[] newArray(int size) {
                    return new DiscoveryRequest[size];
                }
            };

    @NonNull
    private final String mServiceType;

    @Nullable
    private final String mSubtype;

    int mProtocolType;

    @Nullable
    private final Network mNetwork;

    private DiscoveryRequest(@NonNull String serviceType, @Nullable String subtype,
            int protocolType, @Nullable Network network) {
        mServiceType = serviceType;
        mSubtype = subtype;
        mProtocolType = protocolType;
        mNetwork = network;
    }

    /** Returns the service type. For example, "_meshcop._udp" and "_matter._tcp". */
    @NonNull
    public String getServiceType() {
        return mServiceType;
    }

    /**
     * Returns the subtype without the trailing "._sub" label or {@code null} if no subtype is
     * specified.
     */
    @Nullable
    public String getSubtype() {
        return mSubtype;
    }

    /** Returns the service discovery protocol. */
    public int getProtocolType() {
        return mProtocolType;
    }

    /**
     * Returns the {@link Network} on which the query should be sent or {@code null} if no
     * network is specified.
     */
    @Nullable
    public Network getNetwork() {
        return mNetwork;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("serviceType: ").append(mServiceType)
            .append(", subtype: ").append(mSubtype)
            .append(", protocolType: ").append(mProtocolType)
            .append(", network: ").append(mNetwork);
        return sb.toString();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        } else if (!(other instanceof DiscoveryRequest)) {
            return false;
        } else {
            DiscoveryRequest otherRequest = (DiscoveryRequest) other;
            return mServiceType.equals(otherRequest.mServiceType)
                    && Objects.equals(mSubtype, otherRequest.mSubtype)
                    && mProtocolType == otherRequest.mProtocolType
                    && Objects.equals(mNetwork, otherRequest.mNetwork);
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(mServiceType, mSubtype, mProtocolType, mNetwork);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mServiceType);
        dest.writeValue(mSubtype);
        dest.writeInt(mProtocolType);
        dest.writeValue(mNetwork);
    }

    /** The builder for creating new {@link DiscoveryRequest} objects. */
    public static final class Builder {
        @NonNull
        private final String mServiceType;

        private final int mProtocolType;

        @Nullable
        private String mSubtype;

        @Nullable
        private Network mNetwork;

        /**
         * Creates a new {@link Builder} object.
         *
         * @param serviceType the type of services to be discovered, example values are
         * "_meshcop._udp" and "_matter._tcp"
         * @param protocolValue the service discovery protocol, valid values are {@code
         * NsdManager#PROTOCOL_*}
         *
         * @throws IllegalArgumentException if {@code serviceType} is an empty string or {@code
         * protocolType} is a value other than {@link NsdManager#PROTOCOL_*}
         */
        public Builder(@NonNull String serviceType, int protocolType) {
            if (TextUtils.isEmpty(serviceType)) {
                throw new IllegalArgumentException("Service type cannot be empty");
            }
            NsdManager.checkProtocol(protocolType);
            mServiceType = serviceType;
            mProtocolType = protocolType;
        }

        /**
         * Sets the optional subtype of the services to be discovered.
         *
         * If a non-empty {@code subtype} is specified, only services with this {@code subtype}
         * will be queried, rather than all services of the base service type.
         *
         * @param subtype {@code null} or the subtype value with the trailing "._sub" removed
         */
        @NonNull
        public Builder setSubtype(@Nullable String subtype) {
            mSubtype = subtype;
            return this;
        }

        /**
         * Sets the {@link network} on which the discovery queries should be sent.
         *
         * @param network the discovery network or {@code null} if the query should be sent on
         * all supported networks
         */
        @NonNull
        public Builder setNetwork(@Nullable Network network) {
            mNetwork = network;
            return this;
        }

        /** Creates a new {@link DiscoveryRequest} object. */
        @NonNull
        public DiscoveryRequest build() {
            return new DiscoveryRequest(mServiceType, mSubtype, mProtocolType, mNetwork);
        }
    }
}
