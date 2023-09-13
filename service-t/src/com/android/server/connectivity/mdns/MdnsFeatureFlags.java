/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.server.connectivity.mdns;

/**
 * The class that contains mDNS feature flags;
 */
public class MdnsFeatureFlags {
    /**
     * A feature flag to control whether the  mDNS offload is enabled or not.
     */
    public static final String NSD_FORCE_DISABLE_MDNS_OFFLOAD = "nsd_force_disable_mdns_offload";

    /**
     * A feature flag to control whether unicast replies should be enabled.
     *
     * <p>Enabling this feature causes replies to queries with the Query Unicast (QU) flag set to be
     * sent unicast instead of multicast, as per RFC6762 5.4.
     */
    public static final String NSD_UNICAST_REPLY_ENABLED = "nsd_unicast_reply_enabled";

    // Flag for offload feature
    public final boolean mIsMdnsOffloadFeatureEnabled;
    public final boolean mIsUnicastReplyEnabled;

    /**
     * The constructor for {@link MdnsFeatureFlags}.
     */
    public MdnsFeatureFlags(boolean isOffloadFeatureEnabled, boolean isUnicastReplyEnabled) {
        mIsMdnsOffloadFeatureEnabled = isOffloadFeatureEnabled;
        mIsUnicastReplyEnabled = isUnicastReplyEnabled;
    }


    /** Returns a {@link Builder} for {@link MdnsFeatureFlags}. */
    public static Builder newBuilder() {
        return new Builder();
    }

    /** A builder to create {@link MdnsFeatureFlags}. */
    public static final class Builder {

        private boolean mIsMdnsOffloadFeatureEnabled;
        private boolean mIsUnicastReplyEnabled;

        /**
         * The constructor for {@link Builder}.
         */
        public Builder() {
            mIsMdnsOffloadFeatureEnabled = false;
        }

        /**
         * Set whether the mDNS offload feature is enabled.
         *
         * @see #NSD_FORCE_DISABLE_MDNS_OFFLOAD
         */
        public Builder setIsMdnsOffloadFeatureEnabled(boolean isMdnsOffloadFeatureEnabled) {
            mIsMdnsOffloadFeatureEnabled = isMdnsOffloadFeatureEnabled;
            return this;
        }

        /**
         * Set whether the unicast reply feature is enabled.
         *
         * @see #NSD_UNICAST_REPLY_ENABLED
         */
        public Builder setIsUnicastReplyEnabled(boolean isUnicastReplyEnabled) {
            mIsUnicastReplyEnabled = isUnicastReplyEnabled;
            return this;
        }

        /**
         * Builds a {@link MdnsFeatureFlags} with the arguments supplied to this builder.
         */
        public MdnsFeatureFlags build() {
            return new MdnsFeatureFlags(mIsMdnsOffloadFeatureEnabled, mIsUnicastReplyEnabled);
        }

    }
}
