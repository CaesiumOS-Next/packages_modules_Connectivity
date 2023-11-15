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

import static com.android.server.connectivity.mdns.MdnsServiceTypeClient.INVALID_TRANSACTION_ID;
import static com.android.server.connectivity.mdns.util.MdnsUtils.Clock;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.SharedLog;
import com.android.server.connectivity.mdns.MdnsPacketRepeater.PacketRepeaterCallback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

public class MdnsQuerier {
    @VisibleForTesting
    static final int EVENT_START_QUERYTASK = 1;
    static final int EVENT_QUERY_RESULT = 2;
    @NonNull private final MdnsSocketClientBase mSocketClient;
    @NonNull private final String mServiceType;
    @NonNull private final String[] mServiceTypeLabels;
    @NonNull private final Clock mClock;
    @NonNull private final SharedLog mSharedLog;
    @NonNull private final Dependencies mDependencies;
    @NonNull private final Handler mHandler;
    @NonNull private final ScheduledExecutorService mExecutor;
    @NonNull private final MdnsServiceCache mServiceCache;
    @NonNull private final MdnsQueryScheduler mScheduler;
    @NonNull private final MdnsServiceCache.CacheKey mCacheKey;
    @NonNull private final List<String> mResolveInstanceNames = new ArrayList<>();
    @NonNull private final QueryCallback mQueryCallback;
    @NonNull private final QueryRepeater mQueryRepeater;

    // The session ID increases when startSendAndReceive() is called where we schedule a
    // QueryTask for
    // new subtypes. It stays the same between packets for same subtypes.
    private long mCurrentSessionId = 0;
    private long mLastSentTime;

