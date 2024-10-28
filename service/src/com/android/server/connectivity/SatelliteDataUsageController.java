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

import com.android.connectivity.resources.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.InterfaceParams;

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
    private static final int INVALID_IFINDEX = 0;
    private static final int UNLIMITED_DATA_USAGE_LIMIT = 0; // Unlimited data usage
    // default satellite allowed data usage time measured interval
    private static final int DEFAULT_MEASURED_TIME_INTERVAL_SECS = 30;

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
    public static class Dependencies {
        /** Get the {@link ConnectivityResources} to use in SatelliteDataUsageController. */
        public ConnectivityResources getResources(@NonNull Context ctx) {
            return new ConnectivityResources(ctx);
        }

        /** Set up satellite event config to generate ring buffer at native code. */
        private int[] setupSatelliteEventConfig() {
            return new int[] {
                0, /*polling time, note: read from overlay config*/
                0, /*max_data_usage_allowed, note: read from overlay config*/
                500000000, /*time_threshold_ns*/
                0, /*ifindex6, note: set runtime*/
                0, /*ifindex4, note: set runtime*/
                51200, /*rx_threshold_bytes*/
                51200, /*tx_threshold_bytes*/
                10, /*rx_threshold_packets*/
                10, /*tx_threshold_packets*/
                40, /*rx6_overhead_bytes*/
                40, /*tx6_overhead_bytes*/
                20, /*rx4_overhead_bytes*/
                20, /*tx4_overhead_bytes*/
                0 /*padding*/
            };
        }

        /**
         * Get max satellite data usage limit per uid from overlay.(Can differ for each operator)
         */
        private int getSatelliteDataUsageLimitPerUid(@NonNull Context context) {
            if (getResources(context) != null) {
                return getResources(context)
                        .get()
                        .getInteger(R.integer.config_satelliteDataUsageLimitAllowedPerUidBytes);

            } else {
                Log.wtf(TAG, "failed to get max satellite data usage limit per uid");
            }
            return UNLIMITED_DATA_USAGE_LIMIT;
        }

        /** Get satellite allowed data usage time measured interval in seconds */
        private int getSatelliteAllowedDataUsageTimeMeasuredInterval(@NonNull Context context) {
            if (getResources(context) != null) {
                return getResources(context)
                        .get()
                        .getInteger(
                                R.integer.config_satelliteAllowedDataUsageTimeMeasuredIntervalSecs);

            } else {
                Log.wtf(TAG, "failed to get max satellite data usage limit per uid");
            }
            return DEFAULT_MEASURED_TIME_INTERVAL_SECS;
        }

        /** get the interface index */
        private int getInterfaceIndex(@NonNull String ifaceName) {
            final InterfaceParams params = InterfaceParams.getByName(ifaceName);
            return params != null ? params.index : INVALID_IFINDEX;
        }
    }

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
