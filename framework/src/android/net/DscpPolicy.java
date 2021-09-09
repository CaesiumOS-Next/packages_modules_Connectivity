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

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.os.Parcelable;

import com.android.net.module.util.InetAddressUtils;

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

    /** The unique policy ID. */
    private int mPolicyId;

    /** The QoS DSCP marking to be added to packets matching the policy. */
    private int mDscp = 0;

    /** The source IP address. */
    private @Nullable InetAddress mSrcAddr = null;

    /** The destination IP address. */
    private @Nullable InetAddress mDstAddr = null;

    /** The source port. */
    private int mSrcPort = 0;

    /** The IP protocol that the policy requires. Cannot be used with next header. */
    private int mProtocol = 0;

    /** Start of destination port range. Inclusive range. Cannot be used with mDstPort. */
    private int mDstPortRangeStart = 0;

    /** End of destination port range. Inclusive range. Cannot be used with mDstPort. */
    private int mDstPortRangeEnd = 0;

    /**
     * Implement the Parcelable interface
     */
    public int describeContents() {
        return 0;
    }

    /** @hide */
    //@Retention(RetentionPolicy.SOURCE)
    @android.annotation.IntDef(prefix = "DSCP_POLICY_STATUS_", value = {
        DSCP_POLICY_STATUS_SUCCESS,
        DSCP_POLICY_STATUS_REQUEST_DECLINED,
        DSCP_POLICY_STATUS_REQUESTED_CLASSIFIER_NOT_SUPPORTED,
        DSCP_POLICY_STATUS_INSUFFICIENT_PROCESSING_RESOURCES
    })
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    public @interface DscpPolicyStatus {}

    public static @NonNull String dscpPolicyStatusToString(@DscpPolicyStatus int value) {
        switch (value) {
            case DSCP_POLICY_STATUS_SUCCESS:
                    return "DSCP_POLICY_STATUS_SUCCESS";
            case DSCP_POLICY_STATUS_REQUEST_DECLINED:
                    return "DSCP_POLICY_STATUS_REQUEST_DECLINED";
            case DSCP_POLICY_STATUS_REQUESTED_CLASSIFIER_NOT_SUPPORTED:
                    return "DSCP_POLICY_STATUS_REQUESTED_CLASSIFIER_NOT_SUPPORTED";
            case DSCP_POLICY_STATUS_INSUFFICIENT_PROCESSING_RESOURCES:
                    return "DSCP_POLICY_STATUS_INSUFFICIENT_PROCESSING_RESOURCES";
            default: return Integer.toHexString(value);
        }
    }

    /**
     * Creates a new DscpPolicy.
     *
     * @param policyId
     *   The unique policy ID.
     * @param dscp
     *   The QoS DSCP marking to be added to packets matching the policy.
     * @param srcAddr
     *   The source IP address.
     * @param dstAddr
     *   The destination IP address.
     * @param srcPort
     *   The source port.
     * @param protocol
     *   The IP protocol that the policy requires. Cannot be used with next header.
     * @param dstPortRangeStart
     *   Start of destination port range. Inclusive range. Cannot be used with mDstPort.
     * @param dstPortRangeEnd
     *   End of destination port range. Inclusive range. Cannot be used with mDstPort.
     */
    public DscpPolicy(
            int policyId,
            int dscp,
            @Nullable InetAddress srcAddr,
            @Nullable InetAddress dstAddr,
            int srcPort,
            int protocol,
            int dstPortRangeStart,
            int dstPortRangeEnd) {
        this.mPolicyId = policyId;
        this.mDscp = dscp;
        this.mSrcAddr = srcAddr;
        this.mDstAddr = dstAddr;
        this.mSrcPort = srcPort;
        this.mProtocol = protocol;
        this.mDstPortRangeStart = dstPortRangeStart;

        // If start range has been set and not end, then set
        // end == start.
        if (dstPortRangeStart !=0 && dstPortRangeEnd == 0)
            this.mDstPortRangeEnd = dstPortRangeStart;
        else
            this.mDstPortRangeEnd = dstPortRangeEnd;

        // onConstructed(); // You can define this method to get a callback
    }

    /**
     * The unique policy ID.
     */
    public int getPolicyId() {
        return mPolicyId;
    }

    /**
     * The QoS DSCP marking to be added to packets matching the policy.
     */
    public int getDscp() {
        return mDscp;
    }

    /**
     * The source IP address.
     */
    public @Nullable InetAddress getSrcAddr() {
        return mSrcAddr;
    }

    /**
     * The destination IP address.
     */
    public @Nullable InetAddress getDstAddr() {
        return mDstAddr;
    }

    /**
     * The source port.
     */
    public int getSrcPort() {
        return mSrcPort;
    }

    /**
     * The IP protocol that the policy requires. Cannot be used with next header.
     */
    public int getProtocol() {
        return mProtocol;
    }

    /**
     * Start of destination port range. Inclusive range. Cannot be used with mDstPort.
     */
    public int getDstPortRangeStart() {
        return mDstPortRangeStart;
    }

    /**
     * End of destination port range. Inclusive range. Cannot be used with mDstPort.
     */
    public int getDstPortRangeEnd() {
        return mDstPortRangeEnd;
    }

    /**
     * The unique policy ID.
     */
    public @NonNull DscpPolicy setPolicyId( int value) {
        mPolicyId = value;
        return this;
    }

    /**
     * The QoS DSCP marking to be added to packets matching the policy.
     */
    public @NonNull DscpPolicy setDscp( int value) {
        mDscp = value;
        return this;
    }

    /**
     * The source IP address.
     */
    public @NonNull DscpPolicy setSrcAddr(@NonNull InetAddress value) {
        mSrcAddr = value;
        return this;
    }

    /**
     * The destination IP address.
     */
    public @NonNull DscpPolicy setDstAddr(@NonNull InetAddress value) {
        mDstAddr = value;
        return this;
    }

    /**
     * The source port.
     */
    public @NonNull DscpPolicy setSrcPort( int value) {
        mSrcPort = value;
        return this;
    }

    /**
     * The IP protocol that the policy requires. Cannot be used with next header.
     */
    public @NonNull DscpPolicy setProtocol( int value) {
        mProtocol = value;
        return this;
    }

    /**
     * Start of destination port range. Inclusive range. Cannot be used with mDstPort.
     */
    public @NonNull DscpPolicy setDstPortRangeStart( int value) {
        mDstPortRangeStart = value;
        return this;
    }

    /**
     * End of destination port range. Inclusive range. Cannot be used with mDstPort.
     */
    public @NonNull DscpPolicy setDstPortRangeEnd( int value) {
        mDstPortRangeEnd = value;
        return this;
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
                "dstPortRangeStart = " + mDstPortRangeStart + ", " +
                "dstPortRangeEnd = " + mDstPortRangeEnd +
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
                && java.util.Objects.equals(mSrcAddr, that.mSrcAddr)
                && java.util.Objects.equals(mDstAddr, that.mDstAddr)
                && mSrcPort == that.mSrcPort
                && mProtocol == that.mProtocol
                && mDstPortRangeStart == that.mDstPortRangeStart
                && mDstPortRangeEnd == that.mDstPortRangeEnd;
    }

    @Override
    public int hashCode() {
        int _hash = 1;
        _hash = 31 * _hash + mPolicyId;
        _hash = 31 * _hash + mDscp;
        _hash = 31 * _hash + java.util.Objects.hashCode(mSrcAddr);
        _hash = 31 * _hash + java.util.Objects.hashCode(mDstAddr);
        _hash = 31 * _hash + mSrcPort;
        _hash = 31 * _hash + mProtocol;
        _hash = 31 * _hash + mDstPortRangeStart;
        _hash = 31 * _hash + mDstPortRangeEnd;
        return _hash;
    }

    @Override
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        int flg = 0;
        dest.writeInt(flg);
        dest.writeInt(mPolicyId);
        dest.writeInt(mDscp);
        InetAddressUtils.parcelInetAddress(dest, mSrcAddr, flags);
        InetAddressUtils.parcelInetAddress(dest, mDstAddr, flags);
        dest.writeInt(mSrcPort);
        dest.writeInt(mProtocol);
        dest.writeInt(mDstPortRangeStart);
        dest.writeInt(mDstPortRangeEnd);
    }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    DscpPolicy(@NonNull android.os.Parcel in) {
        int flg = in.readInt();
        int policyId = in.readInt();
        int dscp = in.readInt();
        InetAddress srcAddr = InetAddressUtils.unparcelInetAddress(in);
        InetAddress dstAddr = InetAddressUtils.unparcelInetAddress(in);
        int srcPort = in.readInt();
        int protocol = in.readInt();
        int dstPortRangeStart = in.readInt();
        int dstPortRangeEnd = in.readInt();

        this.mPolicyId = policyId;
        this.mDscp = dscp;
        this.mSrcAddr = srcAddr;
        this.mDstAddr = dstAddr;
        this.mSrcPort = srcPort;
        this.mProtocol = protocol;
        this.mDstPortRangeStart = dstPortRangeStart;
        this.mDstPortRangeEnd = dstPortRangeEnd;
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
    @SuppressWarnings("WeakerAccess")
    public static final class Builder {

        private int mPolicyId;
        private int mDscp;
        private @Nullable InetAddress mSrcAddr;
        private @Nullable InetAddress mDstAddr;
        private int mSrcPort;
        private int mProtocol;
        private int mDstPortRangeStart;
        private int mDstPortRangeEnd;

        private long mBuilderFieldsSet = 0L;

        /**
         * Creates a new Builder.
         *
         * @param policyId
         *   The unique policy ID.
         */
        public Builder(
                int policyId) {
            mPolicyId = policyId;
        }

        /**
         * The unique policy ID.
         */
        public @NonNull Builder setPolicyId(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1;
            mPolicyId = value;
            return this;
        }

        /**
         * The QoS DSCP marking to be added to packets matching the policy.
         */
        public @NonNull Builder setDscp(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2;
            mDscp = value;
            return this;
        }

        /**
         * The source IP address.
         */
        public @NonNull Builder setSrcAddr(@NonNull InetAddress value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4;
            mSrcAddr = value;
            return this;
        }

        /**
         * The destination IP address.
         */
        public @NonNull Builder setDstAddr(@NonNull InetAddress value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x10;
            mDstAddr = value;
            return this;
        }

        /**
         * The source port.
         */
        public @NonNull Builder setSrcPort(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x40;
            mSrcPort = value;
            return this;
        }

        /**
         * The IP protocol that the policy requires. Cannot be used with next header.
         */
        public @NonNull Builder setProtocol(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x80;
            mProtocol = value;
            return this;
        }

        /**
         * Start of destination port range. Inclusive range. Cannot be used with mDstPort.
         */
        public @NonNull Builder setDstPortRangeStart(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x100;
            mDstPortRangeStart = value;
            return this;
        }

        /**
         * End of destination port range. Inclusive range. Cannot be used with mDstPort.
         */
        public @NonNull Builder setDstPortRangeEnd(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x200;
            mDstPortRangeEnd = value;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @NonNull DscpPolicy build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x400; // Mark builder used

            if ((mBuilderFieldsSet & 0x2) == 0) {
                mDscp = 0;
            }
            if ((mBuilderFieldsSet & 0x4) == 0) {
                mSrcAddr = null;
            }
            if ((mBuilderFieldsSet & 0x10) == 0) {
                mDstAddr = null;
            }
            if ((mBuilderFieldsSet & 0x40) == 0) {
                mSrcPort = 0;
            }
            if ((mBuilderFieldsSet & 0x80) == 0) {
                mProtocol = 0;
            }
            if ((mBuilderFieldsSet & 0x100) == 0) {
                mDstPortRangeStart = 0;
            }
            if ((mBuilderFieldsSet & 0x200) == 0) {
                mDstPortRangeEnd = 0;
            }
            DscpPolicy o = new DscpPolicy(
                    mPolicyId,
                    mDscp,
                    mSrcAddr,
                    mDstAddr,
                    mSrcPort,
                    mProtocol,
                    mDstPortRangeStart,
                    mDstPortRangeEnd);
            return o;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x400) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }
}
