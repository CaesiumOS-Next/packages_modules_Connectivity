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

package com.android.server.connectivity.mdns.internal;

import android.os.Looper;

import androidx.annotation.NonNull;

import com.android.net.module.util.TimerFileDescriptor;
import com.android.server.connectivity.mdns.AbstractAccurateDelayCallback;

/**
 * The delay callback for delivering scheduled tasks accurately.
 */
public class AccurateDelayCallback extends TimerFileDescriptor implements
        AbstractAccurateDelayCallback {
    private static final String TAG = AccurateDelayCallback.class.getSimpleName();

    public AccurateDelayCallback(@NonNull Looper looper) {
        super(looper);
    }

    @Override
    public boolean isSupported() {
        return true;
    }

    @Override
    public void setDelayedTask(@NonNull Runnable task, long delayMs) {
        super.setDelayedTask(task, delayMs);
    }

    @Override
    public void cancelTask() {
        super.cancelTask();
    }

    @Override
    public boolean hasDelayedTask() {
        return super.hasDelayedTask();
    }
}
