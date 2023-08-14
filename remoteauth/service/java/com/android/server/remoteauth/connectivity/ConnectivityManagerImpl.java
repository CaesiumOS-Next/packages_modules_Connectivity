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

package com.android.server.remoteauth.connectivity;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Implementation of the connectivity manager.
 *
 * <p>This is a singleton that exposes API's for discovery, connection, sending messages to the peer
 * device and receiving messagr from the peer device.
 */
// TODO(b/295240244): Use DI to inject dependencies.
public final class ConnectivityManagerImpl implements ConnectivityManager {
    private static final String TAG = "ConnectivityManagerImpl";

    private static ConnectivityManagerImpl sInstance = null;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private final Context mContext;
    private final CdmConnectivityManager mCdmConnectivityManager;

    private CompanionDeviceManagerWrapper mCompanionDeviceManagerWrapper;

    /** Static method to create single instance of this class * */
    public static synchronized ConnectivityManagerImpl getInstance(@NonNull Context context) {
        if (sInstance == null) {
            sInstance = new ConnectivityManagerImpl(context);
        }
        return sInstance;
    }

    @VisibleForTesting
    public ConnectivityManagerImpl(
            @NonNull Context context, @NonNull CdmConnectivityManager cdmConnectivityManager) {
        mContext = context;
        mCdmConnectivityManager = cdmConnectivityManager;
    }

    private ConnectivityManagerImpl(@NonNull Context context) {
        mContext = context;
        mCompanionDeviceManagerWrapper = new CompanionDeviceManagerWrapper(mContext);
        mCdmConnectivityManager =
                new CdmConnectivityManager(EXECUTOR, mCompanionDeviceManagerWrapper);
    }

    @Override
    public void startDiscovery(
            @NonNull DiscoveryFilter discoveryFilter,
            @NonNull DiscoveredDeviceReceiver discoveredDeviceReceiver) {
        Log.i(TAG, "Starting discovery");
        if (mCdmConnectivityManager != null) {
            mCdmConnectivityManager.startDiscovery(discoveryFilter, discoveredDeviceReceiver);
        }
    }

    @Override
    public void stopDiscovery(
            @NonNull DiscoveryFilter discoveryFilter,
            @NonNull DiscoveredDeviceReceiver discoveredDeviceReceiver) {
        Log.i(TAG, "Stopping discovery");
        if (mCdmConnectivityManager != null) {
            mCdmConnectivityManager.stopDiscovery(discoveryFilter, discoveredDeviceReceiver);
        }
    }

    @Override
    @Nullable
    public Connection connect(
            @NonNull ConnectionInfo connectionInfo, @NonNull EventListener eventListener) {
        Log.i(TAG, "Initiating a connection");
        // Not implemented.
        return null;
    }

    @Override
    public void startListening(MessageReceiver messageReceiver) {
        Log.i(TAG, "Start listening for messages");
        // Not implemented.
    }

    @Override
    public void stopListening(MessageReceiver messageReceiver) {
        Log.i(TAG, "Stop listening for messages");
        // Not implemented.
    }
}
