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

import java.net.InetAddress;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Range;

import com.android.net.module.util.InetAddressUtils;

import java.lang.annotation.Retention;
import java.util.Objects;


/**
 * DSCP policy to be set on the requesting NetworkAgent.
 */
public final class DscpPolicy implements Parcelable {
    /**
     * Policy was successfully added.
     */
    public static final int DSCP_POLICY_STATUS_SUCCESS = 0;

    /**
     * Policy was rejected for any reason besides invalid classifier or insufficient resources.
     */
    public static final int DSCP_POLICY_STATUS_REQUEST_DECLINED = 1;

    /**
     * Requested policy contained a classifier which is not supported.
     */
    public static final int DSCP_POLICY_STATUS_REQUESTED_CLASSIFIER_NOT_SUPPORTED = 2;

    /**
     * TODO: should this error case be supported?
     */
    public static final int DSCP_POLICY_STATUS_INSUFFICIENT_PROCESSING_RESOURCES = 3;

    /**
     * Policy was deleted.
     */
    public static final int DSCP_POLICY_STATUS_DELETED = 4;

    /** The unique policy ID. AP is responsible for maintaining unique
     * policy IDs. In the case where a policy with an existing ID is created, the new
     * policy will update the existing policy with the same ID.
     */
    private final int mPolicyId;

    /** The QoS DSCP marking to be added to packets matching the policy. */
    private final int mDscp;

    /** The source IP address. */
    private final @Nullable InetAddress mSrcAddr;

    /** The destination IP address. */
    private final @Nullable InetAddress mDstAddr;

    /** The source port. */
    private final int mSrcPort;

    /** The IP protocol that the policy requires. */
    private final int mProtocol;

    /** Destination port range. Inclusive range. */
    private final @Nullable Range<Integer> mDstPortRange;

    /**
     * Implement the Parcelable interface
     */
    public int describeContents() {
        return 0;
    }

