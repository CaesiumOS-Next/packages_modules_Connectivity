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

package com.android.metrics;

import static android.net.netstats.NetworkStatsDataMigrationUtils.PREFIX_UID;
import static android.net.netstats.NetworkStatsDataMigrationUtils.PREFIX_UID_TAG;
import static android.net.netstats.NetworkStatsDataMigrationUtils.PREFIX_XT;

import static com.android.server.ConnectivityStatsLog.NETWORK_STATS_RECORDER_FILE_OPERATION_REPORTED;
import static com.android.server.ConnectivityStatsLog.NETWORK_STATS_RECORDER_FILE_OPERATION_REPORTED__FAST_DATA_INPUT_STATE__FASTDATAINPUT_STATE_UNKNOWN;
import static com.android.server.ConnectivityStatsLog.NETWORK_STATS_RECORDER_FILE_OPERATION_REPORTED__OPERATION_TYPE__RECORDER_OPERATION_TYPE_READ;
import static com.android.server.ConnectivityStatsLog.NETWORK_STATS_RECORDER_FILE_OPERATION_REPORTED__RECORDER_TYPE__RECORDER_TYPE_UID;
import static com.android.server.ConnectivityStatsLog.NETWORK_STATS_RECORDER_FILE_OPERATION_REPORTED__RECORDER_TYPE__RECORDER_TYPE_UIDTAG;
import static com.android.server.ConnectivityStatsLog.NETWORK_STATS_RECORDER_FILE_OPERATION_REPORTED__RECORDER_TYPE__RECORDER_TYPE_UNKNOWN;
import static com.android.server.ConnectivityStatsLog.NETWORK_STATS_RECORDER_FILE_OPERATION_REPORTED__RECORDER_TYPE__RECORDER_TYPE_XT;

import android.annotation.NonNull;
import android.net.NetworkStatsCollection;
import android.net.NetworkStatsHistory;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.ConnectivityStatsLog;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Helper class to log NetworkStats related metrics.
 *
 * This class does not provide thread-safe.
 */
public class NetworkStatsMetricsLogger {
    final Dependencies mDeps;
    int mReadIndex = 1;

    /** Dependency class */
    @VisibleForTesting
    public class Dependencies {
        /**
         * Writes a NETWORK_STATS_RECORDER_FILE_OPERATION_REPORTED event to ConnectivityStatsLog.
         */
        public void writeRecorderFileReadingStats(int recorderType, int readIndex, int readDuration,
                                                  int fileCount, int totalFileSize,
                                                  int keys, int uids, int totalHistorySize) {
            ConnectivityStatsLog.write(NETWORK_STATS_RECORDER_FILE_OPERATION_REPORTED,
                    NETWORK_STATS_RECORDER_FILE_OPERATION_REPORTED__OPERATION_TYPE__RECORDER_OPERATION_TYPE_READ,
                    recorderType,
                    readIndex,
                    readDuration,
                    fileCount,
                    totalFileSize,
                    keys,
                    uids,
                    totalHistorySize,
                    NETWORK_STATS_RECORDER_FILE_OPERATION_REPORTED__FAST_DATA_INPUT_STATE__FASTDATAINPUT_STATE_UNKNOWN);
        }
    }

    public NetworkStatsMetricsLogger() {
        mDeps = new Dependencies();
    }

    @VisibleForTesting
    public NetworkStatsMetricsLogger(Dependencies deps) {
        mDeps = deps;
    }

    private static int prefixToRecorderType(@NonNull String prefix) {
        switch (prefix) {
            case PREFIX_XT:
                return NETWORK_STATS_RECORDER_FILE_OPERATION_REPORTED__RECORDER_TYPE__RECORDER_TYPE_XT;
            case PREFIX_UID:
                return NETWORK_STATS_RECORDER_FILE_OPERATION_REPORTED__RECORDER_TYPE__RECORDER_TYPE_UID;
            case PREFIX_UID_TAG:
                return NETWORK_STATS_RECORDER_FILE_OPERATION_REPORTED__RECORDER_TYPE__RECORDER_TYPE_UIDTAG;
            default:
                return NETWORK_STATS_RECORDER_FILE_OPERATION_REPORTED__RECORDER_TYPE__RECORDER_TYPE_UNKNOWN;
        }
    }

    /**
     * Log NetworkStatsRecorder file reading event to metrics.
     */
    public void logRecorderFileReading(@NonNull String prefix, int duration,
                                              @NonNull NetworkStatsCollection collection) {
        final Set<Integer> uids = new HashSet<>();
        final Map<NetworkStatsCollection.Key, NetworkStatsHistory> entries =
                collection.getEntries();

        for (final NetworkStatsCollection.Key key : entries.keySet()) {
            uids.add(key.uid);
        }

        int totalHistorySize = 0;
        for (final NetworkStatsHistory history : entries.values()) {
            totalHistorySize += history.size();
        }

        // TODO: Fill file characteristics.
        mDeps.writeRecorderFileReadingStats(prefixToRecorderType(prefix),
                duration,
                mReadIndex++,
                0 /* fileCount */,
                0 /* totalFileSize */,
                entries.size(),
                uids.size(),
                totalHistorySize);
    }
}