    private class QueryTaskHandler extends Handler {
        QueryTaskHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_START_QUERYTASK: {
                    final MdnsQueryScheduler.ScheduledQueryTaskArgs taskArgs =
                            (MdnsQueryScheduler.ScheduledQueryTaskArgs) msg.obj;
                    // QueryTask should be run immediately after being created (not be scheduled in
                    // advance). Because the result of "makeResponsesForResolve" depends on answers
                    // that were received before it is called, so to take into account all answers
                    // before sending the query, it needs to be called just before sending it.
                    final List<MdnsResponse> servicesToResolve =
                            mServiceCache.makeResponsesForResolve(
                                    mResolveInstanceNames, mCacheKey, mServiceTypeLabels);
                    final QueryTask queryTask = new QueryTask(taskArgs, servicesToResolve,
                            +servicesToResolve.size() < mResolveInstanceNames.size(),
                            mDependencies.createMdnsPacketWriter());
                    mExecutor.submit(queryTask);
                    break;
                }
                case EVENT_QUERY_RESULT: {
                    final QuerySentArguments sentResult = (QuerySentArguments) msg.obj;
                    if (sentResult.mTaskArgs.sessionId != mCurrentSessionId) {
                        break;
                    }

                    mQueryCallback.onQuerySent(sentResult.mTransactionId, sentResult.mSubTypes);

                    final long now = mClock.elapsedRealtime();
                    mLastSentTime = now;
                    final long minRemainingTtl =
                            mServiceCache.getServiceMinRemainingTtl(mCacheKey, now);
                    MdnsQueryScheduler.ScheduledQueryTaskArgs args =
                            mScheduler.scheduleNextRun(
                                    sentResult.mTaskArgs.config,
                                    minRemainingTtl,
                                    now,
                                    mLastSentTime,
                                    sentResult.mTaskArgs.sessionId
                            );
                    mDependencies.sendMessageDelayed(
                            mHandler,
                            mHandler.obtainMessage(EVENT_START_QUERYTASK, args),
                            calculateTimeToNextTask(args, now, mSharedLog));

                    break;
                }
            }
        }
    }

    /**
     * Dependencies of MdnsServiceTypeClient, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        /**
         * @see Handler#sendMessageDelayed(Message, long)
         */
        public void sendMessageDelayed(@NonNull Handler handler, @NonNull Message message,
                long delayMillis) {
            handler.sendMessageDelayed(message, delayMillis);
        }

        /**
         * @see Handler#removeMessages(int)
         */
        public void removeMessages(@NonNull Handler handler, int what) {
            handler.removeMessages(what);
        }

        /**
         * @see Handler#hasMessages(int)
         */
        public boolean hasMessages(@NonNull Handler handler, int what) {
            return handler.hasMessages(what);
        }

        /**
         * @see Handler#post(Runnable)
         */
        public void sendMessage(@NonNull Handler handler, @NonNull Message message) {
            handler.sendMessage(message);
        }

        /**
         * @see MdnsPacketWriter
         */
        public MdnsPacketWriter createMdnsPacketWriter() {
            return new MdnsPacketWriter(MdnsConstants.DEFAULT_MTU);
        }
    }

    MdnsQuerier(@NonNull String serviceType, @NonNull MdnsSocketClientBase socketClient,
            @NonNull ScheduledExecutorService executor, @NonNull Clock clock,
            @NonNull SharedLog sharedLog, @NonNull Looper looper,
            @NonNull MdnsServiceCache serviceCache, @NonNull MdnsServiceCache.CacheKey cacheKey,
            @NonNull QueryCallback callback, @NonNull MdnsSender mdnsSender) {
        this(serviceType, socketClient, executor, clock, sharedLog, looper, serviceCache, cacheKey,
                callback, new Dependencies(), mdnsSender);
    }

    MdnsQuerier(@NonNull String serviceType, @NonNull MdnsSocketClientBase socketClient,
            @NonNull ScheduledExecutorService executor, @NonNull Clock clock,
            @NonNull SharedLog sharedLog, @NonNull Looper looper,
            @NonNull MdnsServiceCache serviceCache, @NonNull MdnsServiceCache.CacheKey cacheKey,
            @NonNull QueryCallback callback, @NonNull Dependencies dependencies,
            @NonNull MdnsSender mdnsSender) {
        mSocketClient = socketClient;
        mServiceType = serviceType;
        mServiceTypeLabels = TextUtils.split(serviceType, "\\.");
        mClock = clock;
        mSharedLog = sharedLog;
        mHandler = new QueryTaskHandler(looper);
        mExecutor = executor;
        mServiceCache = serviceCache;
        mScheduler = new MdnsQueryScheduler();
        mCacheKey = cacheKey;
        mQueryCallback = callback;
        mDependencies = dependencies;
        mQueryRepeater = new QueryRepeater(looper, mdnsSender, new QueryingCallback(), sharedLog);
    }

    private static class QuerySentArguments {
        private final int mTransactionId;
        private final List<String> mSubTypes = new ArrayList<>();
        private final MdnsQueryScheduler.ScheduledQueryTaskArgs mTaskArgs;

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

    private class QueryingCallback implements PacketRepeaterCallback<QueryRequest> {
        @Override
        public void onSent(int index, @NonNull QueryRequest request, int sentPacketCount) {
            // TODO: Implement packet sent
        }
    }

    private class QueryRepeater extends MdnsPacketRepeater<QueryRequest> {
        QueryRepeater(@NonNull Looper looper,
                @NonNull MdnsSender sender,
                @Nullable PacketRepeaterCallback<QueryRequest> cb,
                @NonNull SharedLog sharedLog) {
            super(looper, sender, cb, sharedLog, MdnsDiscoveryManager.DBG);
        }
    }

    private class QueryRequest implements MdnsPacketRepeater.Request {
        @NonNull
        @Override
        public MdnsPacket getPacket(int index) {
            // TODO: Implement query packet
            final MdnsPacket mdnsPacket = new MdnsPacket(
                    0, /* transactionId */
                    MdnsConstants.FLAGS_QUERY,
                    Collections.emptyList(), /* questions */
                    Collections.emptyList(), /* answers */
                    Collections.emptyList(), /* authorityRecords */
                    Collections.emptyList() /* additionalRecords */);
            return mdnsPacket;
        }

        @Override
        public long getDelayMs(int nextIndex) {
            // TODO: Implement query time
            return 0L;
        }

        @Override
        public int getNumSends() {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Schedule the query task.
     */
    public void scheduleQueryTask(@NonNull QueryTaskConfig taskConfig,
            @NonNull List<String> resolveInstanceNames, boolean hadReply) {
        mResolveInstanceNames.clear();
        mResolveInstanceNames.addAll(resolveInstanceNames);

        final long now = mClock.elapsedRealtime();
        if (mLastSentTime == 0) {
            mLastSentTime = now;
        }
        final long minRemainingTtl = mServiceCache.getServiceMinRemainingTtl(mCacheKey, now);
        if (hadReply) {
            MdnsQueryScheduler.ScheduledQueryTaskArgs args =
                    mScheduler.scheduleNextRun(
                            taskConfig,
                            minRemainingTtl,
                            now,
                            mLastSentTime,
                            mCurrentSessionId
                    );
            mDependencies.sendMessageDelayed(
                    mHandler,
                    mHandler.obtainMessage(EVENT_START_QUERYTASK, args),
                    calculateTimeToNextTask(args, now, mSharedLog));
        } else {
            final List<MdnsResponse> servicesToResolve = mServiceCache.makeResponsesForResolve(
                    resolveInstanceNames, mCacheKey, mServiceTypeLabels);
            final QueryTask queryTask = new QueryTask(
                    mScheduler.scheduleFirstRun(taskConfig, now,
                            minRemainingTtl, mCurrentSessionId), servicesToResolve,
                    servicesToResolve.size() < resolveInstanceNames.size(),
                    mDependencies.createMdnsPacketWriter());
            mExecutor.submit(queryTask);
        }
    }

    private void removeScheduledTask() {
        mDependencies.removeMessages(mHandler, EVENT_START_QUERYTASK);
        mSharedLog.log("Remove EVENT_START_QUERYTASK" + ", current session: " + mCurrentSessionId);
        ++mCurrentSessionId;
    }

    /**
     * Cancel all tasks.
     */
    public void cancelAll() {
        removeScheduledTask();
        mScheduler.cancelScheduledRun();
    }

    /**
     * Reschedule the query task if necessary.
     */
    public void rescheduleQueryTaskIfNecessary() {
        if (mDependencies.hasMessages(mHandler, EVENT_START_QUERYTASK)) {
            final long now = mClock.elapsedRealtime();
            final long minRemainingTtl = mServiceCache.getServiceMinRemainingTtl(mCacheKey, now);
            MdnsQueryScheduler.ScheduledQueryTaskArgs args =
                    mScheduler.maybeRescheduleCurrentRun(now, minRemainingTtl,
                            mLastSentTime, mCurrentSessionId + 1);
            if (args != null) {
                removeScheduledTask();
                mDependencies.sendMessageDelayed(
                        mHandler,
                        mHandler.obtainMessage(EVENT_START_QUERYTASK, args),
                        calculateTimeToNextTask(args, now, mSharedLog));
            }
        }
    }

    private static long calculateTimeToNextTask(MdnsQueryScheduler.ScheduledQueryTaskArgs args,
            long now, SharedLog sharedLog) {
        long timeToNextTasksWithBackoffInMs = Math.max(args.timeToRun - now, 0);
        sharedLog.log(String.format("Next run: sessionId: %d, in %d ms",
                args.sessionId, timeToNextTasksWithBackoffInMs));
        return timeToNextTasksWithBackoffInMs;
    }

    /*** Callbacks for listening query sent */
    public interface QueryCallback {
        /*** Notify the query is sent */
        void onQuerySent(int transactionId, List<String> subtypes);
    }
}
