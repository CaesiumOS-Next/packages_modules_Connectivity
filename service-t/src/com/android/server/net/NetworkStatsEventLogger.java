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

package com.android.server.net;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.util.IndentingPrintWriter;
import android.util.LocalLog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;

/**
 * Helper class for NetworkStatsService to log events.
 *
 * @hide
 */
public class NetworkStatsEventLogger {
    static final int POLL_REASON_UNKNOWN = 0;
    static final int POLL_REASON_RAT_CHANGED = 1;
    static final int POLL_REASON_NETWORK_STATUS = 2;
    static final int POLL_REASON_GLOBAL_ALERT = 3;
    static final int POLL_REASON_OPEN_SESSION = 4;
    static final int POLL_REASON_DUMPSYS = 5;
    static final int POLL_REASON_FORCE_UPDATE = 6;
    static final int POLL_REASON_UPSTREAM_CHANGED = 7;
    static final int POLL_REASON_REG_CALLBACK = 8;
    static final int POLL_REASON_ACTION_NETWORK_STATS_POLL = 9;
    static final int POLL_REASON_REMOVE_UIDS = 10;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            POLL_REASON_UNKNOWN,
            POLL_REASON_RAT_CHANGED,
            POLL_REASON_NETWORK_STATUS,
            POLL_REASON_GLOBAL_ALERT,
            POLL_REASON_OPEN_SESSION,
            POLL_REASON_DUMPSYS,
            POLL_REASON_FORCE_UPDATE,
            POLL_REASON_UPSTREAM_CHANGED,
            POLL_REASON_REG_CALLBACK,
            POLL_REASON_ACTION_NETWORK_STATS_POLL,
            POLL_REASON_REMOVE_UIDS
    })
    public @interface PollReason {
    }
    static final int MAX_POLL_REASON = POLL_REASON_REMOVE_UIDS;

    private static final int MAX_EVENTS_LOGS = 50;
    private final LocalLog mEventChanges = new LocalLog(MAX_EVENTS_LOGS);
    private final int[] mPollEventCounts = new int[MAX_POLL_REASON + 1];

    /**
     * Log a poll event.
     */
    public void logPollEvent(int flags, @NonNull PollEvent event) {
        mEventChanges.log("Poll(flags=" + flags + ", " + event + ")");
        mPollEventCounts[event.reason]++;
    }

    /**
     * Print the object's state into the given stream.
     */
    public void dump(@NonNull IndentingPrintWriter pw) {
        pw.println("Poll counts per reason:");
        pw.increaseIndent();
        pw.println(Arrays.toString(mPollEventCounts));
        pw.decreaseIndent();
        pw.println();

        pw.println("Recent poll events:");
        pw.increaseIndent();
        mEventChanges.dump(pw);
        pw.decreaseIndent();
        pw.println();
    }

    public static class PollEvent {
        public final int reason;
        public PollEvent(@PollReason int reason) {
            if (reason <= POLL_REASON_UNKNOWN || reason > MAX_POLL_REASON) {
                throw new IllegalArgumentException("Unsupported poll reason: " + reason);
            }
            this.reason = reason;
        }

        @Override
        public String toString() {
            return "PollEvent{" + "reason=" + reason + "}";
        }
    }
}
