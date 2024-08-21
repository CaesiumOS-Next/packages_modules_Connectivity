/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.networkstack.tethering.util;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.UserHandle;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;

/**
 * Utils class for checking permissions related to Tethering APIs.
 */
public class TetheringPermissionsUtils {
    private static final String TAG = "TetherPermUtils";

    @NonNull private final Context mContext;

    public TetheringPermissionsUtils(@NonNull Context context) {
        mContext = context;
    }

    /**
     * Checks if the package name and uid belong to the Device Owner.
     */
    public boolean isDeviceOwner(final int uid, @NonNull final String packageName) {
        Pair<UserHandle, ComponentName> deviceOwner = getDeviceOwner();
        // no device owner
        if (deviceOwner == null) return false;

        return deviceOwner.first.equals(UserHandle.getUserHandleForUid(uid))
                && deviceOwner.second.getPackageName().equals(packageName);
    }

    @android.annotation.Nullable
    private Pair<UserHandle, ComponentName> getDeviceOwner() {
        DevicePolicyManager devicePolicyManager = retrieveDevicePolicyManagerFromContext(mContext);
        if (devicePolicyManager == null) return null;
        long ident = Binder.clearCallingIdentity();
        UserHandle deviceOwnerUser;
        ComponentName deviceOwnerComponent;
        try {
            deviceOwnerUser = devicePolicyManager.getDeviceOwnerUser();
            deviceOwnerComponent = devicePolicyManager.getDeviceOwnerComponentOnAnyUser();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        if (deviceOwnerUser == null || deviceOwnerComponent == null) return null;

        if (deviceOwnerComponent.getPackageName() == null) {
            // shouldn't happen
            Log.wtf(TAG, "no package name on device owner component: " + deviceOwnerComponent);
            return null;
        }
        return new Pair<>(deviceOwnerUser, deviceOwnerComponent);
    }

    private DevicePolicyManager retrieveDevicePolicyManagerFromContext(
            @NonNull final Context context) {
        DevicePolicyManager devicePolicyManager =
                context.getSystemService(DevicePolicyManager.class);
        if (devicePolicyManager == null
                && context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_DEVICE_ADMIN)) {
            Log.w(TAG, "Error retrieving DPM service");
        }
        return devicePolicyManager;
    }

    /**
     * Checks if the package name belongs to a carrier privileged app.
     */
    public boolean isCarrierApp(@NonNull final String packageName) {
        TelephonyManager telephonyManager = mContext.getSystemService(TelephonyManager.class);
        if (telephonyManager == null) {
            return false;
        }
        return telephonyManager.checkCarrierPrivilegesForPackageAnyPhone(packageName)
                == TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS;
    }

}
