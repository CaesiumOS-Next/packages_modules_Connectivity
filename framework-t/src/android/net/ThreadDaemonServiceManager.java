/*
 * Copyright (C) 2020 The Android Open Source Project
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
package android.net;

import android.annotation.NonNull;
import android.os.IBinder;

/**
 * Provides a way to obtain the binder of the Thread daemon service (i.e. "ot_daemon").
 *
 * @hide
 */
public class ThreadDaemonServiceManager {
    /** Service name for the Thread daemon service. */
    public static final String THREAD_DAEMON_SERVICE = "ot_daemon";

    private final IBinder mBinder;

    public ThreadDaemonServiceManager(@NonNull IBinder binder) {
        mBinder = binder;
    }

    /** Returns an {@link IBinder} representing the Thread daemon stable AIDL interface. */
    @NonNull
    public IBinder getService() {
        return mBinder;
    }
}
