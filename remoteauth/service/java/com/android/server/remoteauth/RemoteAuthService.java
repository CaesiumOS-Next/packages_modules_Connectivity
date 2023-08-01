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

import static android.content.pm.PackageManager.FEATURE_UWB;

import android.annotation.Nullable;
import android.content.Context;
import android.remoteauth.DiscoveredDevice;
import android.remoteauth.IDeviceDiscoveryListener;
import android.remoteauth.IRegisterAuthenticatorListener;
import android.remoteauth.IRemoteAuthService;
import android.remoteauth.IUnregisterAuthenticatorListener;
import android.remoteauth.RemoteAuthenticator;

import java.util.ArrayList;
import java.util.List;

/** Service implementing remoteauth functionality. */
public class RemoteAuthService extends IRemoteAuthService.Stub {
    public static final String TAG = "RemoteAuthService";
    private final Context mContext;

    public RemoteAuthService(Context context) {
        mContext = context;

        // TODO: Create here RemoteConnectivityManager and RangingManager
    }

    @Override
    public boolean isRemoteAuthSupported() {
        return mContext.getPackageManager().hasSystemFeature(FEATURE_UWB);
    }

    @Override
    public List<RemoteAuthenticator> getRegisterAuthenticators() {
        return new ArrayList<>();
    }

    @Override
    public boolean registerDiscoveryListener(IDeviceDiscoveryListener deviceDiscoveryListener,
            int userId,
            int timeout,
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

    @Override
    public boolean registerAuthenticatorListener(
            IRegisterAuthenticatorListener listener,
            DiscoveredDevice device,
            int userId) {
        // TODO: implement register authenticator logic
        return true;
    }

    @Override
    public boolean unregisterAuthenticatorListener(IUnregisterAuthenticatorListener listener,
            RemoteAuthenticator authenticator) {
        // TODO: implement unregister authenticator logic
        return true;
    }
}
