/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.connectivity;

import android.net.INetd;

/**
 * A wrapper class for managing network and traffic permissions.
 *
 * This class encapsulates permissions represented as a bitmask, as defined in INetd.aidl
 * and used within PermissionMonitor.java.  It distinguishes between two types of permissions:
 *
 * 1. Network Permissions: These permissions, declared in INetd.aidl, are used
 *    by the Android platform's network daemon (system/netd) to control network
 *    management
 *
 * 2. Traffic Permissions: These permissions are used internally by the eBPF maps to manage
 *    fine-grained network traffic filtering and control.
 *
 * This wrapper ensures that no new permission definitions, here or in aidl, conflict with any
 * existing permissions. This prevents unintended interactions or overrides.
 *
 * @hide
 */
public class NetworkPermissions {

    /*
     * Below are network permissions declared in INetd.aidl and used by the platform. Using these is
     * equivalent to using the values in android.net.INetd.
     */
    public static final int PERMISSION_NONE = INetd.PERMISSION_NONE;
    public static final int PERMISSION_NETWORK = INetd.PERMISSION_NETWORK;
    public static final int PERMISSION_SYSTEM = INetd.PERMISSION_SYSTEM;

    /*
     * Below are traffic permissions used by the BPF maps.
     */
    public static final int TRAFFIC_PERMISSION_UNINSTALLED = INetd.PERMISSION_UNINSTALLED;
    public static final int TRAFFIC_PERMISSION_INTERNET = INetd.PERMISSION_INTERNET;
    public static final int TRAFFIC_PERMISSION_UPDATE_DEVICE_STATS =
                    INetd.PERMISSION_UPDATE_DEVICE_STATS;

    /**
     * Below are the traffic permissions used by the BPF maps that are not part of the aidl
     * interface.
     *
     * PERMISSION_SDKSANDBOX_LOCALHOST indicates if an SdkSandbox UID will be allowed to
     * connect to localhost. For non SdkSandbox UIDs this bit is a no-op.
     */
    public static final int TRAFFIC_PERMISSION_SDKSANDBOX_LOCALHOST = 16;
}
