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

package com.android.server;

import android.os.RemoteException;

/**
 * Interface for Netd traffic controller functionality used from PermissionMonitor on pre T devices.
 * This is an interface to split class between devices pre T and T+.
 *
 * {@hide}
 */
public interface IBpfNetPermMaps {
    /**
     * Add ingress interface filtering rules to a list of UIDs
     *
     * For a given uid, once a filtering rule is added, the kernel will only allow packets from the
     * allowed interface and loopback to be sent to the list of UIDs.
     *
     * Calling this method on one or more UIDs with an existing filtering rule but a different
     * interface name will result in the filtering rule being updated to allow the new interface
     * instead. Otherwise calling this method will not affect existing rules set on other UIDs.
     *
     * @param ifName the name of the interface on which the filtering rules will allow packets to
     *               be received.
     * @param uids   an array of UIDs which the filtering rules will be set
     * @throws RemoteException when netd has crashed.
     */
    void addUidInterfaceRules(String ifName, int[] uids) throws RemoteException;

    /**
     * Remove ingress interface filtering rules from a list of UIDs
     *
     * Clear the ingress interface filtering rules from the list of UIDs which were previously set
     * by addUidInterfaceRules(). Ignore any uid which does not have filtering rule.
     *
     * @param uids an array of UIDs from which the filtering rules will be removed
     * @throws RemoteException when netd has crashed.
     */
    void removeUidInterfaceRules(int[] uids) throws RemoteException;

    /**
     * Assigns android.permission.INTERNET and/or android.permission.UPDATE_DEVICE_STATS to the uids
     * specified. Or remove all permissions from the uids.
     *
     * @param permissions The permission to grant, it could be either PERMISSION_INTERNET and/or
     *                    PERMISSION_UPDATE_DEVICE_STATS. If the permission is NO_PERMISSIONS, then
     *                    revoke all permissions for the uids.
     * @param uids        uid of users to grant permission
     * @throws RemoteException when netd has crashed.
     */
    void setNetPermForUids(int permissions, int[] uids) throws RemoteException;
}
