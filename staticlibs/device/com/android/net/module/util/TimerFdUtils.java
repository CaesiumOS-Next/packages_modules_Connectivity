/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.net.module.util;

import android.util.Log;

import java.io.IOException;

/**
 * Contains mostly timerfd functionality.
 */
public class TimerFdUtils {
    static {
        System.loadLibrary(JniUtil.getJniLibraryName(TimerFdUtils.class.getPackage()));
    }

    private static final String TAG = TimerFdUtils.class.getSimpleName();

    /**
     * Create a timerfd.
     *
     * @throws IOException
     */
    private static native int createTimerFd() throws IOException;

    /**
     * Set given time to the timerfd.
     *
     * @param timeMs target time
     * @throws IOException
     */
    private static native void setTime(int fd, long timeMs) throws IOException;

    /**
     * Get remaining time from the timerfd.
     *
     * @throws IOException
     */
    private static native long getTime(int fd) throws IOException;

    /**
     * Create a timerfd
     */
    static int createTimerFileDescriptor() {
        try {
            return createTimerFd();
        } catch (IOException e) {
            Log.e(TAG, "createTimerFd failed", e);
            return -1;
        }
    }

    /**
     * Set expiration time to timerfd
     */
    static boolean setExpirationTime(int id, long expirationTimeMs) {
        try {
            setTime(id, expirationTimeMs);
        } catch (IOException e) {
            Log.e(TAG, "setExpirationTime failed", e);
            return false;
        }
        return true;
    }

    /**
     * Get the remaining expiration time from timerfd
     */
    static long getExpirationTime(int id) {
        try {
            return getTime(id);
        } catch (IOException e) {
            Log.e(TAG, "getExpirationTime failed", e);
            return -1;
        }
    }
}
