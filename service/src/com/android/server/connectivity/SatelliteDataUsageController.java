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

import static android.net.ConnectivityManager.FIREWALL_CHAIN_OEM_DENY_3;

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

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.InterfaceParams;

import java.util.Arrays;

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
    private int[] mCurrentSatelliteUidBlockingArrayCache;
    private static final int INVALID_IFINDEX = 0;
    private final Handler handler;

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
        this(c, new Dependencies(c), connectivityServiceInternalHandler);
    }

    public static class Dependencies {
        private Dependencies(Context context) {

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
            }
            Log.i(TAG, "network callback successfully registered");
        }
    }

    private int[] setupSatelliteEventConfig() {
        return new int[] {
                1024000,     /*max_data_usage_allowed*/
                500000000, 	/*time_threshold_ns*/
                0 , 		/*ifindex6*/
                0, 			/*ifindex4*/
                51200,  	/*rx_threshold_bytes*/
                51200,		/*tx_threshold_bytes*/
                10, 		/*rx_threshold_packets*/
                10,			/*tx_threshold_packets*/
                40, 		/*rx6_overhead_bytes*/
                40,			/*tx6_overhead_bytes*/
                20,			/*rx4_overhead_bytes*/
                20,			/*tx4_overhead_bytes*/
                0			/*padding*/
        };
    }

    private void registerNativeSatelliteBufferEvent() {
        int ifaceIndex = getInterfaceIndexOfActiveNetwork();

        if (ifaceIndex == INVALID_IFINDEX) {
            return;
        } else {
            // build satellite event configs
            int[] SatelliteEventConfig = setupSatelliteEventConfig();

            // update ifindex4 index as per current active network
            SatelliteEventConfig[3] = ifaceIndex;

            // ifindex6 set same as ifindex4
            SatelliteEventConfig[2] = SatelliteEventConfig[3];
            Log.i(TAG, "interface index: " + SatelliteEventConfig[2]);
            isSatelliteBufferEventActive = true;
            new Thread(() -> {
                nativeStartSatelliteBufferEvent(this, SatelliteEventConfig);
            }).start();
        }
    }

    private void unregisterNativeSatelliteBufferEvent() {
        if (isSatelliteBufferEventActive) {
            Log.i(TAG, "stop satellite buffer event");
            isSatelliteBufferEventActive = false;
            if (mCurrentSatelliteUidBlockingArrayCache != null
                    && mCurrentSatelliteUidBlockingArrayCache.length > 0) {
                onSatelliteUidBlockingListReceived(new int[0]);
                nativeStopSatelliteBufferEvent();
            }
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

    // update firewall rule for satellie uids for blocking and unblocking network access
    // TODO: On Callback received by native code with uid list to block or on satellite network
    // disconnected, call this method with uid block array to update firewall rule
    private void updateFirewallRuleOfSatelliteUids(
            int[] updatedSatelliteUidBlockArrayCache, boolean needBlocking) {

        if (needBlocking) {
            Log.i(TAG, "enforce block rule for uids on satellite network : " + Arrays.toString(
                    updatedSatelliteUidBlockArrayCache));
            mCurrentSatelliteUidBlockingArrayCache
                    = Arrays.copyOf(updatedSatelliteUidBlockArrayCache,
                    updatedSatelliteUidBlockArrayCache.length);
            // TODO: Create new firewall chain FIREWALL_CHAIN_BLOCK_SATELLITE_NETWORK
            // Test code for enforceing firewall chain for uids
            if (!mConnectivityManager.getFirewallChainEnabled(FIREWALL_CHAIN_OEM_DENY_3)) {
                Log.i(TAG, "firewall chain not enabled");
                mConnectivityManager.setFirewallChainEnabled(FIREWALL_CHAIN_OEM_DENY_3, true);
            }
            Log.i(TAG, "is firewall chain enabled: "
                    + mConnectivityManager.getFirewallChainEnabled(FIREWALL_CHAIN_OEM_DENY_3));
            mConnectivityManager.replaceFirewallChain(FIREWALL_CHAIN_OEM_DENY_3,
                    updatedSatelliteUidBlockArrayCache);
        } else {
            Log.i(TAG, "enforce unblock rule for uids : " + Arrays.toString(
                    mCurrentSatelliteUidBlockingArrayCache));
            // TODO: Create new firewall chain FIREWALL_CHAIN_UNBLOCK_SATELLITE_NETWORK
            // mConnectivityManager.replaceFirewallChain(1, mCurrentSatelliteUidBlockingArrayCache);
            mCurrentSatelliteUidBlockingArrayCache = new int[0];
        }
    }

    /** Start Satellite Buffer Event */
    public static native void nativeStartSatelliteBufferEvent(SatelliteDataUsageController callback,
            int[] config);

    /** Stop Satellite Buffer Event */
    public static native void nativeStopSatelliteBufferEvent();
}
