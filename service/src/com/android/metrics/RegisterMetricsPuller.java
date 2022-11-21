/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.app.StatsManager;
import android.content.Context;
import android.util.Log;
import android.util.StatsEvent;

import com.android.modules.utils.BackgroundThread;

import java.util.List;

/**
* Implements statsd pullers for ConnectivityService.
*
* <p>This class registers all connectivity metrics pullers to statsd.
*/
public class RegisterMetricsPuller implements StatsManager.StatsPullAtomCallback {
    private static final String TAG = RegisterMetricsPuller.class.getSimpleName();
    final StatsManager mStatsManager;
    public RegisterMetricsPuller(Context context) {
        mStatsManager = context.getSystemService(StatsManager.class);
        if (mStatsManager != null) {
            registerAtomPuller(CONNECTIVITY_STATE_SAMPLE);
        }
    }


    /** Registers a pulled atom ID {@code atomId} for pulling. */
    private void registerAtomPuller(int atomId) {
        mStatsManager.setPullAtomCallback(atomId, null, BackgroundThread.getExecutor(), this);
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link StatsManager#PULL_SUCCESS} with list of atoms (potentially empty) if pull
     *     succeeded, {@link StatsManager#PULL_SKIP} if pull was too frequent or atom ID is
     *     unexpected.
     */
    @Override
    public int onPullAtom(int atomTag, List<StatsEvent> data) {
        switch (atomTag) {
            case CONNECTIVITY_STATE_SAMPLE:
                data.add(ConnectivityStateSampleMetrics.buildStatsEvent());
                return StatsManager.PULL_SUCCESS;
            default:
                Log.e(TAG, "Unexpected atom tag: " + atomTag);
                return StatsManager.PULL_SKIP;
        }
    }
}
