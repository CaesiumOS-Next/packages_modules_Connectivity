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

/**
 * Details for DSCP policy.
 * {@hide}
 */
parcelable DscpPolicyParcel {
    /** The unique policy ID. */
    int mPolicyId;

    /** The QoS DSCP marking to be added to packets matching the policy. */
    int mDscp;

    /** The source IP address. */
    String mSrcIp;

    /** The destination IP address. */
    String mDstIp;

    /** The source port. */
    int mSrcPort;

    /** The destination port. Cannot be used if port range is specified. */
    int mDstPort;

    /** The IP protocl that the policy requires. Cannot be used with next header. */
    int mProtocol;

    /** Start of destination port range. Inclussive range. Cannot be used with mDstPort. */
    int mDstPortRangeStart;

    /** End of destination port range. Inclussive range. Cannot be used with mDstPort. */
    int mDstPortRangeEnd;
}