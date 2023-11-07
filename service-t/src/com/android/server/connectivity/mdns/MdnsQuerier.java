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

import static com.android.server.connectivity.mdns.MdnsServiceTypeClient.EVENT_QUERY_RESULT;
import static com.android.server.connectivity.mdns.MdnsServiceTypeClient.INVALID_TRANSACTION_ID;
import static com.android.server.connectivity.mdns.util.MdnsUtils.Clock;

import android.annotation.NonNull;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Pair;

import com.android.net.module.util.SharedLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

public class MdnsQuerier {

    @NonNull private final MdnsSocketClientBase mSocketClient;
    @NonNull private final String mServiceType;
    @NonNull private final Clock mClock;
    @NonNull private final SharedLog mSharedLog;
    @NonNull private final MdnsServiceTypeClient.Dependencies mDependencies;
    @NonNull private final Handler mHandler;
    @NonNull private final ScheduledExecutorService mExecutor;

    MdnsQuerier(@NonNull MdnsSocketClientBase socketClient, @NonNull String serviceType,
            @NonNull Clock clock, @NonNull SharedLog sharedLog,
            @NonNull MdnsServiceTypeClient.Dependencies dependencies, @NonNull Handler handler,
            @NonNull ScheduledExecutorService executor) {
        mSocketClient = socketClient;
        mServiceType = serviceType;
        mClock = clock;
        mSharedLog = sharedLog;
        mDependencies = dependencies;
        mHandler = handler;
        mExecutor = executor;
    }

    public static class QuerySentArguments {
        final int mTransactionId;
        final List<String> mSubTypes = new ArrayList<>();
        final MdnsQueryScheduler.ScheduledQueryTaskArgs mTaskArgs;

        QuerySentArguments(int transactionId, @NonNull List<String> subTypes,
                @NonNull MdnsQueryScheduler.ScheduledQueryTaskArgs taskArgs) {
            mTransactionId = transactionId;
            mSubTypes.addAll(subTypes);
            mTaskArgs = taskArgs;
        }
    }

    // A FutureTask that enqueues a single query, and schedule a new FutureTask for the next task.
    private class QueryTask implements Runnable {
        private final MdnsQueryScheduler.ScheduledQueryTaskArgs mTaskArgs;
        private final List<MdnsResponse> mServicesToResolve = new ArrayList<>();
        private final boolean mSendDiscoveryQueries;
        private final MdnsPacketWriter mPacketWriter;
        QueryTask(@NonNull MdnsQueryScheduler.ScheduledQueryTaskArgs taskArgs,
                @NonNull List<MdnsResponse> servicesToResolve, boolean sendDiscoveryQueries,
                @NonNull MdnsPacketWriter packetWriter) {
            mTaskArgs = taskArgs;
            mServicesToResolve.addAll(servicesToResolve);
            mSendDiscoveryQueries = sendDiscoveryQueries;
            mPacketWriter = packetWriter;
        }

        @Override
        public void run() {
            Pair<Integer, List<String>> result;
            try {
                result =
                        new EnqueueMdnsQueryCallable(
                                mSocketClient,
                                mPacketWriter,
                                mServiceType,
                                mTaskArgs.config.subtypes,
                                mTaskArgs.config.expectUnicastResponse,
                                mTaskArgs.config.transactionId,
                                mTaskArgs.config.socketKey,
                                mTaskArgs.config.onlyUseIpv6OnIpv6OnlyNetworks,
                                mSendDiscoveryQueries,
                                mServicesToResolve,
                                mClock,
                                mSharedLog)
                                .call();
            } catch (RuntimeException e) {
                mSharedLog.e(String.format("Failed to run EnqueueMdnsQueryCallable for subtype: %s",
                        TextUtils.join(",", mTaskArgs.config.subtypes)), e);
                result = Pair.create(INVALID_TRANSACTION_ID, new ArrayList<>());
            }
            mDependencies.sendMessage(
                    mHandler, mHandler.obtainMessage(EVENT_QUERY_RESULT,
                            new QuerySentArguments(result.first, result.second, mTaskArgs)));
        }
    }

    /**
     * Make a QueryTask and submit it to the executor.
     */
    public void makeQueryTaskAndSubmitToExecutor(
            @NonNull MdnsQueryScheduler.ScheduledQueryTaskArgs taskArgs,
            @NonNull List<MdnsResponse> servicesToResolve,
            boolean sendDiscoveryQueries, @NonNull MdnsPacketWriter packetWriter) {
        final QueryTask queryTask =
                new QueryTask(taskArgs, servicesToResolve, sendDiscoveryQueries, packetWriter);
        mExecutor.submit(queryTask);
    }
}