    /** @hide */
    //@Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "DSCP_POLICY_STATUS_", value = {
        DSCP_POLICY_STATUS_SUCCESS,
        DSCP_POLICY_STATUS_REQUEST_DECLINED,
        DSCP_POLICY_STATUS_REQUESTED_CLASSIFIER_NOT_SUPPORTED,
        DSCP_POLICY_STATUS_INSUFFICIENT_PROCESSING_RESOURCES,
        DSCP_POLICY_STATUS_DELETED
    })
    @Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface DscpPolicyStatus {}

    /**
     * Creates a new DscpPolicy.
     *
     * @param policyId The unique policy ID. AP is responsible for maintaining unique
     * policy IDs. In the case where a policy with an existing ID is created, the new
     * policy will update the existing policy with the same ID.
     * @param dscp The QoS DSCP marking to be added to packets matching the policy.
     * @param srcAddr The source IP address.
     * @param dstAddr The destination IP address.
     * @param srcPort The source port.
     * @param protocol The IP protocol that the policy requires.
     * @param dstPortRange Destination port range. Inclusive range.
     * @hide
     */
    public DscpPolicy(
            int policyId,
            int dscp,
            @Nullable InetAddress srcAddr,
            @Nullable InetAddress dstAddr,
            int srcPort,
            int protocol,
            Range<Integer> dstPortRange) {
        this.mPolicyId = policyId;
        this.mDscp = dscp;
        this.mSrcAddr = srcAddr;
        this.mDstAddr = dstAddr;
        this.mSrcPort = srcPort;
        this.mProtocol = protocol;
        this.mDstPortRange = dstPortRange;
    }

    /**
     * The unique policy ID.
     *
     * AP is responsible for maintaining unique
     * policy IDs. In the case where a policy with an existing ID is created, the new
     * policy will update the existing policy with the same ID
     *
     * @return Policy ID set in Builder, must always be set.
     * @hide
     */
    public int getPolicyId() {
        return mPolicyId;
    }

    /**
     * The QoS DSCP marking to be added to packets matching the policy.
     *
     * @return DSCP value set in Builder or 0 if none was set.
     * @hide
     */
    public int getDscpValue() {
        return mDscp;
    }

    /**
     * The source IP address.
     *
     * @return Source IP address set in Builder or null if none was set.
     * @hide
     */
    public @Nullable InetAddress getSrcAddr() {
        return mSrcAddr;
    }

    /**
     * The destination IP address.
     *
     * @return Destination IP address set in Builder or null if none was set.
     * @hide
     */
    public @Nullable InetAddress getDstAddr() {
        return mDstAddr;
    }

    /**
     * The source port.
     *
     * @return Source port set in Builder or 0 if no port was set.
     * @hide
     */
    public int getSrcPort() {
        return mSrcPort;
    }

    /**
     * The IP protocol that the policy requires.
     *
     * @return Protocol set in Builder or 0 if no protocol was set. 0 indicates that
     * any protocol will be matched.
     * @hide
     */
    public int getProtocol() {
        return mProtocol;
    }

    /**
     * Destination port range. Inclusive range.
     *
     * @return Range<Integer> set in Builder or null if none was set.
     * @hide
     */
    public @Nullable Range<Integer> getDstPortRange() {
        return mDstPortRange;
    }

    @Override
    public String toString() {
        return "DscpPolicy { " +
                "policyId = " + mPolicyId + ", " +
                "dscp = " + mDscp + ", " +
                "srcAddr = " + mSrcAddr + ", " +
                "dstAddr = " + mDstAddr + ", " +
                "srcPort = " + mSrcPort + ", " +
                "protocol = " + mProtocol + ", " +
                "dstPortRange Start = " +
                (mDstPortRange != null ? mDstPortRange.getLower() : "none") +
                ", " + "dstPortRange End = " +
                (mDstPortRange.getUpper() != null ? mDstPortRange.getUpper() : "none") +
        " }";
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        @SuppressWarnings("unchecked")
        DscpPolicy that = (DscpPolicy) o;
        return true
                && mPolicyId == that.mPolicyId
                && mDscp == that.mDscp
                && Objects.equals(mSrcAddr, that.mSrcAddr)
                && Objects.equals(mDstAddr, that.mDstAddr)
                && mSrcPort == that.mSrcPort
                && mProtocol == that.mProtocol
                && Objects.equals(mDstPortRange, that.mDstPortRange);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPolicyId, mDscp, mSrcAddr.hashCode(),
                mDstAddr.hashCode(), mSrcPort, mProtocol, mDstPortRange.hashCode());
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mPolicyId);
        dest.writeInt(mDscp);
        InetAddressUtils.parcelInetAddress(dest, mSrcAddr, flags);
        InetAddressUtils.parcelInetAddress(dest, mDstAddr, flags);
        dest.writeInt(mSrcPort);
        dest.writeInt(mProtocol);
        dest.writeBoolean(mDstPortRange != null ? true : false);
        if (mDstPortRange != null) {
            dest.writeInt(mDstPortRange.getLower());
            dest.writeInt(mDstPortRange.getUpper());
        }
    }

    /** @hide */
    DscpPolicy(@NonNull Parcel in) {
        this.mPolicyId = in.readInt();
        this.mDscp = in.readInt();
        this.mSrcAddr = InetAddressUtils.unparcelInetAddress(in);
        this.mDstAddr = InetAddressUtils.unparcelInetAddress(in);
        this.mSrcPort = in.readInt();
        this.mProtocol = in.readInt();
        if (in.readBoolean())
            this.mDstPortRange = new Range<Integer>(in.readInt(), in.readInt());
        else
            this.mDstPortRange = null;
    }

    public static final @NonNull Parcelable.Creator<DscpPolicy> CREATOR
            = new Parcelable.Creator<DscpPolicy>() {
        @Override
        public DscpPolicy[] newArray(int size) {
            return new DscpPolicy[size];
        }

        @Override
        public DscpPolicy createFromParcel(@NonNull android.os.Parcel in) {
            return new DscpPolicy(in);
        }
    };

    /**
     * A builder for {@link DscpPolicy}
     */
    public static final class Builder {

        private int mPolicyId;
        private int mDscp;
        private @Nullable InetAddress mSrcAddr;
        private @Nullable InetAddress mDstAddr;
        private int mSrcPort;
        private int mProtocol;
        private @Nullable Range<Integer> mDstPortRange;

        private long mBuilderFieldsSet = 0L;

        /**
         * Creates a new Builder.
         *
         * @param policyId The unique policy ID. AP is responsible for maintaining unique
         * policy IDs. In the case where a policy with an existing ID is created, the new
         * policy will update the existing policy with the same ID
         */
        public Builder(int policyId) {
            mPolicyId = policyId;
        }

        /**
         * The QoS DSCP marking to be added to packets matching the policy.
         *
         * @hide
         */
        @SystemApi
        // No getter needed in builder.
        @SuppressWarnings("MissingGetterMatchingBuilder")
        public @NonNull Builder setDscp(int value) {
            mDscp = value;
            return this;
        }

        /**
         * The source IP address.
         *
         * @hide
         */
        @SystemApi
        // No getter needed in builder.
        @SuppressWarnings("MissingGetterMatchingBuilder")
        public @NonNull Builder setSrcAddr(@NonNull InetAddress value) {
            mSrcAddr = value;
            return this;
        }

        /**
         * The destination IP address.
         *
         * @hide
         */
        @SystemApi
        // No getter needed in builder.
        @SuppressWarnings("MissingGetterMatchingBuilder")
        public @NonNull Builder setDstAddr(@NonNull InetAddress value) {
            mDstAddr = value;
            return this;
        }

        /**
         * The source port.
         *
         * @hide
         */
        @SystemApi
        // No getter needed in builder.
        @SuppressWarnings("MissingGetterMatchingBuilder")
        public @NonNull Builder setSrcPort(int value) {
            mSrcPort = value;
            return this;
        }

        /**
         * The IP protocol that the policy requires.
         *
         * @hide
         */
        @SystemApi
        // No getter needed in builder.
        @SuppressWarnings("MissingGetterMatchingBuilder")
        public @NonNull Builder setProtocol(int value) {
            mProtocol = value;
            return this;
        }

        /**
         * Start of destination port range. Inclusive range. Cannot be used with mDstPort.
         *
         * @hide
         */
        @SystemApi
        // No getter needed in builder.
        @SuppressWarnings("MissingGetterMatchingBuilder")
        public @NonNull Builder setDstPortRange(@NonNull Range<Integer> range) {
            mDstPortRange = range;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @NonNull DscpPolicy build() {
            return new DscpPolicy(
                    mPolicyId,
                    mDscp,
                    mSrcAddr,
                    mDstAddr,
                    mSrcPort,
                    mProtocol,
                    mDstPortRange);
        }
    }
}
