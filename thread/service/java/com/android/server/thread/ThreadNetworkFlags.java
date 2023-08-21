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

import static android.provider.DeviceConfig.NAMESPACE_TETHERING;

import com.android.net.module.util.DeviceConfigUtils;

/** Defines feature flags for the Thread. */
public class ThreadNetworkFlags {
    /**
     * Top level feature switch which controls whether the whole Thread network
     * feature is enabled or not.
     */
    public static final String THREAD_ENABLED = "thread_enabled";

    /** Returns the {@link ThreadNetworkFlags} singleton. */
    public static ThreadNetworkFlags getInstance() {
        return new ThreadNetworkFlags();
    }

    private ThreadNetworkFlags() {
    }

    /** Returns {@code true} is the Thread feature is enabled on this device. */
    public boolean isThreadEnabled() {
        return DeviceConfigUtils.getDeviceConfigPropertyBoolean(NAMESPACE_TETHERING,
                ThreadNetworkFlags.THREAD_ENABLED, /* defaultValue= */ false);
    }
}
