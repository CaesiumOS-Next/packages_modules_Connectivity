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

import static com.android.server.ConnectivityStatsLog.CONNECTIVITY_STATE_SAMPLE;

import android.annotation.NonNull;
import android.app.StatsManager;
import android.content.Context;
import android.os.Handler;
import android.util.Log;
import android.util.StatsEvent;

import com.android.modules.utils.HandlerExecutor;

import java.util.List;
import java.util.function.Supplier;

/**
 * A class to register, sample and send connectivity state metrics.
 */
public class SampleConnectivityStateMetrics implements StatsManager.StatsPullAtomCallback {
    private static final String TAG = SampleConnectivityStateMetrics.class.getSimpleName();

    final Supplier<StatsEvent> mDelegate;

    /**
     * Start collecting metrics.
     * @param context some context to get services
     * @param connectivityServiceHandler the connectivity service handler
     * @param delegate a method returning data when called on the handler thread
     */
    public static void start(@NonNull final Context context,
            @NonNull final Handler connectivityServiceHandler,
            @NonNull final Supplier<StatsEvent> delegate) {
        final SampleConnectivityStateMetrics metrics = new SampleConnectivityStateMetrics(delegate);
        final StatsManager mgr = context.getSystemService(StatsManager.class);
        if (null == mgr) return; // No metrics for you
        mgr.setPullAtomCallback(CONNECTIVITY_STATE_SAMPLE, null /* metadata */,
                new HandlerExecutor(connectivityServiceHandler), metrics);
    }

    public SampleConnectivityStateMetrics(@NonNull final Supplier<StatsEvent> delegate) {
        mDelegate = delegate;
    }

    @Override
    public int onPullAtom(int atomTag, List<StatsEvent> data) {
        switch (atomTag) {
            case CONNECTIVITY_STATE_SAMPLE:
                data.add(mDelegate.get());
                return StatsManager.PULL_SUCCESS;
            default:
                Log.e(TAG, "Unexpected atom tag: " + atomTag);
                return StatsManager.PULL_SKIP;
        }
    }
}
