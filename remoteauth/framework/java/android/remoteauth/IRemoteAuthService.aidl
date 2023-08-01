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

package android.remoteauth;

import android.remoteauth.IDeviceDiscoveryListener;
import android.remoteauth.IRegisterAuthenticatorListener;
import android.remoteauth.IUnregisterAuthenticatorListener;
import android.remoteauth.DiscoveredDevice;
import android.remoteauth.RemoteAuthenticator;

/**
 * Interface for communicating with the RemoteAuthService.
 *
 * @hide
 */
interface IRemoteAuthService {
    boolean isRemoteAuthSupported();

    List<RemoteAuthenticator> getRegisterAuthenticators();

    boolean registerDiscoveryListener(in IDeviceDiscoveryListener deviceDiscoveryListener,
                                  int userId,
                                  int timeout,
                                  String packageName,
                                  @nullable String attributionTag);

    void unregisterDiscoveryListener(in IDeviceDiscoveryListener deviceDiscoveryListener,
                                     int userId,
                                     String packageName,
                                     @nullable String attributionTag);

    boolean registerAuthenticatorListener(in IRegisterAuthenticatorListener listener,
                                          in DiscoveredDevice device,
                                          int userId);

    boolean unregisterAuthenticatorListener(in IUnregisterAuthenticatorListener listener,
                                            in RemoteAuthenticator authenticator);
}