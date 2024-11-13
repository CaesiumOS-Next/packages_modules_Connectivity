/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.annotation.Nullable;
import android.os.Looper;
import android.util.Pair;

import androidx.annotation.GuardedBy;

import com.android.net.module.util.RealtimeScheduler;

public class RealtimeDiscoveryExecutor extends DiscoveryExecutor {
    @GuardedBy("mPendingTasks")
    @Nullable
    RealtimeScheduler mRealtimeScheduler;

    RealtimeDiscoveryExecutor(@Nullable Looper defaultLooper) {
        super(defaultLooper);
    }

    /**
     * Execute the given function after the specified amount of time elapses.
     *
     * Note: It's using RealtimeScheduler instead of Handler to execute delayed task accurately.
     */
    @Override
    public void executeDelayed(Runnable function, long delayMillis) {
        final RealtimeScheduler realtimeScheduler;
        synchronized (mPendingTasks) {
            if (this.mHandler == null) {
                mPendingTasks.add(Pair.create(function, delayMillis));
                return;
            } else {
                if (this.mRealtimeScheduler == null) {
                    this.mRealtimeScheduler = new RealtimeScheduler(mHandler);
                }
                realtimeScheduler = this.mRealtimeScheduler;
            }
        }
        realtimeScheduler.postDelayed(function, delayMillis);
    }

    @Override
    public void shutDown() {
        super.shutDown();
        synchronized (mPendingTasks) {
            if (mRealtimeScheduler != null) {
                mRealtimeScheduler.close();
            }
        }
    }
}
