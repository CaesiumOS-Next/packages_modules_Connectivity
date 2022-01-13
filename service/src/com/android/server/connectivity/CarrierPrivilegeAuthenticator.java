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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.TelephonyNetworkSpecifier;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.TelephonyManager.CarrierPrivilegesListener;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Tracks the uid of the carrier privileged app that provides the carrier config.
 * Authenticates if the caller has same uid as
 * carrier privileged app that provides the carrier config
 * @hide
 */
public class CarrierPrivilegeAuthenticator extends BroadcastReceiver {
    private static final String TAG = CarrierPrivilegeAuthenticator.class.getSimpleName();
    private static final boolean DBG = true;

    // The context is for the current user (system server)
    private final Context mContext;
    private final TelephonyManager mTelephonyManager;
    private int[] mCarrierServiceUid;
    private int mModemCount = 0;
    private final Object mLock = new Object();
    private final HandlerThread mThread;
    private final Handler mHandler;
    @NonNull
    private final List<CarrierPrivilegesListener> mCarrierPrivilegesChangedListeners =
            new ArrayList<>();

    public CarrierPrivilegeAuthenticator(@NonNull final Context c,
            @NonNull final TelephonyManager t) {
        mContext = c;
        mTelephonyManager = t;
        mModemCount = mTelephonyManager.getActiveModemCount();
        mThread = new HandlerThread(TAG);
        mThread.start();
        mHandler = new Handler(mThread.getLooper()) {};
        registerForCarrierChanges();
        updateCarrierServiceUid();
    }

    /**
     * An adapter {@link Executor} that posts all executed tasks onto the given
     * {@link Handler}.
     *
     * TODO : migrate to the version in frameworks/libs/net when it's ready
     *
     * @hide
     */
    public class HandlerExecutor implements Executor {
        private final Handler mHandler;
        public HandlerExecutor(@NonNull Handler handler) {
            mHandler = handler;
        }
        @Override
        public void execute(Runnable command) {
            if (!mHandler.post(command)) {
                throw new RejectedExecutionException(mHandler + " is shutting down");
            }
        }
    }

    /**
     * Broadcast receiver for ACTION_MULTI_SIM_CONFIG_CHANGED
     *
     * <p>The broadcast receiver is registered with mHandler
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        switch (intent.getAction()) {
            case TelephonyManager.ACTION_MULTI_SIM_CONFIG_CHANGED:
                handleActionMultiSimConfigChanged(context, intent);
                break;
            default:
                Log.d(TAG, "Unknown intent received with action: " + intent.getAction());
        }
    }

    private void handleActionMultiSimConfigChanged(Context context, Intent intent) {
        unregisterCarrierPrivilegesListeners();
        registerCarrierPrivilegesListeners();
        updateCarrierServiceUid();
    }

    private void registerForCarrierChanges() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(TelephonyManager.ACTION_MULTI_SIM_CONFIG_CHANGED);
        mContext.registerReceiver(this, filter, null, mHandler);
        registerCarrierPrivilegesListeners();
    }

    private void registerCarrierPrivilegesListeners() {
        final HandlerExecutor executor = new HandlerExecutor(mHandler);
        mModemCount = mTelephonyManager.getActiveModemCount();
        try {
            for (int i = 0; i < mModemCount; i++) {
                CarrierPrivilegesListener carrierPrivilegesListener =
                        new CarrierPrivilegesListener() {
                            @Override
                            public void onCarrierPrivilegesChanged(
                                    @NonNull List<String> privilegedPackageNames,
                                    @NonNull int[] privilegedUids) {
                                // Re-trigger the synchronous check (which is also very cheap due
                                // to caching in CarrierPrivilegesTracker). This allows consistency
                                // with the onSubscriptionsChangedListener and broadcasts.
                                updateCarrierServiceUid();
                            }
                        };

                mTelephonyManager.addCarrierPrivilegesListener(
                        i, executor, carrierPrivilegesListener);
                mCarrierPrivilegesChangedListeners.add(carrierPrivilegesListener);
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Encountered exception registering carrier privileges listeners", e);
        }
    }

    private void unregisterCarrierPrivilegesListeners() {
        for (CarrierPrivilegesListener carrierPrivilegesListener :
                mCarrierPrivilegesChangedListeners) {
            mTelephonyManager.removeCarrierPrivilegesListener(carrierPrivilegesListener);
        }
        mCarrierPrivilegesChangedListeners.clear();
    }

    /**
     * Check if network request is allowed based upon carrrier privilege.
     *
     * Network request for {@link NET_CAPABILITY_CBS} is allowed if the caller has
     * carrier privilege and provides the carrier config. This function checks if caller
     * has the same and retrurns true if it has else false.
     *
     * @param callingUid user identifier that uniquely identifies the caller.
     * @param networkRequest the network request for which the carrier privilege is checked.
     * @return true if caller has arrier privilege and provides the carrier config else false.
     */
    public boolean hasCarrierPrivilegeForNetworkRequest(int callingUid,
            NetworkRequest networkRequest) {
        if (callingUid != Process.INVALID_UID) {
            final int subId = getSubIdFromNetworkSpecifier(
                    networkRequest.getNetworkSpecifier());
            return callingUid == getCarrierServiceUidForSubId(subId);
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
    int getCarrierServiceUidForSubId(int subId) {
        final int slotId = getSlotIndex(subId);
        if (slotId != SubscriptionManager.INVALID_SIM_SLOT_INDEX && slotId < mModemCount) {
            synchronized (mLock) {
                return mCarrierServiceUid[slotId];
            }
        }
        return Process.INVALID_UID;
    }

    @VisibleForTesting
    protected int getSlotIndex(int subId) {
        return SubscriptionManager.getSlotIndex(subId);
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
