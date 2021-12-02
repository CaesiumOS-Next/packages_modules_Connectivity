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

package android.net.cts.util;

import android.os.HandlerThread;
import android.os.Looper;

public class CtsCallbackHandlerThread extends HandlerThread {
    private static class Singleton {
        private static final CtsCallbackHandlerThread INSTANCE = createInstance();
    }

    private CtsCallbackHandlerThread() {
        super("CtsCallbackHandlerThread");
    }

    private static CtsCallbackHandlerThread createInstance() {
        CtsCallbackHandlerThread t = new CtsCallbackHandlerThread();
        t.start();
        return t;
    }

    public static CtsCallbackHandlerThread get() {
        return Singleton.INSTANCE;
    }

    public static Looper getInstanceLooper() {
        return Singleton.INSTANCE.getLooper();
    }
}
