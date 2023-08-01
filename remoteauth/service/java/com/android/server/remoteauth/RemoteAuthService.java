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

package com.android.server.remoteauth;

import static com.android.server.SystemService.PHASE_BOOT_COMPLETED;
import static com.android.server.SystemService.PHASE_SYSTEM_SERVICES_READY;
import static com.android.server.SystemService.PHASE_THIRD_PARTY_APPS_CAN_START;

import android.annotation.Nullable;
import android.content.Context;
import android.remoteauth.IDeviceDiscoveryListener;
import android.remoteauth.IRemoteAuthService;

/** Service implementing remoteauth functionality. */
public class RemoteAuthService extends IRemoteAuthService.Stub {
    public static final String TAG = "RemoteAuthService";

    public static final String FEATURE_UWB = "android.hardware.uwb";
    private final Context mContext;

    public RemoteAuthService(Context context) {
        mContext = context;

        // TODO: Create here RemoteConnectivityManager and RangingManager
    }

    /**
     * Called by the service initializer.
     *
     * <p>{@see com.android.server.SystemService#onBootPhase}.
     */
    public void onBootPhase(int phase) {
        switch (phase) {
            case PHASE_SYSTEM_SERVICES_READY:
                break;
            case PHASE_THIRD_PARTY_APPS_CAN_START:
                // Ensures that a fast pair data provider exists which will work in direct boot.
                break;
            case PHASE_BOOT_COMPLETED:
                break;
        }
    }

    @Override
    public boolean isRemoteAuthSupported() {
        return mContext.getPackageManager().hasSystemFeature(FEATURE_UWB);
    }

    @Override
    public boolean registerDiscoveryListener(IDeviceDiscoveryListener deviceDiscoveryListener,
            int userId,
            int timeoutMs,
            String packageName, @Nullable String attributionTag) {
        // TODO: implement register discovery logic
        return true;
    }

    @Override
    public void unregisterDiscoveryListener(IDeviceDiscoveryListener deviceDiscoveryListener,
            int userId,
            String packageName, @Nullable String attributionTag) {
        // TODO: implement unregister logic
    }
}
