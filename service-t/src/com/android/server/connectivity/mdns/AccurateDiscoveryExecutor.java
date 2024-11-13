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

import android.os.Looper;
import android.util.Pair;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;

import com.android.net.module.util.TimerFileDescriptor;

public class AccurateDiscoveryExecutor extends DiscoveryExecutor {
    @GuardedBy("mPendingTasks")
    @android.annotation.Nullable
    TimerFileDescriptor mTimerFd;

    AccurateDiscoveryExecutor(@Nullable Looper defaultLooper) {
        super(defaultLooper);
    }

    /**
     * Execute the given function after the specified amount of time elapses.
     *
     * Note: It's using TimerFileDescriptor instead of Handler to execute delayed task accurately.
     */
    @Override
    public void executeDelayed(Runnable function, long delayMillis) {
        final TimerFileDescriptor timerFd;
        synchronized (mPendingTasks) {
            if (this.mHandler == null) {
                mPendingTasks.add(Pair.create(function, delayMillis));
                return;
            } else {
                if (this.mTimerFd == null) {
                    this.mTimerFd = new TimerFileDescriptor(mHandler);
                }
                timerFd = this.mTimerFd;
            }
        }
        timerFd.setDelayedTask(new TimerFileDescriptor.RunnableTask(function), delayMillis);
    }
}
