/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.annotation.NonNull;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Monitors the Satellite network data usage for each uid based on the satellite buffer event
 * gnerated at a ring buffer at native interface and enforces blocking (firewall rules) to the uids
 * exceeding the threshold limit (received as callback information from native interface.
 *
 * @hide
 */
public class SatelliteDataUsageController {
    private static final String TAG = SatelliteDataUsageController.class.getSimpleName();
    private final Context mContext;
    private final Dependencies mDeps;
    private final Handler mConnectivityServiceHandler;
    private boolean mSatelliteNetworkConnected;
    private boolean mRegistered;
    private ConnectivityManager mConnectivityManager;

    private final ConnectivityManager.NetworkCallback mNetworkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    Log.i(TAG, "On Available: " + network);
                    if (network != null) {
                        if (mConnectivityManager != null) {
                            NetworkCapabilities capabilities =
                                    mConnectivityManager.getNetworkCapabilities(network);
                            if (capabilities != null) {
                                mSatelliteNetworkConnected =
                                        capabilities.hasTransport(
                                                NetworkCapabilities.TRANSPORT_SATELLITE);
                                if (mSatelliteNetworkConnected) {
                                    Log.i(TAG, "satellite network connected");
                                } else {
                                }
                            }
                        }
                    } else {
                        Log.wtf(TAG, "network is null");
                    }
                }

                @Override
                public void onLost(Network network) {
                    Log.i(TAG, "Network Lost");
                }
            };

    public SatelliteDataUsageController(
            @NonNull final Context c, @NonNull final Handler connectivityServiceInternalHandler) {
        this(c, new Dependencies(), connectivityServiceInternalHandler);
    }

    /** Dependencies of SatelliteDataUsageController, */
    @VisibleForTesting
    public static class Dependencies {}

    @VisibleForTesting
    SatelliteDataUsageController(
            @NonNull final Context c,
            @NonNull final Dependencies deps,
            @NonNull final Handler connectivityServiceInternalHandler) {
        mContext = c;
        mDeps = deps;
        mConnectivityServiceHandler = connectivityServiceInternalHandler;
    }

    public synchronized void registerForSatelliteNetwork() {
        if (!mRegistered) {
            mRegistered = true;
            HandlerThread handlerThread = new HandlerThread("SatelliteDataUsageThread");
            handlerThread.start();
            Handler handler = new Handler(handlerThread.getLooper());

            NetworkRequest.Builder builder = new NetworkRequest.Builder();
            builder.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            builder.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED);
            mConnectivityManager =
                    (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (mConnectivityManager != null) {
                mConnectivityManager.registerBestMatchingNetworkCallback(
                        builder.build(), mNetworkCallback, handler);
            } else {
                Log.wtf(TAG, "network callback not registered");
            }
        }
    }
}
