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

package android.net;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Provides identifying information of a DSCP policy.
 *
 * @hide
 */
@SystemApi
public final class DscpPolicy implements Parcelable {
    DscpPolicyParcel mPolicy;

    /** @hide */
    public DscpPolicy(DscpPolicyParcel policy) {
        mPolicy = policy;
    }

    @Override
    public String toString() {
        return "DscpPolicy{"
                + "mPolicyId=" + mPolicy.mPolicyId
                + ", mDscp=" + mPolicy.mDscp
                + ", mSrcIp=" + mPolicy.mSrcIp
                + ", mDstIp=" + mPolicy.mDstIp
                + ", mSrcPort=" + mPolicy.mSrcPort
                + ", mDstPort=" + mPolicy.mDstPort
                + ", mProtocol=" + mPolicy.mProtocol
                + ", mDstPortRangeStart=" + mPolicy.mDstPortRangeStart
                + ", mDstPortRangeEnd=" + mPolicy.mDstPortRangeEnd
                + '}';
    }

    @Override
    public void writeToParcel(@NonNull final Parcel dest, final int flags) {
        dest.writeInt(mPolicy.mPolicyId);
        dest.writeInt(mPolicy.mDscp);
        dest.writeInt(mPolicy.mSrcIp);
        dest.writeInt(mPolicy.mDstIp);
        dest.writeInt(mPolicy.mSrcPort);
        dest.writeInt(mPolicy.mDstPort);
        dest.writeInt(mPolicy.mProtocol);
        dest.writeInt(mPolicy.mDstPortRangeStart);
        dest.writeInt(mPolicy.mDstPortRangeEnd);
    }

    /**
     * A builder for DscpPolicy.
     */
    public static final class Builder {
        private final int mPolicyId;
        private final int mDscp;
        private final String mSrcIp;
        private final String mDstIp;
        private final int mSrcPort;
        private final int mDstPort;
        private final int mProtocol;
        private final int mDstPortRangeStart;
        private final int mDstPortRangeEnd;

        /**
         * ...
         *
         * @param policyId the unique ID from the AP for this policy
         * @return this
         */
        @NonNull
        public Builder setPolicyId(final int policyId) {
            mPolicyId = policyId;
            return this;
        }

        /**
         * ...
         *
         * @param dscp the DSCP value for this policy
         * @return this
         */
        @NonNull
        public Builder setDscp(final int dscp) {
            mDscp = dscp;
            return this;
        }

        /**
         * ...
         *
         * @param ip the source IP address
         * @return this
         */
        @NonNull
        public Builder setSrcIp(final int ip) {
            mSrcIp = ip;
            return this;
        }

        /**
         * ...
         *
         * @param ip the destination ip address
         * @return this
         */
        @NonNull
        public Builder setDstIp(final int ip) {
            mDstIp = ip;
            return this;
        }

        /**
         * ...
         *
         * @param port the source port
         * @return this
         */
        @NonNull
        public Builder setSrcPort(final int port) {
            mSrcPort = port;
            return this;
        }

        /**
         * ...
         *
         * @param port the destination port
         * @return this
         */
        @NonNull
        public Builder setDstPort(final int port) {
            mDstPort = port;
            return this;
        }

        /**
         * ...
         *
         * @param protocol the protocol for the policy
         * @return this
         */
        @NonNull
        public Builder setProtocol(final int protocol) {
            mProtocol = protocol;
            return this;
        }

        /**
         * ...
         *
         * @param portRangeStart the start of the port range
         * @return this
         */
        @NonNull
        public Builder setDstPortRangeStart(final int portRangeStart) {
            mDstPortRangeStart = portRangeStart;
            return this;
        }

        /**
         * ...
         *
         * @param portRangeEnd the end of the port range
         * @return this
         */
        @NonNull
        public Builder setDstPortRangeEnd(final int portRangeEnd) {
            mDstPortRangeEnd = portRangeEnd;
            return this;
        }

        /**
         * Builds this DscpPolicyBinder.
         * @return The built DscpPolicyBinder object.
         */
        @NonNull
        public DscpPolicyBinder build() {
            return new DscpPolicyBinder(new DscpPolicyParcel(mPolicyId, mDscp, mSrcIp, mDstIp,
                    mSrcPort, mDstPort, mProtocol, mDstPortRangeStart, mDstPortRangeEnd));
        }
    }
}
