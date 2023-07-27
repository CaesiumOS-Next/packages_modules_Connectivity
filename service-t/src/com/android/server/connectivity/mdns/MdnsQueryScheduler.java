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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Pair;

import com.android.net.module.util.SharedLog;

/**
 * THe query scheduler class for calculating next query tasks
 */
public class MdnsQueryScheduler {

    /**
     * The argument for tracking the query tasks status.
     */
    public static class ScheduledQueryTaskArgs {
        public final QueryTaskConfig config;
        public final long timeToRun;
        public final long minTtlExpirationTimeWhenScheduled;
        public final long sessionId;

        ScheduledQueryTaskArgs(@NonNull QueryTaskConfig config, long timeToRun,
                long minTtlExpirationTimeWhenScheduled, long sessionId) {
            this.config = config;
            this.timeToRun = timeToRun;
            this.minTtlExpirationTimeWhenScheduled = minTtlExpirationTimeWhenScheduled;
            this.sessionId = sessionId;
        }
    }
    @Nullable
    private ScheduledQueryTaskArgs mLastScheduledQueryTaskArgs;

    @NonNull
    private final SharedLog mSharedLog;
    public MdnsQueryScheduler(@NonNull SharedLog sharedLog) {
        mSharedLog = sharedLog;
    }

    public void resetLastScheduledQueryTaskArgs() {
        mLastScheduledQueryTaskArgs = null;
    }

    @Nullable
    public Pair<ScheduledQueryTaskArgs, Long> maybeRescheduleCurrentRun(long now,
            long minRemainingTtl, long lastSentTime, long sessionId) {
        if (mLastScheduledQueryTaskArgs == null) {
            return null;
        }
        if (!mLastScheduledQueryTaskArgs.config.shouldUseQueryBackoff()) {
            return null;
        }

        final long timeToRun = calculateTimeToRun(mLastScheduledQueryTaskArgs,
                mLastScheduledQueryTaskArgs.config, now, minRemainingTtl, lastSentTime);

        if (timeToRun < mLastScheduledQueryTaskArgs.timeToRun) {
            return null;
        }

        return calculateQueryTaskParameters(mLastScheduledQueryTaskArgs.config, minRemainingTtl,
                timeToRun, now, sessionId);
    }

    @NonNull
    public Pair<ScheduledQueryTaskArgs, Long> scheduleNextRun(
            @NonNull QueryTaskConfig currentConfig,
            long minRemainingTtl,
            long now,
            long lastSentTime,
            long sessionId,
            boolean isInitialQuery) {
        final QueryTaskConfig nextRunConfig = currentConfig.getConfigForNextRun();
        final long timeToRun;
        if (isInitialQuery) {
            timeToRun = now + nextRunConfig.delayUntilNextTaskWithoutBackoffMs;
        } else {
            timeToRun = calculateTimeToRun(mLastScheduledQueryTaskArgs,
                    nextRunConfig, now, minRemainingTtl, lastSentTime);
        }
        return calculateQueryTaskParameters(nextRunConfig, minRemainingTtl, timeToRun, now,
                sessionId);
    }

    @NonNull
    public Pair<ScheduledQueryTaskArgs, Long> calculateQueryTaskParameters(
            @NonNull QueryTaskConfig nextRunConfig,
            long minRemainingTtl,
            long timeToRun,
            long now,
            long sessionId
    ) {
        mLastScheduledQueryTaskArgs = new ScheduledQueryTaskArgs(nextRunConfig, timeToRun,
                minRemainingTtl + now, sessionId);
        // The timeWhenScheduled could be greater than the timeToRun if the Runnable is delayed.
        long timeToNextTasksWithBackoffInMs = Math.max(timeToRun - now, 0);
        mSharedLog.log(String.format("Next run: sessionId: %d, in %d ms",
                sessionId, timeToNextTasksWithBackoffInMs));

        return new Pair<>(mLastScheduledQueryTaskArgs, timeToNextTasksWithBackoffInMs);
    }

    public ScheduledQueryTaskArgs initializeQueryTaskArguments(@NonNull QueryTaskConfig taskConfig,
            long now, long minRemainingTtl, long currentSessionId) {
        mLastScheduledQueryTaskArgs = new ScheduledQueryTaskArgs(taskConfig, now /* timeToRun */,
                now + minRemainingTtl/* minTtlExpirationTimeWhenScheduled */,
                currentSessionId);
        return mLastScheduledQueryTaskArgs;
    }

    private static long calculateTimeToRun(@NonNull ScheduledQueryTaskArgs taskArgs,
            QueryTaskConfig queryTaskConfig, long now, long minRemainingTtl, long lastSentTime) {
        final long baseDelayInMs = queryTaskConfig.delayUntilNextTaskWithoutBackoffMs;
        if (!queryTaskConfig.shouldUseQueryBackoff()) {
            return lastSentTime + baseDelayInMs;
        }
        if (minRemainingTtl <= 0) {
            // There's no service, or there is an expired service. In any case, schedule for the
            // minimum time, which is the base delay.
            return lastSentTime + baseDelayInMs;
        }
        // If the next TTL expiration time hasn't changed, then use previous calculated timeToRun.
        if (lastSentTime < now
                && taskArgs.minTtlExpirationTimeWhenScheduled == now + minRemainingTtl) {
            // Use the original scheduling time if the TTL has not changed, to avoid continuously
            // rescheduling to 80% of the remaining TTL as time passes
            return taskArgs.timeToRun;
        }
        return Math.max(now + (long) (0.8 * minRemainingTtl), lastSentTime + baseDelayInMs);
    }
}
