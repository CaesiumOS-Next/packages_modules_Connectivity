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

package android.net.nsd;

import android.annotation.NonNull;

/**
 * OffloadEngine is an interface for mDns hardware offloading.
 *
 * An offloading engine can interact with the firmware code to instruct the hardware to
 * offload some of mDns network traffic before it reached android OS. This can improve the
 * power consumption performance of the host system by not always waking up the OS to handle
 * the mDns packet when the device is in low power mode.
 *
 * @hide
 */
//@SystemApi
public interface OffloadEngine {
    /**
     * Indicates the OffloadEngine can transmit the mRawOffloadPacket for the mDns queries.
     */
    int OFFLOAD_CAPABILITY_TRANSMIT = 1;
    /**
     * Indicates the OffloadEngine can filter and drop the mDns queries.
     */
    int OFFLOAD_CAPABILITY_FILTER_QUERIES = 1 << 1;
    /**
     * Indicates the OffloadEngine can filter and drop the mDns replies.
     */
    int OFFLOAD_CAPABILITY_FILTER_REPLIES = 1 << 2;
    /**
     * To be called when the OffloadServiceInfo is added or updated.
     *
     * @param info The OffloadServiceInfo to add or update.
     */
    void addOrUpdateOffloadServiceInfo(@NonNull OffloadServiceInfo info);

    /**
     * To be called when the OffloadServiceInfo is removed.
     *
     * @param info The OffloadServiceInfo to remove.
     */
    void removeOffloadServiceInfo(@NonNull OffloadServiceInfo info);
}
