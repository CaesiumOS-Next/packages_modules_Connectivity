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

package android.net;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;
import android.util.Log;

import java.util.Collections;
import java.util.Set;

/**
 * A class to communicate configuration info about a local network through {@link NetworkAgent}.
 * @hide
 */
// TODO : @SystemApi
public final class LocalNetworkConfig implements Parcelable {
    @Nullable
    private final NetworkRequest mUpstreamSelector;

    @NonNull
    private final Set<IpPrefix> mAilPrefixes;

    @NonNull
    private final MulticastRoutingConfig mUpstreamMulticastRoutingConfig;

    @NonNull
    private final MulticastRoutingConfig mDownstreamMulticastRoutingConfig;

    private LocalNetworkConfig(@Nullable final NetworkRequest upstreamSelector,
            @NonNull final Set<IpPrefix> ailPrefixes,
            @Nullable final MulticastRoutingConfig upstreamConfig,
            @Nullable final MulticastRoutingConfig downstreamConfig) {
        mUpstreamSelector = upstreamSelector;
        mAilPrefixes = Collections.unmodifiableSet(new ArraySet<>(ailPrefixes));
        if (null != upstreamConfig) {
            mUpstreamMulticastRoutingConfig = upstreamConfig;
        } else {
            mUpstreamMulticastRoutingConfig = MulticastRoutingConfig.CONFIG_FORWARD_NONE;
        }
        if (null != downstreamConfig) {
            mDownstreamMulticastRoutingConfig = downstreamConfig;
        } else {
            mDownstreamMulticastRoutingConfig = MulticastRoutingConfig.CONFIG_FORWARD_NONE;
        }
    }

    /**
     * Get the request choosing which network traffic from this network is forwarded to and from.
     *
     * This may be null if the local network doesn't forward the traffic anywhere.
     */
    @Nullable
    public NetworkRequest getUpstreamSelector() {
        return mUpstreamSelector;
    }

    @NonNull
    public Set<IpPrefix> getAilPrefixes() {
        return mAilPrefixes;
    }

    public @NonNull MulticastRoutingConfig getUpstreamMulticastRoutingConfig() {
        return mUpstreamMulticastRoutingConfig;
    }

    public @NonNull MulticastRoutingConfig getDownstreamMulticastRoutingConfig() {
        return mDownstreamMulticastRoutingConfig;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull final Parcel dest, final int flags) {
        dest.writeParcelable(mUpstreamSelector, flags);
        dest.writeInt(mAilPrefixes.size());
        for (final IpPrefix prefix : mAilPrefixes) {
            dest.writeParcelable(prefix, flags);
        }
        dest.writeParcelable(mUpstreamMulticastRoutingConfig, flags);
        dest.writeParcelable(mDownstreamMulticastRoutingConfig, flags);
    }

    public static final @NonNull Creator<LocalNetworkConfig> CREATOR = new Creator<>() {
        public LocalNetworkConfig createFromParcel(Parcel in) {
            final NetworkRequest upstreamSelector = in.readParcelable(null);
            final int ailPrefixSize = in.readInt();
            final ArraySet<IpPrefix> ailPrefixes = new ArraySet<>(ailPrefixSize);
            for (int i = ailPrefixSize - 1; i > 0; --i) {
                ailPrefixes.add(in.readParcelable(null));
            }
            final MulticastRoutingConfig upstreamConfig = in.readParcelable(null);
            final MulticastRoutingConfig downstreamConfig = in.readParcelable(null);
            return new LocalNetworkConfig(
                    upstreamSelector, ailPrefixes, upstreamConfig, downstreamConfig);
        }

        @Override
        public LocalNetworkConfig[] newArray(final int size) {
            return new LocalNetworkConfig[size];
        }
    };


    public static final class Builder {
        private static final String TAG = "LocalNetworkConfig.Builder";

        @Nullable
        NetworkRequest mUpstreamSelector;

        @NonNull
        final ArraySet<IpPrefix> mAilPrefixes;

        @Nullable
        MulticastRoutingConfig mUpstreamMulticastRoutingConfig;

        @Nullable
        MulticastRoutingConfig mDownstreamMulticastRoutingConfig;

        /**
         * Create a Builder
         */
        public Builder() {
            mAilPrefixes = new ArraySet<>();
        }

        /**
         * Set to choose where this local network should forward its traffic to.
         *
         * The system will automatically choose the best network matching the request as an
         * upstream, and set up forwarding between this local network and the chosen upstream.
         * If no network matches the request, there is no upstream and the traffic is not forwarded.
         * The caller can know when this changes by listening to link properties changes of
         * this network with the {@link android.net.LinkProperties#getForwardedNetwork()} getter.
         *
         * Set this to null if the local network shouldn't be forwarded. Default is null.
         */
        @NonNull
        public Builder setUpstreamSelector(@Nullable NetworkRequest upstreamSelector) {
            mUpstreamSelector = upstreamSelector;
            return this;
        }

        /**
         * Add a prefix to advertise on the infrastructure link (the selected upstream)
         *
         * This is a no-op if the prefix was already added.
         */
        @NonNull
        public Builder addAilPrefix(@NonNull final IpPrefix prefix) {
            if (!mAilPrefixes.add(prefix)) {
                Log.i(TAG, "Already added prefix : " + prefix);
            }
            return this;
        }

        /**
         * Remove a prefix to advertise on the infrastructure link (the selected upstream)
         *
         * This is a no-op if the prefix wasn't added or was already removed.
         */
        @NonNull
        public Builder removeAilPrefix(@NonNull final IpPrefix prefix) {
            if (!mAilPrefixes.remove(prefix)) {
                Log.i(TAG, "Removal of an absent prefix : " + prefix);
            }
            return this;
        }

        /**
         * Set the upstream multicast routing config.
         *
         * If null, don't route multicast packets upstream. This is equivalent to a
         * MulticastRoutingConfig in mode FORWARD_NONE. The default is null.
         */
        @NonNull
        public Builder setUpstreamMulticastRoutingConfig(@Nullable MulticastRoutingConfig cfg) {
            mUpstreamMulticastRoutingConfig = cfg;
            return this;
        }

        /**
         * Set the downstream multicast routing config.
         *
         * If null, don't route multicast packets downstream. This is equivalent to a
         * MulticastRoutingConfig in mode FORWARD_NONE. The default is null.
         */
        @NonNull
        public Builder setDownstreamMulticastRoutingConfig(@Nullable MulticastRoutingConfig cfg) {
            mDownstreamMulticastRoutingConfig = cfg;
            return this;
        }

        /**
         * Build the LocalNetworkConfig object.
         */
        @NonNull
        public LocalNetworkConfig build() {
            return new LocalNetworkConfig(mUpstreamSelector,
                    mAilPrefixes,
                    mUpstreamMulticastRoutingConfig,
                    mDownstreamMulticastRoutingConfig);
        }
    }
}
