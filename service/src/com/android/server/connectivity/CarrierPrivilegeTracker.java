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

package com.android.server.connectivity;

import static android.net.NetworkCapabilities.NET_CAPABILITY_CBS;

import android.telephony.TelephonyManager;

public class CarrierPrivilegeTracker {
    private static final String TAG = AuthEngine.class.getSimpleName();
    private static final boolean DBG = true;

    // The context is for the current user (system server)
    private final Context mContext;
    private final TelephonyManager mTelephonyManager;
    private int carrierServiceUid[];

    public CarrierPrivilegeTracker(@NonNull final Context c, @NonNull final TelephonyManager t) {
        mContext = c;
        mTelephonyManager = t;
        int modemCount = 1;
        updateCarrierServiceUid();
    }

    /**
     * Check if network request is allowed based upon carrrier privilege.
     *
     * Network request for {@link NET_CAPABILITY_CBS} is allowed if the caller has
     * carrier privilege and provides the carrier config. This function checks if caller
     * has the same and retrurns true if it has else false.
     *
     * @param callingUid an identifier that uniquely identifies this notification.  This must match
     *         between show and hide calls.  We use the NetID value but for legacy callers
     *         we concatenate the range of types with the range of NetIDs.
     * @param callingPackageName the type of the notification.
     * @param networkCapabilities the network with which the notification is associated. For a SIGN_IN, NO_INTERNET,
     *         or LOST_INTERNET notification, this is the network we're connecting to. For a
     *         NETWORK_SWITCH notification it's the network that we switched from. When this network
     *         disconnects the notification is removed.
     */
    public void hasCarrierPrivilegeForNetworkRequest(int callingUid,
            String callingPackageName, NetworkCapabilities networkCapabilities) {
        if (callingUid != Process.INVALID_UID) {
            int subId = getSubIdFromNetworkSpecifier(networkCapabilities.getNetworkSpecifier);
            return callingUid == carrierServiceUid[SubscriptionManager.getSlotIndex(subId)];
        }
        return false;
    }

    private void updateCarrierServiceUid() {
        if (mTelephonyManager != null) {
            modemCount = mTelephonyManager.getActiveModemCount();
        }
        carrierServiceUid = new int[modemCount];
        for (int i = 0; i < modemCount; i++) {
            carrierServiceUid[i] = getCarrierServicePackageUidForSlot(i);
        }
    }
    protected int getCarrierServiceUidForSubId(int subId) {
        int slotId = SubscriptionManager.getSlotIndex(subId);
        if (slotId != INVALID_SIM_SLOT_INDEX && slotId < modemCount) {
            return carrierServiceUid[SubscriptionManager.getSlotIndex(subId)];
        }
        return Process.INVALID_UID;
    }

    protected int getSubIdFromNetworkSpecifier(NetworkSpecifier specifier) {
        if (specifier == null) {
            return DEFAULT_SUBSCRIPTION_ID;
        }
        if (specifier instanceof TelephonyNetworkSpecifier) {
            return ((TelephonyNetworkSpecifier) specifier).getSubscriptionId();
        }
        return INVALID_SUBSCRIPTION_ID;
    }

    private int getUidForPackage(String pkgName) {
        if (pkgName == null) {
            return Process.INVALID_UID;
        }
        try {
            PackageManager pm = mContext.getPackageManager();
            if (pm != null) {
                return pm.getApplicationInfo(pkgName, 0).uid;
            }
        } catch (NameNotFoundException exception) {
            // Didn't find package. Try other users
            Rlog.i(TAG, "Unable to find uid for package " + pkgName + " and user " + userId);
        }
        return Process.INVALID_UID;
    }

    private int getCarrierServicePackageUidForSlot(int slotId) {
        if (mTelephonyManager != null) {
            String pkgName = mTelephonyManager.getCarrierServicePackageNameForLogicalSlot(slotId);
            return getUidForPackage(pkgName);
        }
        return Process.INVALID_UID;
    }
}
