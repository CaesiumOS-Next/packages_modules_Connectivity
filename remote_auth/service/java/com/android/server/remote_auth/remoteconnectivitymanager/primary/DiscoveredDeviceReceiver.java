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
package com.google.android.server.remote_auth.remoteconnectivitymanager;

/** Abstract class representing the device discovery callbacks. */
public abstract class DiscoveredDeviceReceiver {
    /**
     * Callback called when a device matching the discovery filter is found.
     *
     * @param discoveredDevice
     */
    public void onDiscovered(DiscoveredDevice discoveredDevice) {}

    /**
     * Callback called when a previously discovered device using
     * {@link ConnectivityManager#startDiscovery} is lost.
     *
     * @param discoveredDevice
     */
    public void onLost(DiscoveredDevice discoveredDevice) {}

    /**
     * Callback when discovery timed out.
     */
    public void onTimedout() {}
}
