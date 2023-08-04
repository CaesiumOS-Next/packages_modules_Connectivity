/*
 * Copyright 2023 The Android Open Source Project
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

package android.remoteauth;

import android.annotation.NonNull;

import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Reports newly discovered devices.
 * Note: The frequency of the callback is dependent on whether the caller
 * is in the foreground or background. Foreground callbacks will occur
 * as fast as the underlying medium supports, whereas background
 * use cases will be rate limited to improve performance (ie, only on
 * found/lost/significant changes).
 *
 * @hide
 */
//@SystemApi(client = MODULE_LIBRARIES)
public interface DeviceDiscoveryCallback {
    /** @hide **/
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({State.STATE_LOST, State.STATE_SEEN})
    @interface State {
        /** The device is no longer seen in the latest scan. */
        int STATE_LOST = 1;
        /** The device is seen in the latest scan. */
        int STATE_SEEN = 2;
    }

    /** A callback function invoked for every change in discovered device state.
     *
     * @param device discovered device
     * @param state indicates if found or lost
     *
     * @hide
     */
    void onDeviceUpdate(@NonNull DiscoveredDevice device, @State int state);

    /** A callback function invoked when discovery is stopped due to timeout was reached
     *
     * @hide
     */
    void onTimeout();

}
