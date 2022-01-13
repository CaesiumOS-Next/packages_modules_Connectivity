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

import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.NetworkCapabilities;
import android.net.NetworkSpecifier;
import android.net.TelephonyNetworkSpecifier;
import android.os.Process;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.TelephonyManager.CarrierPrivilegesListener;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Tracks the uid of the carrier privileged app that provides the carrier config.
 * Authenticates if the caller has same uid as
 * carrier privileged app that provides the carrier config
 */
public class CarrierPrivilegeAuthenticator {
    private static final String TAG = CarrierPrivilegeAuthenticator.class.getSimpleName();
    private static final boolean DBG = true;

    // The context is for the current user (system server)
    private final Context mContext;
    private final TelephonyManager mTelephonyManager;
    private int[] mCarrierServiceUid;
    private int mModemCount = 0;
    private Object mLock;

    public CarrierPrivilegeAuthenticator(@NonNull final Context c,
            @NonNull final TelephonyManager t) {
        mContext = c;
        mTelephonyManager = t;
        mModemCount = mTelephonyManager.getActiveModemCount();
        updateCarrierServiceUid();
    }

    /**
     * Check if network request is allowed based upon carrrier privilege.
     *
     * Network request for {@link NET_CAPABILITY_CBS} is allowed if the caller has
     * carrier privilege and provides the carrier config. This function checks if caller
     * has the same and retrurns true if it has else false.
     *
     * @param callingUid user identifier that uniquely identifies the caller.
     * @param callingPackageName calling app's package name
     * @param networkCapabilities the network capabilities for the network request.
     * @return true if caller has arrier privilege and provides the carrier config else false.
     */
    public boolean hasCarrierPrivilegeForNetworkRequest(int callingUid,
            String callingPackageName, NetworkCapabilities networkCapabilities) {
        if (callingUid != Process.INVALID_UID) {
            final int subId = getSubIdFromNetworkSpecifier(
                    networkCapabilities.getNetworkSpecifier());
            final int slot = SubscriptionManager.getSlotIndex(subId);
            if (SubscriptionManager.INVALID_SIM_SLOT_INDEX == slot || slot >= mModemCount) {
                return false;
            }
            return callingUid == mCarrierServiceUid[slot];
        }
        return false;
    }

    @VisibleForTesting
    void updateCarrierServiceUid() {
        synchronized (mLock) {
            mCarrierServiceUid = new int[mModemCount];
            for (int i = 0; i < mModemCount; i++) {
                mCarrierServiceUid[i] = getCarrierServicePackageUidForSlot(i);
            }
        }
    }

    @VisibleForTesting
    void registerForCarrierPrivilegeChange() {
        final Executor callbackExecutor = Executors.newSingleThreadExecutor();
        CarrierPrivilegesListener carrierPrivilegesListener =
                new CarrierPrivilegesListener() {
                    @Override
                    public void onCarrierPrivilegesChanged(
                            List<String> privilegedPackageNames, int[] privilegedUids) {
                        updateCarrierServiceUid();
                    }
                };
        try {
            for (int i = 0; i < mModemCount; i++) {
                mTelephonyManager.addCarrierPrivilegesListener(
                        i, callbackExecutor, carrierPrivilegesListener);
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Encounted exception registering carrier privileges listeners", e);
        }
    }

    @VisibleForTesting
    int getCarrierServiceUidForSubId(int subId) {
        final int slotId = SubscriptionManager.getSlotIndex(subId);
        if (slotId != SubscriptionManager.INVALID_SIM_SLOT_INDEX && slotId < mModemCount) {
            return mCarrierServiceUid[SubscriptionManager.getSlotIndex(subId)];
        }
        return Process.INVALID_UID;
    }

    @VisibleForTesting
    int getSubIdFromNetworkSpecifier(NetworkSpecifier specifier) {
        if (specifier instanceof TelephonyNetworkSpecifier) {
            return ((TelephonyNetworkSpecifier) specifier).getSubscriptionId();
        }
        return SubscriptionManager.DEFAULT_SUBSCRIPTION_ID;
    }

    @VisibleForTesting
    int getUidForPackage(String pkgName) {
        if (pkgName == null) {
            return Process.INVALID_UID;
        }
        try {
            PackageManager pm = mContext.getPackageManager();
            if (pm != null) {
                return pm.getApplicationInfo(pkgName, 0).uid;
            }
        } catch (PackageManager.NameNotFoundException exception) {
            // Didn't find package. Try other users
            Log.i(TAG, "Unable to find uid for package " + pkgName);
        }
        return Process.INVALID_UID;
    }

    @VisibleForTesting
    int getCarrierServicePackageUidForSlot(int slotId) {
        return getUidForPackage(mTelephonyManager.getCarrierServicePackageNameForLogicalSlot(
                slotId));
    }
}
