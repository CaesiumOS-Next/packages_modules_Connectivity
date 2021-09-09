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
import android.annotation.SuppressLint;
import android.annotation.SystemApi;

/**
 * Provides identifying information of a DSCP policy.
 */
 @DataClass(
//        genParcelable = true, // implied by `implements Parcelable`
        genAidl = false,       // implied by `implements Parcelable`, false because aidl needs to be in /export
//        genGetters = true,    // on by default
//        genConstDefs = true,  // implied by presence of constants with common prefix
        genBuilder = true,      // on by default if optional fields present, but suppressed by
                                // genConstructor
        genConstructor = true,  // on by default but normally suppressed by genBuilder
        genEqualsHashCode = true,
        genToString = true,
        genForEachField = true,
        genSetters = true
)
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

    @NonNull DscpPolicyParcel mPolicy;

    @Override
    public String toString() {
        return mPolicy.toString();
    }

    /**
     * Implement the Parcelable interface
     */
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<DscpPolicy> CREATOR = new Creator<DscpPolicy>() {
        @NonNull
        @Override
        public DscpPolicy createFromParcel(@NonNull final Parcel in) {
            return new DscpPolicy(new DscpPolicyParcel(in.readInt(), in.readInt(),
                    in.readString(), in.readString(), in.readInt(), in.readInt(), in.readInt(),
                    in.readInt(), in.readInt()));
        }

        @NonNull
        @Override
        public DscpPolicy[] newArray(final int size) {
            return new DscpPolicy[size];
        }
    };

    /**
     * A builder for DscpPolicy.
     *
     * @hide
     */
    @SystemApi
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

        // TODO: what should init parameters be set to?
        public Builder() {
            mPolicyId = 0;
            mDscp = 0;
            mSrcIp = "";
            mDstIp = "";
            mSrcPort = 0;
            mDstPort = 0;
            mProtocol = 0;
            mDstPortRangeStart = 0;
            mDstPortRangeEnd = 0;
        }

        /**
         * Constructor to populate the builder's values with an already built
         * {@link DscpPolicy}.
         * @param policy the {@link DscpPolicy} to populate with.
         */
        public Builder(@NonNull final DscpPolicy policy) {
            mPolicyId = policy.policyId;
            mDscp = policy.dscp;
            mSrcIp = policy.srcIp;
            mDstIp = policy.dstIp;
            mSrcPort = policy.srcPort;
            mDstPort = policy.dstPort;
            mProtocol = policy.protocol;
            mDstPortRangeStart = policy.dstPortRangeStart;
            mDstPortRangeEnd = policy.dstPortRangeEnd;
        }

        /**
         * Set the policy ID for the QoS DSCP policy. Required for the policy
         * to be added.
         *
         * @param policyId the unique ID from the AP for this policy
         * @return this
         */
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setPolicyId(final int policyId) {
            mPolicyId = policyId;
            return this;
        }

        /**
         * Set the DSCP value to be set for traffic matching this policy.
         * Required for the policy to be added.
         *
         * @param dscp the DSCP value for this policy
         * @return this
         */
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setDscp(final int dscp) {
            mDscp = dscp;
            return this;
        }

        /**
         * Set the source IP address for traffic to match to apply this policy.
         *
         * @param ip the source IP address
         * @return this
         */
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setSourceAddress(@NonNull final InetAddress ip) {
            mSrcIp = ip.toString();
            return this;
        }

        /**
         * Set the destination IP address for traffic to match to apply this policy.
         *
         * @param ip the destination ip address.
         * @return this
         */
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setDestinationAddress(@NonNull final InetAddress ip) {
            mDstIp = ip.toString();
            return this;
        }

        /**
         * Set the source port for traffic to match to apply this policy.
         *
         * @param port the source port. When 0 this classifier should be ignored.
         * @return this
         */
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setSourcePort(@IntRange(from = 0, to = 65535) final int port) {
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("Port must be between 0 and 65535.");
            }
            mSrcPort = port;
            return this;
        }

        /**
         * Set the destination port for traffic to match to apply this policy.
         *
         * @param port the destination port. When 0 this classifier should be ignored.
         * @return this
         */
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setDestinationPort(@IntRange(from = 0, to = 65535) final int port) {
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("Port must be between 0 and 65535.");
            }
            mDstPort = port;
            return this;
        }

        /**
         * Set the protocol for traffic to match to apply this policy. When 0 this classifier
         * should be ignored.
         *
         * @param protocol the protocol for the policy
         * @return this
         */
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setProtocol(final int protocol) {
            mProtocol = protocol;
            return this;
        }

        /**
         * Set the destination port range for traffic to match to apply this policy.
         *
         * @param portRangeStart the start of the port range. When 0 this classifier should be
         *                       ignored.
         * @param portRangeEnd the end of the port range. When 0 this classifier should be ignored.
         * @return this
         */
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setDestinationPortRange(
                @IntRange(from = 0, to = 65535) final int portRangeStart,
                @IntRange(from = 0, to = 65535) final int portRangeEnd) {
            if (portRangeStart > portRangeEnd) {
                throw new IllegalArgumentException("Start port cannot be above end port");
            }
            mDstPortRangeStart = portRangeStart;
            mDstPortRangeEnd = portRangeEnd;
            return this;
        }

        /**
         * Builds this DscpPolicy.
         * @return The built DscpPolicy object.
         */
        @NonNull
        public DscpPolicy build() {
            if (mDstPort > 0 && (mDstPortRangeStart > 0 || mDstPortRangeEnd > 0)) {
                throw new IllegalArgumentException(
                        "Destination port and destination port range cannot be set together");
            }
            return new DscpPolicy(new DscpPolicyParcel(mPolicyId, mDscp, mSrcIp, mDstIp,
                    mSrcPort, mDstPort, mProtocol, mDstPortRangeStart, mDstPortRangeEnd));
        }
    }



    // Code below generated by codegen v1.0.15.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/packages/modules/Connectivity/framework/src/android/net/DscpPolicy.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    @android.annotation.IntDef(prefix = "DSCP_POLICY_STATUS_", value = {
        DSCP_POLICY_STATUS_SUCCESS,
        DSCP_POLICY_STATUS_REQUEST_DECLINED,
        DSCP_POLICY_STATUS_REQUESTED_CLASSIFIER_NOT_SUPPORTED,
        DSCP_POLICY_STATUS_INSUFFICIENT_PROCESSING_RESOURCES
    })
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    @com.android.internal.util.DataClass.Generated.Member
    public @interface DscpPolicyStatus {}

    @com.android.internal.util.DataClass.Generated.Member
    public static String dscpPolicyStatusToString(@DscpPolicyStatus int value) {
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

    @com.android.internal.util.DataClass.Generated.Member
    public DscpPolicy(
            @NonNull DscpPolicyParcel policy) {
        this.mPolicy = policy;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mPolicy);

        // onConstructed(); // You can define this method to get a callback
    }

    @com.android.internal.util.DataClass.Generated.Member
    public @NonNull DscpPolicyParcel getPolicy() {
        return mPolicy;
    }

    @Override
    @com.android.internal.util.DataClass.Generated.Member
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        dest.writeTypedObject(mPolicy, flags);
    }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @com.android.internal.util.DataClass.Generated.Member
    /* package-private */ DscpPolicy(@NonNull android.os.Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        DscpPolicyParcel policy = (DscpPolicyParcel) in.readTypedObject(DscpPolicyParcel.CREATOR);

        this.mPolicy = policy;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mPolicy);

        // onConstructed(); // You can define this method to get a callback
    }

    @com.android.internal.util.DataClass.Generated(
            time = 1634674964010L,
            codegenVersion = "1.0.15",
            sourceFile = "packages/modules/Connectivity/framework/src/android/net/DscpPolicy.java",
            inputSignatures = "public static final  int DSCP_POLICY_STATUS_SUCCESS\npublic static final  int DSCP_POLICY_STATUS_REQUEST_DECLINED\npublic static final  int DSCP_POLICY_STATUS_REQUESTED_CLASSIFIER_NOT_SUPPORTED\npublic static final  int DSCP_POLICY_STATUS_INSUFFICIENT_PROCESSING_RESOURCES\n @android.annotation.NonNull android.net.DscpPolicyParcel mPolicy\npublic static final @android.annotation.NonNull android.net.Creator<android.net.DscpPolicy> CREATOR\n  android.net.DscpPolicyParcel getParcel()\npublic @java.lang.Override java.lang.String toString()\npublic @java.lang.Override void writeToParcel(android.net.Parcel,int)\npublic  int describeContents()\nclass DscpPolicy extends java.lang.Object implements [android.net.Parcelable]\n")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
