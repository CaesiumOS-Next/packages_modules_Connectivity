/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.net;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.net.MacAddress;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.net.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * A {@link NetworkSpecifier} used to identify both L2CAP networks.
 *
 * An L2CAP network is not symmetrical, meaning there exists both a server (Bluetooth peripheral)
 * and a client (Bluetooth central) node. This specifier contains information required to request or
 * reserve an L2CAP network.
 *
 * An L2CAP server network allocates a PSM to be advertised to the client. Therefore, the server
 * network must always be reserved using {@link ConnectivityManager#reserveNetwork}. The subsequent
 * {@link ConnectivityManager.NetworkCallback#onReserved(NetworkCapabilities)} includes information
 * (i.e. the PSM) for the server to advertise to the client.
 *
 * The L2cap client network can be connected using {@link ConnectivityManager#requestNetwork}
 * including passing in the relevant information (i.e. PSM and destination MAC address) using the
 * {@link L2capNetworkSpecifier}.
 *
 */
@FlaggedApi(Flags.FLAG_IPV6_OVER_BLE)
public final class L2capNetworkSpecifier extends NetworkSpecifier implements Parcelable {
    /**
     * Accept any role
     * @hide
     */
    public static final int ROLE_ANY = 0;
    /**
     * Specifier describes a client network
     * @hide
     */
    public static final int ROLE_CLIENT = 1;
    /**
     * Specifier describes a server network
     * @hide
     */
    public static final int ROLE_SERVER = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = false, prefix = "ROLE_", value = {
        ROLE_ANY,
        ROLE_CLIENT,
        ROLE_SERVER
    })
    public @interface Role {}
    /**
     * Role used to distinguish client from server networks.
     * @hide
     */
    public @Role int mRole;

    /** Accept any form of compression */
    public static final int COMPRESSION_ANY = 0;
    /** Do not compress packets on this network */
    public static final int COMPRESSION_NONE = 1;
    /** Use 6lowpan header compression as specified in rfc6282 */
    public static final int COMPRESSION_6LOWPAN = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = false, prefix = "COMPRESSION_", value = {
        COMPRESSION_ANY,
        COMPRESSION_NONE,
        COMPRESSION_6LOWPAN
    })
    public @interface Compression {}
    /**
     * Compression mechanism used on this network
     * @hide
     */
    public @Compression int mCompression;

    /**
     *  The MAC address of the remote.
     *
     *  Only used in client networks. A null MacAddress matches *any* MacAddress
     *  @hide
     */
    public @Nullable MacAddress mRemoteAddress;

    /**
     * The Bluetooth L2CAP Protocol Service Multiplexer (PSM)
     * @hide
     */
    public int mPsm;


    /** @hide */
    public L2capNetworkSpecifier() {}

    public L2capNetworkSpecifier(@NonNull L2capNetworkSpecifier specifier) {
        mRole = specifier.mRole;
        mCompression = specifier.mCompression;
        mRemoteAddress = specifier.mRemoteAddress;
        mPsm = specifier.mPsm;
    }

    /**
     * Returns the role to be used for this network.
     *
     * @hide
     */
    public @Role int getRole() {
        return mRole;
    }

    /** Returns the compression mechanism for this network. */
    public @Compression int getCompression() {
        return mCompression;
    }

    /** Returns the remote MAC address for this network to connect to. */
    public @Nullable MacAddress getRemoteAddress() {
        return mRemoteAddress;
    }

    /** Returns the PSM for this network to connect to. */
    public int getPsm() {
        return mPsm;
    }

    /**
     * A builder class for L2capNetworkSpecifier */
    public static final class Builder {
        private L2capNetworkSpecifier mSpecifier;

        private Builder(@Role int role) {
            mSpecifier.mRole = role;
        }

        /** Creates an L2CAP server network specifier */
        @SuppressLint("BuilderSetStyle")
        @NonNull
        public static Builder withServerRole() {
            return new Builder(ROLE_SERVER);
        }

        /** Creates an L2CAP client network specifier */
        @SuppressLint("BuilderSetStyle")
        @NonNull
        public static Builder withClientRole() {
            return new Builder(ROLE_CLIENT);
        }

        /**
         * Configures the compression mechanism to use for this network.
         *
         * @param compression the compression mechanism to use.
         */
        @NonNull
        public Builder setCompression(@Compression int compression) {
            mSpecifier.mCompression = compression;
            return this;
        }

        /**
         * Set the remote address for the client to connect to.
         *
         * Only valid for client networks.
         *
         * @param remoteAddress the MAC address to connect to.
         */
        @NonNull
        public Builder setRemoteAddress(@NonNull MacAddress remoteAddress) {
            Objects.requireNonNull(remoteAddress);
            if (mSpecifier.mRole != ROLE_CLIENT) {
                throw new IllegalArgumentException("Remote address only valid for client networks");
            }
            mSpecifier.mRemoteAddress = remoteAddress;
            return this;
        }

        /**
         * Set the PSM for the client to connect to.
         *
         * Can only be configured on client networks.
         *
         * @param psm the Protocol Service Multiplexer (PSM) to connect to.
         */
        @NonNull
        public Builder setPsm(int psm) {
            if (mSpecifier.mRole != ROLE_CLIENT) {
                throw new IllegalArgumentException("PSM can only be requested for client networks");
            }
            mSpecifier.mPsm = psm;
            return this;
        }

        @NonNull
        public L2capNetworkSpecifier build() {
            return new L2capNetworkSpecifier(mSpecifier);
        }
    }

    /** @hide */
    @Override
    public boolean canBeSatisfiedBy(NetworkSpecifier other) {
        // TODO: implement matching semantics.
        return false;
    }

    /** @hide */
    @Override
    public int hashCode() {
        return Objects.hash(mRole, mCompression, mRemoteAddress, mPsm);
    }

    /** @hide */
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof L2capNetworkSpecifier)) return false;

        final L2capNetworkSpecifier rhs = (L2capNetworkSpecifier) obj;
        return mRole == rhs.mRole
                && mCompression == rhs.mCompression
                && Objects.equals(mRemoteAddress, rhs.mRemoteAddress)
                && mPsm == rhs.mPsm;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mRole);
        dest.writeInt(mCompression);
        dest.writeParcelable(mRemoteAddress, flags);
        dest.writeInt(mPsm);
    }

    public static final @NonNull Creator<L2capNetworkSpecifier> CREATOR = new Creator<L2capNetworkSpecifier>() {
        @Override
        public L2capNetworkSpecifier createFromParcel(Parcel in) {
            final L2capNetworkSpecifier specifier = new L2capNetworkSpecifier();
            specifier.mRole = in.readInt();
            specifier.mCompression = in.readInt();
            specifier.mRemoteAddress = in.readParcelable(null);
            specifier.mPsm = in.readInt();
            return specifier;
        }

        @Override
        public L2capNetworkSpecifier[] newArray(int size) {
            return new L2capNetworkSpecifier[size];
        }
    };

}
