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
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.InterfaceParams;

import java.io.FileDescriptor;

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
    private boolean isSatelliteNetworkConnected;
    private boolean isSatelliteBufferEventActive;
    private boolean isRegistered;
    private ConnectivityManager mConnectivityManager;
    private static final int INVALID_IFINDEX = 0;
    int mReadPipeFd = -1;

    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    Log.i(TAG, "On Available: " + network);
                    if (network != null) {
                        if (mConnectivityManager != null) {
                            NetworkCapabilities capabilities =
                                    mConnectivityManager.getNetworkCapabilities(network);
                            if (capabilities != null) {
                                isSatelliteNetworkConnected =
                                        capabilities.hasTransport(
                                                NetworkCapabilities.TRANSPORT_SATELLITE);
                                /*  && !capabilities.hasCapability(NetworkCapabilities
                                .NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED);*/
                                boolean isWifiConnected =
                                        capabilities.hasTransport(
                                                NetworkCapabilities.TRANSPORT_WIFI);
                                if (isSatelliteNetworkConnected && !isWifiConnected) {
                                    Log.i(TAG, "satellite network connected");
                                    if (!isSatelliteBufferEventActive) {
                                        registerNativeSatelliteBufferEvent();
                                    }
                                } else {
                                    unregisterNativeSatelliteBufferEvent();
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
                    if (isSatelliteNetworkConnected) {
                        unregisterNativeSatelliteBufferEvent();
                    }
                }
            };

    public SatelliteDataUsageController(
            @NonNull final Context c, @NonNull final Handler connectivityServiceInternalHandler) {
        this(c, new Dependencies(), connectivityServiceInternalHandler);
    }

    /** Dependencies of SatelliteDataUsageController, */
    @VisibleForTesting
    public static class Dependencies {
        /**
         * Set up satellite event config to generate ring buffer at native code, setup max data
         * usage limit allowed for uid and polling interval to monitor the ring buffer at native
         * code.
         */
        private int[] setupSatelliteEventConfig(@NonNull Context context) {
            return new int[] {
                30, /*polling_count mapped to secs*/
                1024000, /*max_data_usage_allowed*/
                500000000, /*time_threshold_ns*/
                0, /*ifindex6*/
                0, /*ifindex4*/
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

        /** sets the int descriptor */
        private static FileDescriptor createFileDescriptor(int fd) {
            FileDescriptor fileDescriptor = new FileDescriptor();
            fileDescriptor.setInt$(fd);
            return fileDescriptor;
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

    private void initialiseSatelliteBufferEvent() {
        if (mReadPipeFd == -1) {
            Log.i(TAG, "Initialise pipe");
            mReadPipeFd = nativeInitSatelliteBufferEvent();
        }
    }

    public synchronized void registerForSatelliteNetwork() {
        if (!isRegistered) {
            isRegistered = true;
            mConnectivityManager =
                    (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkRequest.Builder builder = new NetworkRequest.Builder();
            builder.addTransportType(NetworkCapabilities.TRANSPORT_SATELLITE);
            builder.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
            NetworkRequest request = builder.build();
            if (mConnectivityManager != null) {
                mConnectivityManager.registerNetworkCallback(request, networkCallback);
            } else {
                Log.wtf(TAG, "network callback not registered");
            }
        }
    }

    private void registerNativeSatelliteBufferEvent() {
        int ifaceIndex = getInterfaceIndexOfActiveNetwork();

        if (ifaceIndex > 0) {
            // build satellite event configs
            int[] SatelliteEventConfig = mDeps.setupSatelliteEventConfig(mContext);
            // update ifindex4 index as per current network
            SatelliteEventConfig[4] = ifaceIndex;
            // ifindex6 set same as ifindex4 (Used at satellite event config to generate ring buffer
            // at native code. To check
            SatelliteEventConfig[3] = SatelliteEventConfig[4];
            Log.i(TAG, "interface index: " + SatelliteEventConfig[4]);
            isSatelliteBufferEventActive = true;

            // Initialise pipe at native code and start FDEventsReader using read fd for monitoring
            // blocking updates on satellite uids
            initialiseSatelliteBufferEvent();

            Thread nativeThread =
                    new Thread(
                            () -> {
                                if (mReadPipeFd != -1) {
                                    // start satellite events to ring buffer, monitor and write to
                                    // pipe using write
                                    // fd the uids to be blocked, on crossing data usage limits at
                                    // native code
                                    // with different thread.
                                    nativeStartSatelliteBufferEvent(SatelliteEventConfig);

                                } else {
                                    Log.wtf(TAG, "pipe fd not created, dont start satellite event");
                                }
                            });
            nativeThread.start();
        }
    }

    private void unregisterNativeSatelliteBufferEvent() {
        if (isSatelliteBufferEventActive) {
            Log.i(TAG, "stop satellite buffer event");
            isSatelliteBufferEventActive = false;
            nativeStopSatelliteBufferEvent();
        }
    }

    private int getInterfaceIndexOfActiveNetwork() {
        if (mConnectivityManager != null) {
            Network activeNetwork = mConnectivityManager.getActiveNetwork();
            if (activeNetwork != null) {
                NetworkCapabilities networkCapabilities =
                        mConnectivityManager.getNetworkCapabilities(activeNetwork);
                if (networkCapabilities != null) {
                    LinkProperties linkProperties =
                            mConnectivityManager.getLinkProperties(activeNetwork);
                    if (linkProperties != null) {
                        String interfaceName = linkProperties.getInterfaceName();
                        Log.i(TAG, "active interface name: " + interfaceName);
                        final InterfaceParams params = InterfaceParams.getByName(interfaceName);
                        return params != null ? params.index : INVALID_IFINDEX;
                    }
                }
            }
        }
        return -1;
    }

    /** Initialise Satellite Buffer Event interface for read and write file descriptors */
    public static native int nativeInitSatelliteBufferEvent();

    /** Start Satellite Buffer Event */
    public static native void nativeStartSatelliteBufferEvent(int[] config);

    /** Stop Satellite Buffer Event */
    public static native void nativeStopSatelliteBufferEvent();
}
