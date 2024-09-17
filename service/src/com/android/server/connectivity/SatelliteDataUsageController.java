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

import static android.net.ConnectivityManager.FIREWALL_CHAIN_DENY_SATELLITE_NETWORK;

import android.annotation.NonNull;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.android.connectivity.resources.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.InterfaceParams;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Monitors the Satellite network data usage for each uid based on the satellite buffer event
 * gnerated at a ring buffer at native interface and enforces blocking (firewall rules) to the
 * uids exceeding the threshold limit (received as callback information from native interface.
 *
 * @hide
 */
public class SatelliteDataUsageController {
    private static final String TAG = SatelliteDataUsageController.class.getSimpleName();
    private final Context mContext;
    private final Dependencies mDeps;
    private final Handler mConnectivityServiceHandler;
    private boolean isCellularConnected;
    private boolean isWifiConnected;
    private boolean isSatelliteBufferEventActive;
    private boolean isRegistered;
    private ConnectivityManager mConnectivityManager;
    // Later if redundant will be removed
    private int[] mCurrentSatelliteUidBlockingArrayCache;
    private static final int INVALID_IFINDEX = 0;
    private final Handler handler;
    final Runnable r = this::checkPipeForSatelliteUidsBlockingUpdates;
    int mReadPipeFd = -1;
    // To monitor pipe for any satellite uids to be blocked updates
    private static final Long EVALUATE_READ_PIPE_DELAY_MILLIS = TimeUnit.SECONDS.toMillis(2);

    public void onSatelliteUidBlockingListReceived(int[] satelliteUidsBlockList) {
        Log.i(TAG, "satellite uid list received for firewall enforcement " + Arrays.toString(
                satelliteUidsBlockList));

        final Runnable r = () -> updateFirewallRuleOfSatelliteUids(
                satelliteUidsBlockList, satelliteUidsBlockList.length > 0);

        mConnectivityServiceHandler.post(r);
    }

    private final ConnectivityManager.NetworkCallback networkCallback
            = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            Log.i(TAG, "On Available: " + network);
            if (network != null) {
                if (mConnectivityManager != null) {
                    NetworkCapabilities capabilities = mConnectivityManager.getNetworkCapabilities(
                            network);
                    if (capabilities != null) {
                        isCellularConnected = capabilities.hasTransport(
                                NetworkCapabilities.TRANSPORT_CELLULAR);
                        isWifiConnected = capabilities.hasTransport(
                                NetworkCapabilities.TRANSPORT_WIFI);
                        if (isCellularConnected && !isWifiConnected) {
                            Log.i(TAG, "Cellular network connected");
                            if (!isSatelliteBufferEventActive) {
                                registerNativeSatelliteBufferEvent();
                            }
                        } else if (isCellularConnected || isWifiConnected) {
                            isCellularConnected = false;
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
            if (isCellularConnected) {
                isCellularConnected = false;
                unregisterNativeSatelliteBufferEvent();
            }
        }
    };

    public SatelliteDataUsageController(@NonNull final Context c,
            @NonNull final Handler connectivityServiceInternalHandler) {
        this(c, new Dependencies(), connectivityServiceInternalHandler);
    }

    /**
     * Dependencies of SatelliteDataUsageController,
     */
    @VisibleForTesting
    public static class Dependencies {
        /**
         * Get the {@link ConnectivityResources} to use in SatelliteDataUsageController.
         */
        public ConnectivityResources getResources(@NonNull Context ctx) {
            return new ConnectivityResources(ctx);
        }
    }

    @VisibleForTesting
    SatelliteDataUsageController(@NonNull final Context c, @NonNull final Dependencies deps,
            @NonNull final Handler connectivityServiceInternalHandler) {
        mContext = c;
        mDeps = deps;
        mConnectivityServiceHandler = connectivityServiceInternalHandler;
        HandlerThread ht = new HandlerThread("");
        ht.start();
        handler = new Handler(ht.getLooper());
    }

    public synchronized void registerForSatelliteNetwork() {
        if (!isRegistered) {
            isRegistered = true;
            mConnectivityManager
                    = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkRequest.Builder builder = new NetworkRequest.Builder();
            builder.addTransportType(
                    NetworkCapabilities.TRANSPORT_CELLULAR); // For testing purpose only
            builder.addTransportType(
                    NetworkCapabilities.TRANSPORT_WIFI); // For testing purpose only
            NetworkRequest request = builder.build();
            if (mConnectivityManager != null) {
                mConnectivityManager.registerNetworkCallback(request, networkCallback);
            } else {
                Log.wtf(TAG, "network callback not registered");
            }
        }
    }

    private int[] setupSatelliteEventConfig() {
        if ( mDeps.getResources(mContext) != null) {
            int[] satelliteEventConfig =
                    mDeps.getResources(mContext).get().getIntArray(R.array.config_satellite_event);

            Log.i(TAG, "satellite event configs set" + Arrays.toString(satelliteEventConfig));

            if (satelliteEventConfig != null) {
                return satelliteEventConfig;
            }
        } else {
            Log.wtf(TAG, "failed to get satellite event config");
        }

        return new int[]{
                30,          /*polling_count mapped to secs*/
                1024000,     /*max_data_usage_allowed*/
                500000000,   /*time_threshold_ns*/
                0,          /*ifindex6*/
                0,           /*ifindex4*/
                51200,       /*rx_threshold_bytes*/
                51200,       /*tx_threshold_bytes*/
                10,          /*rx_threshold_packets*/
                10,          /*tx_threshold_packets*/
                40,          /*rx6_overhead_bytes*/
                40,          /*tx6_overhead_bytes*/
                20,          /*rx4_overhead_bytes*/
                20,          /*tx4_overhead_bytes*/
                0            /*padding*/
        };
    }

    private void registerNativeSatelliteBufferEvent() {
        int ifaceIndex = getInterfaceIndexOfActiveNetwork();

        if (ifaceIndex > 0) {
            // build satellite event configs
            int[] SatelliteEventConfig = setupSatelliteEventConfig();

            // update ifindex4 index as per current active network
            SatelliteEventConfig[4] = ifaceIndex;

            // ifindex6 set same as ifindex4
            SatelliteEventConfig[3] = SatelliteEventConfig[4];
            Log.i(TAG, "interface index: " + SatelliteEventConfig[4]);
            isSatelliteBufferEventActive = true;

            // start satellite events to ring buffer , monitor and write to
            // file description the uids to be blocked on crossing data usage limits at native code
            // with different thread.
            Thread nativeThread = new Thread(() -> {
                // Create write file descriptor for updating satellite uids to be
                // blocked at native code and read file descriptor to read those uids for enforcing
                // firewall rule chain at java side.
                if(mReadPipeFd == -1) {
                    mReadPipeFd = nativeInitSatelliteBufferEvent();
                }
                if (mReadPipeFd == -1) {
                    Log.wtf(TAG, "pipe fd not created failed to start satellite event");
                } else {
                    Log.i(TAG, "start satellite event");
                    nativeStartSatelliteBufferEvent(SatelliteEventConfig);
                }
            });
            nativeThread.start();

            // Start monitoring pipe for satellite uids to be blocked updates with read fd
            // Note: Satellite to be blocked uid list is updated to pipe by native code by
            // write descriptor created at
            // {@link: SatelliteDataUsageController#nativeInitSatelliteBufferEvent}
            new Thread(() -> {
                handler.postDelayed(r, EVALUATE_READ_PIPE_DELAY_MILLIS);
            }).start();
        }
    }

    private void checkPipeForSatelliteUidsBlockingUpdates() {
        if (isSatelliteBufferEventActive) {
            int[] satelliteUidsBlockList = new int[0];
            InputStream input = new FileInputStream(createFileDescriptor(mReadPipeFd));
            try {
                if (input.available() != 0) {
                    Log.i(TAG, "Start reading from pipe");
                    // Read the number of elements
                    byte[] sizeBytes = new byte[4];
                    int satelliteUidBlockingCount;
                    if(input.read(sizeBytes) != -1) {
                        satelliteUidBlockingCount = ByteBuffer
                                .wrap(sizeBytes).order(ByteOrder.nativeOrder()).getInt();

                        // Read the integer array
                        byte[] satelliteUidBlockingUidDataBytes
                                = new byte[satelliteUidBlockingCount * 4];
                        if(input.read(satelliteUidBlockingUidDataBytes) != -1) {
                            satelliteUidsBlockList = new int[satelliteUidBlockingCount];
                            ByteBuffer buffer = ByteBuffer
                                    .wrap(satelliteUidBlockingUidDataBytes)
                                    .order(ByteOrder.nativeOrder());
                            for (int i = 0; i < satelliteUidBlockingCount; i++) {
                                satelliteUidsBlockList[i] = buffer.getInt();
                            }
                            Log.i(TAG, "satellite uid received:" + satelliteUidBlockingCount
                                    + ":" + Arrays.toString(satelliteUidsBlockList));
                        }
                    }
                }
            } catch (IOException e) {
                Log.wtf(TAG,"Error reading from pipe: " + e.getMessage());
            } finally {
                try {
                    input.close();
                } catch (IOException e) {
                    Log.wtf(TAG,"Error closing input stream: " + e.getMessage());
                }
            }
            handler.postDelayed(r, EVALUATE_READ_PIPE_DELAY_MILLIS);

            // Enforce firewall rule for the current satellite blocking uid list if received.
            if (satelliteUidsBlockList.length > 0) {
                onSatelliteUidBlockingListReceived(satelliteUidsBlockList);
            }
        }
    }

    private static FileDescriptor createFileDescriptor(int fd) {
        FileDescriptor fileDescriptor = new FileDescriptor();
        fileDescriptor.setInt$(fd);
        return fileDescriptor;
    }

    private void unregisterNativeSatelliteBufferEvent() {
        if (isSatelliteBufferEventActive) {
            Log.i(TAG, "stop satellite buffer event");
            if (mCurrentSatelliteUidBlockingArrayCache != null
                    && mCurrentSatelliteUidBlockingArrayCache.length > 0) {
                onSatelliteUidBlockingListReceived(new int[0]);
            }
            nativeStopSatelliteBufferEvent();
            isSatelliteBufferEventActive = false;
            handler.removeCallbacks(r);
        }
    }

    private int getInterfaceIndexOfActiveNetwork() {
        if (mConnectivityManager != null) {
            Network activeNetwork = mConnectivityManager.getActiveNetwork();
            if (activeNetwork != null) {
                NetworkCapabilities networkCapabilities =
                        mConnectivityManager.getNetworkCapabilities(activeNetwork);
                if (networkCapabilities != null) {
                    LinkProperties linkProperties = mConnectivityManager.getLinkProperties(
                            activeNetwork);
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

    // update firewall rule for satellie uids for blocking and unblocking network access.
    // On Callback received from native code with uid list to block or on satellite network
    // disconnected, call this method with uids to update firewall rule
    private void updateFirewallRuleOfSatelliteUids(
            int[] updatedSatelliteUidBlockArrayCache, boolean needBlocking) {

        if (needBlocking) {
            Log.i(TAG, "enforce block rule for uids on satellite network : " + Arrays.toString(
                    updatedSatelliteUidBlockArrayCache));
            mCurrentSatelliteUidBlockingArrayCache
                    = Arrays.copyOf(updatedSatelliteUidBlockArrayCache,
                    updatedSatelliteUidBlockArrayCache.length);
            if (!mConnectivityManager.getFirewallChainEnabled(
                    FIREWALL_CHAIN_DENY_SATELLITE_NETWORK)) {
                Log.i(TAG, "firewall chain not enabled");
                mConnectivityManager.setFirewallChainEnabled(
                        FIREWALL_CHAIN_DENY_SATELLITE_NETWORK, true);
            }
            mConnectivityManager.replaceFirewallChain(FIREWALL_CHAIN_DENY_SATELLITE_NETWORK,
                    updatedSatelliteUidBlockArrayCache);
        } else {
            Log.i(TAG, "enforce unblock rule for uids : " + Arrays.toString(
                    mCurrentSatelliteUidBlockingArrayCache));
            mConnectivityManager.replaceFirewallChain(FIREWALL_CHAIN_DENY_SATELLITE_NETWORK,
                    updatedSatelliteUidBlockArrayCache);
            mCurrentSatelliteUidBlockingArrayCache = new int[0];
        }
    }

    /** Initialise Satellite Buffer Event interface for read and write file descriptors */
    public static native int nativeInitSatelliteBufferEvent();

    /** Start Satellite Buffer Event */
    public static native void nativeStartSatelliteBufferEvent(int[] config);

    /** Stop Satellite Buffer Event */
    public static native void nativeStopSatelliteBufferEvent();
}