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

package com.google.android.server.remoteauth.connectivity;


import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Utility class to perform common functionality for CDM. */
public final class CdmUtils {
    // Use CompanionDeviceManager.MESSAGE_REQUEST_REMOTE_AUTHENTICATION
    private static final int REMOTE_AUTH_MSG_TYPE = 0x63827765;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private CdmUtils() {}

    /**
     * Helper method to add message received listener.
     *
     * @param companionDeviceManager instance of {@link CompanionDeviceManager}
     * @param onMessageReceivedListener message listener.
     */
    //    public static void addOnMessageReceivedListener(
    //            @NonNull CompanionDeviceManager companionDeviceManager,
    //            @NonNull CompanionDeviceManager.OnMessageReceivedListener
    // onMessageReceivedListener) {
    //        companionDeviceManager.addOnMessageReceivedListener(
    //                EXECUTOR, REMOTE_AUTH_MSG_TYPE, onMessageReceivedListener);
    //    }
    //
    //    /**
    //     * Helper method to add message received listener.
    //     * @param companionDeviceManager instance of {@link CompanionDeviceManager}
    //     * @param onTransportsChangedListener transports changed listener.
    //     */
    //    public static void addOnTransportsChangedListener(
    //            @NonNull CompanionDeviceManager companionDeviceManager,
    //            @NonNull CompanionDeviceManager.OnTransportsChangedListener
    // onTransportsChangedListener) {
    //        companionDeviceManager.addOnMessageReceivedListener(
    //                EXECUTOR, REMOTE_AUTH_MSG_TYPE, onTransportsChangedListener);
    //    }
}
