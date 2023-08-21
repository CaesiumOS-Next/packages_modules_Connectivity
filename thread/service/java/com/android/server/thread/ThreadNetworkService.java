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

package com.android.server.thread;

import android.content.Context;
import android.os.Build;

import androidx.annotation.RequiresApi;

/**
 * Implementation of the Thread network service.
 *
 * This is the entry point of Android Thread feature.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public class ThreadNetworkService {
    /**
     * Creates new {@link ThreadNetworkService} instance.
     *
     * @throws UnsupportedOperationException if the Thread network feature is not enabled.
     */
    public ThreadNetworkService(Context context) {
        this(context, ThreadNetworkFlags.getInstance());
    }

    @VisibleForTesting
    ThreadNetworkService(Context context, ThreadNetworkFlags flags) {
        if (!flags.isThreadEnabled()) {
            throw new UnsupportedOperationException("Thread network feature is disabled");
        }
    }

    /**
     * Called by the service initializer.
     *
     * <p>{@see com.android.server.SystemService#onBootPhase}.
     */
    public void onBootPhase(int phase) {
        if (phase == PHASE_BOOT_COMPLETED) {
            // TODO: initialize ThreadNetworkManagerService
        }
    }
}
