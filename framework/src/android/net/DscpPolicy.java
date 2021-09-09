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

    /** The unique policy ID. */
    private final int mPolicyId;

    /** The QoS DSCP marking to be added to packets matching the policy. */
    private final int mDscp;

    /** The source IP address. */
    private final String mSrcIp;

    /** The destination IP address. */
    private final String mDstIp;

    /** The source port. */
    private final int mSrcPort;

    /** The destination port. Cannot be used if port range is specified. */
    private final int mDstPort;

    /** The IP protocl that the policy requires. Cannot be used with next header. */
    private final int mProtocol;

    /** The next header subfield to match the policy to. Cannot be used with protocol. */
    private final int mNextHeader;

    /** Start of destination port range. Inclussive range. Cannot be used with mDstPort. */
    private final int mDstPortRangeStart;

    /** End of destination port range. Inclussive range. Cannot be used with mDstPort. */
    private final int mDstPortRangeEnd;

    /**
     * Gets the policy ID.
     *
     * @return the id of DSCP policy.
     */
    public int getPolicyId() {
        return mPolicyId;
    }

    /**
     * Gets the DSCP marking.
     *
     * @return the DSCP marking to be used for the policy
     */
    public int getDscp() {
        return mDscp;
    }

    /**
     * Gets the source IP address.
     *
     * @return the source IP address of the policy.
     */
    public int getSrcIp() {
        return mPolicyId;
    }

    /**
     * Gets the destination IP address.
     *
     * @return the destination IP address of the policy.
     */
    public int getDstIp() {
        return mDstIp;
    }

    /**
     * Gets the source port.
     *
     * @return the source port of the policy.
     */
    public int getSrcPort() {
        return mSrcPort;
    }

    /**
     * Gets the destination port.
     *
     * @return the destination port of the policy.
     */
    public int getDstPort() {
        return mDstPort;
    }

    /**
     * Gets the protocol.
     *
     * @return protocol of the policy.
     */
    public int getProtocol() {
        return mProtocol;
    }

    /**
     * Gets the next header.
     *
     * @return the next header of the policy.
     */
    public int getNextHeader() {
        return mNextHeader;
    }

    /**
     * Gets the start value for the destination port.
     *
     * @return the value indicating the start of the destination port range.
     */
    public int getDstPortRangeStart() {
        return mDstPortRangeStart;
    }

    /**
     * Gets the end value for the destination port.
     *
     * @return the value indicating the end of the destination port range.
     */
    public int getDstPortRangeEnd() {
        return mDstPortRangeEnd;
    }

    /**
     * Creates a {@link QosSession}.
     *
     * @param policyId uniquely identifies the policy
     * @param dscp the QoS marking of the policy
     * @param srcIp the source ip address
     * @param dstIp the destination ip address
     * @param srcPort the source port
     * @param dstPort the destination port
     * @param protocol the protocol of the policy
     * @param nextHeader the next header in the policy
     * @param portRangeStart the start value for the destination port range
     * @param portRangeEnd the end value of the destination port range
     */
    public QosSession(final int policyId, final int dscp, final int srcIp, final int dstIp,
            final int srcPort, final int dstPort, final int protocol, final int nextHeader,
            final int dstPortRangeStart, final int dstPortRangeEnd) {
        mPolicyId = policyId;
        mDscp = dscp;
        mSrcIp = srcIp;
        mDstIp = dstIp;
        mSrcPort = srcPort;
        mDstPort = dstPort;
        mProtocol = protocol;
        mNextHeader = nextHeader;
        mDstPortRangeStart = dstPortRangeStart;
        mDstPortRangeEnd = dstPortRangeEnd;
    }

    @Override
    public String toString() {
        return "DscpPolicy{"
                + "mPolicyId=" + mPolicyId
                + ", mDscp=" + mDscp
                + ", mSrcIp=" + mSrcIp
                + ", mDstIp=" + mDstIp
                + ", mSrcPort=" + mSrcPort
                + ", mDstPort=" + mDstPort
                + ", mProtocol=" + mProtocol
                + ", mNextHeader=" + mNextHeader
                + ", mDstPortRangeStart=" + mDstPortRangeStart
                + ", mDstPortRangeEnd=" + mDstPortRangeEnd
                + '}';
    }

    private DscpPolicy(final Parcel in) {
        mPolicyId = in.readInt();
        mDscp = in.readInt();
        mSrcIp = in.readInt();
        mDstIp = in.readInt();
        mSrcPort = in.readInt();
        mDstPort = in.readInt();
        mProtocol = in.readInt();
        mNextHeader = in.readInt();
        mDstPortRangeStart = in.readInt();
        mDstPortRangeEnd = in.readInt();
    }

    @NonNull
    public static final Creator<DscpPolicy> CREATOR = new Creator<DscpPolicy>() {
        @NonNull
        @Override
        public DscpPolicy createFromParcel(@NonNull final Parcel in) {
            return new DscpPolicy(in);
        }

        @NonNull
        @Override
        public DscpPolicy[] newArray(final int size) {
            return new DscpPolicy[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull final Parcel dest, final int flags) {
        dest.writeInt(mPolicyId);
        dest.writeInt(mDscp);
        dest.writeInt(mSrcIp);
        dest.writeInt(mDstIp);
        dest.writeInt(mSrcPort);
        dest.writeInt(mDstPort);
        dest.writeInt(mProtocol);
        dest.writeInt(mNextHeader);
        dest.writeInt(mDstPortRangeStart);
        dest.writeInt(mDstPortRangeEnd);
    }
}