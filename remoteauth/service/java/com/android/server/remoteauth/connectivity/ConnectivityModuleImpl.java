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

package com.google.android.server.remoteauth.connectivity;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

/**
 * Implementation of the connectivity module.
 * TODO(b/295240244): Use dagger to inject dependencies.
 */
public final class ConnectivityModuleImpl implements ConnectivityModule {

    private static final String TAG = "ConnectivityModuleImpl";

    private static ConnectivityModuleImpl sInstance = null;

    private final Context mContext;
    private final CdmConnectivityModule mCdmConnectivityModule;

    /** Static method to create single instance of this class **/
    public static synchronized ConnectivityModuleImpl getInstance(@NonNull Context context) {
        if (sInstance == null) {
            sInstance = new ConnectivityModuleImpl(context);
        }
        return sInstance;
    }

    @VisibleForTesting
    public ConnectivityModuleImpl(Context context, CdmConnectivityModule cdmConnectivityModule) {
        Preconditions.checkNotNull(cdmConnectivityModule);

        mContext = context;
        mCdmConnectivityModule = cdmConnectivityModule;
    }

    private ConnectivityModuleImpl(@NonNull Context context) {
        mContext = context;
        mCdmConnectivityModule = new CdmConnectivityModule(context);
    }

    @Override
    public void startDiscovery(
            @NonNull DiscoveryFilter discoveryFilter,
            @NonNull DiscoveredDeviceReceiver discoveredDeviceReceiver) {
        Log.i(TAG, "Starting discovery");
        if (mCdmConnectivityModule != null) {
            mCdmConnectivityModule.startDiscovery(discoveryFilter, discoveredDeviceReceiver);
        }
    }

    @Override
    public void stopDiscovery() {
        Log.i(TAG, "Stopping discovery");
        if (mCdmConnectivityModule != null) {
            mCdmConnectivityModule.stopDiscovery();
        }
    }

    @Override
    @Nullable
    public Connection connect(
            @NonNull ConnectionInfo connectionInfo, @NonNull EventListener eventListener) {
        Log.i(TAG, "Initiate a connection");
        if (mCdmConnectivityModule != null) {
            return mCdmConnectivityModule.connect(connectionInfo, eventListener);
        }
        return null;
    }

    @Override
    public void startListening(MessageReceiver messageReceiver) {
        Log.i(TAG, "Start listening for messages");
        if (mCdmConnectivityModule != null) {
            mCdmConnectivityModule.startListening(messageReceiver);
        }
    }

    @Override
    public void stopListening() {
        Log.i(TAG, "Stop listening for messages");
        if (mCdmConnectivityModule != null) {
            mCdmConnectivityModule.stopListening();
        }
    }
}
