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

import android.os.IBinder;
import android.os.ServiceManager;

import com.android.server.thread.openthread.IOtDaemon;

/** Provides a way to access Thread daemon service with the {@link ServiceManager} API. */
public class ServiceManagerWrapper {
    static {
        System.loadLibrary("service-thread-jni");
    }

    private ServiceManagerWrapper() {}

    public static IOtDaemon waitForThreadDaemonService() {
        IBinder binder = nativeWaitForThreadDaemonService();
        if (binder == null) {
            return null;
        }
        return IOtDaemon.Stub.asInterface(binder);
    }

    private static native IBinder nativeWaitForThreadDaemonService();
}
