/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.ethernet;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.IpConfiguration;
import android.net.NetworkCapabilities;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * A class representing a configured ethernet network.
 */
public class EthernetConfiguration implements Parcelable {
    private static final String TAG = EthernetConfiguration.class.getSimpleName();

    private final IpConfiguration mIpConfiguration;

    private final NetworkCapabilities mNetworkCapabilities;

    public EthernetConfiguration() {
        mIpConfiguration = new IpConfiguration();
        mNetworkCapabilities = new NetworkCapabilities();
    }

    public EthernetConfiguration(IpConfiguration ipConfiguration,
            NetworkCapabilities capabilities) {
        mIpConfiguration = ipConfiguration;
        mNetworkCapabilities = capabilities;
    }

    public EthernetConfiguration(@NonNull EthernetConfiguration source) {
        if (source == null) {
            mIpConfiguration = new IpConfiguration();
            mNetworkCapabilities = new NetworkCapabilities();
            return;
        }
        mIpConfiguration = new IpConfiguration(source.mIpConfiguration);
        mNetworkCapabilities = new NetworkCapabilities(source.mNetworkCapabilities);
    }

    protected EthernetConfiguration(Parcel in) {
        mIpConfiguration = in.readParcelable(IpConfiguration.class.getClassLoader());
        mNetworkCapabilities = in.readParcelable(NetworkCapabilities.class.getClassLoader());
    }

    /**
     * Get the IpConfiguration object associated with this EthernetConfiguration.
     * @hide
     */
    @NonNull
    @SystemApi
    public IpConfiguration getIpConfiguration() {
        return new IpConfiguration(mIpConfiguration);
    }

    /**
     * Get the NetworkCapabilities object associated with this EthernetConfiguration.
     * @hide
     */
    @NonNull
    @SystemApi
    public NetworkCapabilities getNetworkCapabilities() {
        return new NetworkCapabilities(mNetworkCapabilities);
    }

    @Override
    public String toString() {
        StringBuilder sbuf = new StringBuilder();

        sbuf.append("IP configurations:\n");
        sbuf.append(mIpConfiguration.toString());

        sbuf.append(", Network capacbilities:\n");
        sbuf.append(mNetworkCapabilities.toString());
        return sbuf.toString();
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o == this) {
            return true;
        }

        if (!(o instanceof EthernetConfiguration)) {
            return false;
        }

        EthernetConfiguration other = (EthernetConfiguration) o;
        return Objects.equals(this.mIpConfiguration, other.mIpConfiguration)
                && Objects.equals(this.mNetworkCapabilities, other.mNetworkCapabilities);
    }

    /** Implement the Parcelable interface */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mIpConfiguration, flags);
        dest.writeParcelable(mNetworkCapabilities, flags);
    }

    /** Implement the Parcelable interface */
    public static final @NonNull Creator<EthernetConfiguration> CREATOR =
            new Creator<EthernetConfiguration>() {
                public EthernetConfiguration createFromParcel(Parcel in) {
                    return new EthernetConfiguration(in);
                }

                public EthernetConfiguration[] newArray(int size) {
                    return new EthernetConfiguration[size];
                }
            };

    /** Implement the Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    public static final class Builder {
        private IpConfiguration mIpConfiguration;
        private NetworkCapabilities mNetworkCapabilities;

        private final EthernetConfiguration mEthernetConfiguration =
                new EthernetConfiguration();

        /** Set IP configuration for current ethernet configuration */
        @NonNull
        public Builder setIpConfiguration(IpConfiguration ipConfiguration) {
            mIpConfiguration = ipConfiguration;
            return this;
        }

        /** Set network capabilities for current ethernet configuration */
        @NonNull
        public Builder setNetworkCapabilities(NetworkCapabilities capabilities) {
            mNetworkCapabilities = capabilities;
            return this;
        }

        /** Build an EthernrtConfiguration object */
        @NonNull
        public EthernetConfiguration build() {
            return new EthernetConfiguration(mIpConfiguration, mNetworkCapabilities);
        }
    }
}
