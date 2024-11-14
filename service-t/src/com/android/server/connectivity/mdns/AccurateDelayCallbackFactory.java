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

import android.annotation.NonNull;
import android.os.Looper;

import com.android.server.connectivity.mdns.internal.AccurateDelayCallback;

/**
 * The factory class for creating an accurate delay callback.
 */
public class AccurateDelayCallbackFactory {

    /**
     * Creates an accurate delay callback.
     */
    public static AbstractAccurateDelayCallback createAccurateDelayCallback(
            @NonNull Looper looper) {
        return new AccurateDelayCallback(looper);
    }

    /**
     * Creates a discovery executor.
     */
    public static DiscoveryExecutor createDiscoveryExecutor(
            boolean isAccurateDelayCallbackEnabled, @NonNull Looper looper) {
        return isAccurateDelayCallbackEnabled
                ? new AccurateDiscoveryExecutor(looper)
                : new DiscoveryExecutor(looper);
    }

    private AccurateDelayCallbackFactory() {
    }
}
