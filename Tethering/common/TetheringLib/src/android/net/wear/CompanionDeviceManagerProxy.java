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

package android.net.wear;

import android.companion.AssociationInfo;
import android.os.RemoteException;

import java.util.List;

public class CompanionDeviceManagerProxy {
    public static final String COMPANION_DEVICE_MANAGER_PROXY_SERVICE =
            "companion_device_manager_proxy";
    private final ICompanionDeviceManagerProxy mService;

    public CompanionDeviceManagerProxy(ICompanionDeviceManagerProxy service) {
        mService = service;
    }

    public List<AssociationInfo> getAllAssociations() {
        try {
            return mService.getAllAssociations();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }
}
