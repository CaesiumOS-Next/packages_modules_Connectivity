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

import androidx.annotation.NonNull;

/**
 * The interface for accurate delay callback.
 */
public interface AbstractAccurateDelayCallback {
    /**
     * Returns if the accurate delay callback is supported or not. By default, it is not supported.
     */
    default boolean isSupported() {
        return false;
    }

    /**
     * Set a task to be executed after a specified delay.
     */
    void setDelayedTask(@NonNull Runnable task, long delayMs);

    /**
     * Cancel the scheduled task.
     */
    void cancelTask();

    /**
     * Check if there is a scheduled task.
     */
    boolean hasDelayedTask();
}
