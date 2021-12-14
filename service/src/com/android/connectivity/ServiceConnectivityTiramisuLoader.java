/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.connectivity;

import android.content.Context;
import android.os.IBinder;
import android.util.Log;

import com.android.modules.utils.build.SdkLevel;
import com.android.server.NsdService;

/**
 * Helper class to create a new instance of all services which are updatable since Android T. These
 * services depend on framework-t jar which load from different bootclasspath. So they can't
 * create on {@link com.android.server.ConnectivityServiceInitializer} directly.
 */
public final class ServiceConnectivityTiramisuLoader {
    private static final String TAG = ServiceConnectivityTiramisuLoader.class.getSimpleName();
    private ServiceConnectivityTiramisuLoader() {}

    /** Return NsdService instance or null if current SDK is lower than T */
    public static IBinder getNsdService(final Context context) {
        if (!SdkLevel.isAtLeastT()) return null;
        try {
            return NsdService.create(context);
        } catch (InterruptedException e) {
            Log.d(TAG, "Can not get NSD service. e=" + e);
            return null;
        }
    }
}
