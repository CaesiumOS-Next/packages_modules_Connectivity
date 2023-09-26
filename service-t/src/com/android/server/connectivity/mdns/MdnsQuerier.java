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

package com.android.server.connectivity.mdns;

import static com.android.server.connectivity.mdns.util.MdnsUtils.Clock;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Looper;
import android.text.TextUtils;

import com.android.net.module.util.SharedLog;
import com.android.server.connectivity.mdns.MdnsQueryScheduler.ScheduledQueryTaskArgs;
import com.android.server.connectivity.mdns.util.MdnsUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MdnsQuerier extends MdnsPacketRepeater<MdnsQuerier.QueryTask> {
    MdnsQuerier(@NonNull Looper looper,
            @NonNull MdnsSender sender,
            @Nullable PacketRepeaterCallback<QueryTask> cb,
            @NonNull SharedLog sharedLog) {
        super(looper, sender, cb, sharedLog);
    }

    public static class QueryTask implements MdnsPacketRepeater.Request {
        private final MdnsQueryScheduler.ScheduledQueryTaskArgs mTaskArgs;
        private final String mServiceType;
        private final SocketKey mSocketKey;
        private final String[] mServiceTypeLabels;
        private final boolean mOnlyUseIpv6OnIpv6OnlyNetworks;
        private final Clock mClock;
        private final SharedLog mSharedLog;
        private final List<MdnsResponse> mServicesToResolve = new ArrayList<>();
        private final boolean mSendDiscoveryQueries;
        private final MdnsServiceCache mServiceCache;
        private final MdnsQueryScheduler mScheduler;

        public QueryTask(@NonNull ScheduledQueryTaskArgs taskArgs,
                String serviceType, SocketKey socketKey,
                boolean onlyUseIpv6OnIpv6OnlyNetworks, Clock clock,
                SharedLog sharedLog, List<MdnsResponse> servicesToResolve,
                boolean sendDiscoveryQueries, MdnsServiceCache serviceCache,
                MdnsQueryScheduler scheduler) {
            mTaskArgs = taskArgs;
            mServiceType = serviceType;
            mSocketKey = socketKey;
            mServiceTypeLabels = TextUtils.split(serviceType, "\\.");
            mOnlyUseIpv6OnIpv6OnlyNetworks = onlyUseIpv6OnIpv6OnlyNetworks;
            mClock = clock;
            mSharedLog = sharedLog;
            mServicesToResolve.addAll(servicesToResolve);
            mSendDiscoveryQueries = sendDiscoveryQueries;
            mServiceCache = serviceCache;
            mScheduler = scheduler;
        }

        @NonNull
        @Override
        public MdnsPacket getPacket(int index) {
            final List<MdnsRecord> questions = new ArrayList<>();

            if (mSendDiscoveryQueries) {
                // Base service type
                questions.add(new MdnsPointerRecord(
                        mServiceTypeLabels,
                        0L, /* receiptTimeMillis */
                        mTaskArgs.config.expectUnicastResponse,
                        0L /* ttlMillis */));
                if (!mTaskArgs.config.subtypes.isEmpty()) {
                    for (String subtype : mTaskArgs.config.subtypes) {
                        String[] labels = new String[mServiceTypeLabels.length + 2];
                        labels[0] = MdnsConstants.SUBTYPE_PREFIX + subtype;
                        labels[1] = MdnsConstants.SUBTYPE_LABEL;
                        System.arraycopy(
                                mServiceTypeLabels, 0, labels, 2, mServiceTypeLabels.length);

                        questions.add(new MdnsPointerRecord(
                                labels,
                                0L, /* receiptTimeMillis */
                                mTaskArgs.config.expectUnicastResponse,
                                0L /* ttlMillis */));
                    }
                }
            }

            final boolean unicast = mTaskArgs.config.expectUnicastResponse;

            // List of (name, type) to query
            final long now = mClock.elapsedRealtime();
            for (MdnsResponse response : mServicesToResolve) {
                final String[] serviceName = response.getServiceName();
                if (serviceName == null) continue;
                boolean renewTxt = !response.hasTextRecord() || MdnsUtils.isRecordRenewalNeeded(
                        response.getTextRecord(), now);
                boolean renewSrv = !response.hasServiceRecord() || MdnsUtils.isRecordRenewalNeeded(
                        response.getServiceRecord(), now);
                if (renewSrv && renewTxt) {
                    questions.add(new MdnsAnyRecord(serviceName, unicast));
                } else {
                    if (renewTxt) {
                        questions.add(new MdnsTextRecord(
                                serviceName,
                                0L, /* receiptTimeMillis */
                                unicast,
                                0L /* ttlMillis */));
                    }
                    if (renewSrv) {
                        questions.add(new MdnsServiceRecord(
                                serviceName,
                                0L, /* receiptTimeMillis */
                                unicast,
                                0L /* ttlMillis */));
                        // The hostname is not yet known, so queries for address records will be
                        // sent the next time the EnqueueMdnsQueryCallable is enqueued if the reply
                        // does not contain them. In practice, advertisers should include the
                        // address records when queried for SRV, although it's not a MUST
                        // requirement (RFC6763 12.2).
                    } else if (!response.hasInet4AddressRecord()
                            && !response.hasInet6AddressRecord()) {
                        final String[] host = response.getServiceRecord().getServiceHost();
                        questions.add(new MdnsInetAddressRecord(
                                host,
                                0L, /* receiptTimeMillis */
                                unicast,
                                0L /* ttlMillis */,
                                MdnsRecord.TYPE_A));
                        questions.add(new MdnsInetAddressRecord(
                                host,
                                0L, /* receiptTimeMillis */
                                unicast,
                                0L /* ttlMillis */,
                                MdnsRecord.TYPE_AAAA));
                    }
                }
            }

            final MdnsPacket mdnsPacket = new MdnsPacket(
                    mTaskArgs.config.transactionId,
                    MdnsConstants.FLAGS_QUERY,
                    questions,
                    Collections.emptyList(), /* answers */
                    Collections.emptyList(), /* authorityRecords */
                    Collections.emptyList() /* additionalRecords */);
            return mdnsPacket;
        }

        @Override
        public long getDelayMs(int nextIndex) {
            final long now = mClock.elapsedRealtime();
            final long minRemainingTtl =
                    mServiceCache.getServiceMinRemainingTtl(mServiceType, mSocketKey, now);
            ScheduledQueryTaskArgs args = mScheduler.scheduleNextRun(mTaskArgs.config,
                    minRemainingTtl, now, now /* lastSentTime */, mTaskArgs.sessionId);
            return MdnsServiceTypeClient.calculateTimeToNextTask(args, now, mSharedLog);
        }

        @Override
        public int getNumSends() {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean onlyUseIpv6OnIpv6OnlyNetworks() {
            return mOnlyUseIpv6OnIpv6OnlyNetworks;
        }

        public int getSessionId() {
            return mTaskArgs.sessionId;
        }

        public QueryTaskConfig getQueryTaskConfig() {
            return mTaskArgs.config;
        }
    }
}