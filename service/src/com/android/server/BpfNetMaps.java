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

package com.android.server;

import android.os.ServiceSpecificException;
import android.util.Log;

/**
 * BpfNetMaps is responsible for providing traffic controller relevant functionality.
 *
 * {@hide}
 */
public final class BpfNetMaps {
    private static final String TAG = "BpfNetMaps";

    static {
        System.loadLibrary("traffic_controller_jni");
        native_init();
    }

    /**
     * Add naughty app
     */
    public static void addNaughtyApp(final int uid) throws ServiceSpecificException {
        final int err = native_addNaughtyApp(uid);
        if (err != 0) {
            Log.e(TAG, "addNaughtyApp failed, errno = " + err);
            throw new ServiceSpecificException(err, "Unable to add naughty app");
        }
    }

    /**
     * Remove naughty app
     */
    public static void removeNaughtyApp(final int uid) throws ServiceSpecificException {
        final int err = native_removeNaughtyApp(uid);
        if (err != 0) {
            Log.e(TAG, "removeNaughtyApp failed, errno = " + err);
            throw new ServiceSpecificException(err, "Unable to remove naughty app");
        }
    }

    /**
     * Add nice app
     */
    public static void addNiceApp(final int uid) throws ServiceSpecificException {
        final int err = native_addNiceApp(uid);
        if (err != 0) {
            Log.e(TAG, "addNiceApp failed, errno = " + err);
            throw new ServiceSpecificException(err, "Unable to add nice app");
        }
    }

    /**
     * Remove nice app
     */
    public static void removeNiceApp(final int uid) throws ServiceSpecificException {
        final int err = native_removeNiceApp(uid);
        if (err != 0) {
            Log.e(TAG, "removeNiceApp failed, errno = " + err);
            throw new ServiceSpecificException(err, "Unable to remove nice app");
        }
    }

    /**
     * Set child chain
     */
    public static void setChildChain(final int childChain, final boolean enable)
            throws ServiceSpecificException {
        final int err = native_setChildChain(childChain, enable);
        if (err != 0) {
            Log.e(TAG, "setChildChain failed, errno = " + err);
            throw new ServiceSpecificException(err, "Unable to set child chain");
        }
    }

    /**
     * Replace UID chain
     */
    public static void replaceUidChain(final String name, final boolean isAllowlist,
            final int[] uids) throws ServiceSpecificException {
        final int err = native_replaceUidChain(name, isAllowlist, uids);
        if (err != 0) {
            Log.e(TAG, "replaceUidChain failed, errno = " + err);
            throw new ServiceSpecificException(err, "Unable to replace uid chain");
        }
    }

    /**
     * Set UID rule
     */
    public static void setUidRule(final int childChain, final int uid,
            final int firewallRule) throws ServiceSpecificException {
        final int err = native_setUidRule(childChain, uid, firewallRule);
        if (err != 0) {
            Log.e(TAG, "setUidRule failed, errno = " + err);
            throw new ServiceSpecificException(err, "Unable to set uid rule");
        }
    }

    /**
     * Add UID interface rules
     */
    public static void addUidInterfaceRules(final String ifName, final int[] uids)
            throws ServiceSpecificException {
        final int err = native_addUidInterfaceRules(ifName, uids);
        if (err != 0) {
            Log.e(TAG, "addUidInterfaceRules failed, errno = " + err);
            throw new ServiceSpecificException(err, "Unable to add uid interface rules");
        }
    }

    /**
     * Remove UID interface rules
     */
    public static void removeUidInterfaceRules(final int[] uids) throws ServiceSpecificException {
        final int err = native_removeUidInterfaceRules(uids);
        if (err != 0) {
            Log.e(TAG, "removeUidInterfaceRules failed, errno = " + err);
            throw new ServiceSpecificException(err, "Unable to remove uid interface rules");
        }
    }

    /**
     * Swap active stats map
     */
    public static void swapActiveStatsMap() {
        native_swapActiveStatsMap();
    }

    /**
     * Set net permission for UIDs
     */
    public static void setNetPermForUids(final int permission, final int[] uids) {
        native_setPermissionForUids(permission, uids);
    }

    /**
     * Set counterset
     */
    public static int setCounterSet(final int counterSet, final int uid) {
        return native_setCounterSet(counterSet, uid);
    }

    /**
     * Delete tag data
     */
    public static int deleteTagData(final int tag, final int uid) {
        return native_deleteTagData(tag, uid);
    }

    private static native void native_init();
    private static native int native_addNaughtyApp(int uid) throws ServiceSpecificException;
    private static native int native_removeNaughtyApp(int uid) throws ServiceSpecificException;
    private static native int native_addNiceApp(int uid) throws ServiceSpecificException;
    private static native int native_removeNiceApp(int uid) throws ServiceSpecificException;
    private static native int native_setChildChain(int childChain, boolean enable)
            throws ServiceSpecificException;
    private static native int native_replaceUidChain(String name, boolean isAllowlist, int[] uids)
            throws ServiceSpecificException;
    private static native int native_setUidRule(int childChain, int uid, int firewallRule)
            throws ServiceSpecificException;
    private static native int native_addUidInterfaceRules(String ifName, int[] uids)
            throws ServiceSpecificException;
    private static native int native_removeUidInterfaceRules(int[] uids)
            throws ServiceSpecificException;
    private static native void native_swapActiveStatsMap();
    private static native void native_setPermissionForUids(int permission, int[] uids);
    private static native int native_setCounterSet(int counterSet, int uid);
    private static native int native_deleteTagData(int tag, int uid);
}
