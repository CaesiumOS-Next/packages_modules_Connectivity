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

import android.os.Handler;
import android.os.Message;

import androidx.annotation.NonNull;

import com.android.net.module.util.RealtimeScheduler;
import com.android.server.connectivity.mdns.AbstractRealtimeDelayCallback;

/**
 * The delay callback for delivering scheduled tasks accurately.
 */
public class RealtimeDelayCallback extends RealtimeScheduler implements
        AbstractRealtimeDelayCallback {
    private static final String TAG = RealtimeDelayCallback.class.getSimpleName();

    public RealtimeDelayCallback(@NonNull Handler handler) {
        super(handler);
    }

    @Override
    public boolean isSupported() {
        return true;
    }

    @Override
    public boolean sendDelayedMessage(@NonNull Message message, long delayMs) {
        return super.sendDelayedMessage(message, delayMs);
    }

    @Override
    public void removeDelayedMessage(int what) {
        super.removeDelayedMessage(what);
    }

    @Override
    public boolean hasDelayedMessage(int what) {
        return super.hasDelayedMessage(what);
    }

    @Override
    public void close() {
        super.close();
    }
}
