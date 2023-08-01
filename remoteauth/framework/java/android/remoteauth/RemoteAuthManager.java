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

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;

/*
import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
//*/
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;

import java.util.Objects;

/**
 * This class provides a way to perform RemoteAuth related operations such as
 * discovering, registering and authenticating via remote authenticator.
 *
 * <p> To get a {@link RemoteAuthManager} instance, call the
 * <code>Context.getSystemService(RemoteAuthManager.class)</code>.
 *
 * @hide
 */
@SystemApi(client = MODULE_LIBRARIES)
@SystemService(Context.REMOTE_AUTH_SERVICE)
public class RemoteAuthManager {
    private static final String TAG = "RemoteAuthManager";

    private final Context mContext;
    private final IRemoteAuthManager mService;

    RemoteAuthManager(@NonNull Context context, @NonNull IRemoteAuthManager service) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(service);
        mContext = context;
        mService = service;
    }

    /**********************************************************************/
    /** The following APIs are for Settings and Registration integration **/
    /**********************************************************************/


    /** This function is used by Settings to verify this device can be enrolled in Remote Unlock
     *
     * @return true if this device can be enrolled
     *
     * @hide
     */
    public boolean isRemoteAuthSupported() {
        try {
            return mService.isRemoteAuthSupported();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

}
