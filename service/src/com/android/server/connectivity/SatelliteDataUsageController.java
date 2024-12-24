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
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.util.Log;

import com.android.connectivity.resources.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.InterfaceParams;

import java.util.ArrayList;
import java.util.Arrays;

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
    ArrayList<String> mCurrentSatelliteIfaces = new ArrayList<>();
    private static final int INVALID_IFINDEX = 0;
    private static final int UNLIMITED_DATA_USAGE_LIMIT = 0; // Unlimited data usage
    // default satellite allowed data usage time measured interval
    private static final int DEFAULT_MEASURED_TIME_INTERVAL_SECS = 30;
    private boolean mSatelliteBufferEventActive;
    ArrayList<String> mNewSatelliteIfaces = new ArrayList<>();

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

        /**
         * initialise satellite buffer event
         */
        public boolean initSatelliteBufferEvent() {
            return nativeInitSatelliteBufferEvent();
        }

        /**
         * Start satellite buffer event monitoring
         */
        public void startSatelliteBufferEventMonitoring(int[] ifaceIndexConfigs,
                int[] eventConfigs) {
            nativeStartSatelliteBufferEventMonitoring(ifaceIndexConfigs, eventConfigs);
        }

        /**
         * update satellite ifaces for satellite buffer event monitoring
         */
        public void UpdateSatelliteBufferEventMonitoringIfaces(int[] ifaceIndexConfig) {
            nativeUpdateSatelliteBufferEventMonitoringIfaces(ifaceIndexConfig);
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

    private void addIfacetoMonitoringList(@NonNull String iface) {
        if (mCurrentSatelliteIfaces != null && mCurrentSatelliteIfaces.contains(iface)) {
            return;
        } else {
            Log.i(TAG, "iface to track data usage:" + iface);
            mCurrentSatelliteIfaces.add(iface);
        }
    }

    private void removeIfaceFromMonitoringList(@NonNull String iface) {
        if (mCurrentSatelliteIfaces != null && mCurrentSatelliteIfaces.contains(iface)) {
            Log.i(TAG, "iface to untrack data usage:" + iface);
            mCurrentSatelliteIfaces.remove(iface);
        }
    }

    public synchronized void setMonitoringStatusForIface() {
        Log.i(TAG, "current satellite iface list: " + mCurrentSatelliteIfaces);
        if (!mSatelliteBufferEventActive) {
            mNewSatelliteIfaces = new ArrayList<>(mCurrentSatelliteIfaces);
            Log.i(TAG, "start monitoring for iface:" + mNewSatelliteIfaces);
            startSatelliteBufferEventMonitoring();
        } else {
            mNewSatelliteIfaces = new ArrayList<>(mCurrentSatelliteIfaces);
            Log.i(TAG, "update monitoring for iface:" + mNewSatelliteIfaces);
            UpdateSatelliteBufferEventMonitoring();
        }
    }

    public synchronized void createDataUsageTrackingForIface(@NonNull String iface,
            boolean isSatellite, @NonNull NetworkAgentInfo nai) {
        if (isSatellite && !iface.isEmpty()) {
            addIfacetoMonitoringList(iface);
        } else {
            Log.i(TAG, "modifyDataUsageTrackingStatus for non satellite network");

            if (nai.networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && nai.networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                Log.i(TAG, "wifi default network iface detected");
                mCurrentSatelliteIfaces = new ArrayList<>();
                setMonitoringStatusForIface();
            }
        }
    }

    // To handle scenarios at Mobile Data Off (Iface disconnected), Wi-fi on lingering(satellite
    // disconnected) and Wi-fi off adding satellite iface adding back scenario at unlinger scenario.
    public synchronized void updateTrackingStatusForIface(@NonNull String iface,
            boolean isRemoved) {
        Log.i(TAG, "updateTrackingStatusForIface: " + iface);
        if (!iface.isEmpty()) {
            if (isRemoved) { // remove iface from data usage tracking list
                removeIfaceFromMonitoringList(iface);
            } else { // add iface to data usage tracking list
                addIfacetoMonitoringList(iface);
            }
            setMonitoringStatusForIface();
        }
    }

    private void UpdateSatelliteBufferEventMonitoring() {
        if (mSatelliteBufferEventActive) {
            int[] satelliteIfaceIndexConfig = getSatelliteInterfaceIndexForIfaces();
            mDeps.UpdateSatelliteBufferEventMonitoringIfaces(satelliteIfaceIndexConfig);
        }
    }

    private int[] getSatelliteInterfaceIndexForIfaces() {
        int[] result = new int[mNewSatelliteIfaces.size()];
        for (int i = 0; i < mNewSatelliteIfaces.size(); i++) {
            String interfaceName = mNewSatelliteIfaces.get(i);
            result[i] = mDeps.getInterfaceIndex(interfaceName);
        }
        return result;
    }

    /**
     * Start satellite events to ring buffer at native code with different thread, monitor.
     */
    private void startSatelliteBufferEventMonitoring() {
        if (!mSatelliteBufferEventActive) {
            int[] satelliteIfaceIndexConfig = getSatelliteInterfaceIndexForIfaces();
            int maxDataUsageLimitPerUid = mDeps.getSatelliteDataUsageLimitPerUid(mContext);

            if (satelliteIfaceIndexConfig.length > 0
                    && maxDataUsageLimitPerUid > UNLIMITED_DATA_USAGE_LIMIT) {
                // build other satellite event configs to generate satellite buffer event to a ring
                // buffer
                int[] SatelliteEventConfig = mDeps.setupSatelliteEventConfig();
                // time interval for which max usage check measured for
                SatelliteEventConfig[0] =
                        mDeps.getSatelliteAllowedDataUsageTimeMeasuredInterval(mContext);
                // Update max satellite data usage limit per uid
                SatelliteEventConfig[1] = maxDataUsageLimitPerUid;
                Log.i(TAG, "interface index: " + Arrays.toString(satelliteIfaceIndexConfig));

                boolean status = mDeps.initSatelliteBufferEvent();
                if (status) {
                    mSatelliteBufferEventActive = true;
                    Thread nativeThread =
                            new Thread(
                                    () ->
                                            mDeps.startSatelliteBufferEventMonitoring(
                                                    satelliteIfaceIndexConfig,
                                                    SatelliteEventConfig));
                    nativeThread.start();
                } else {
                    Log.wtf(TAG, "failed to initialise satellite buffer event");
                }
            }
        }
    }

    /** Initialise Satellite Buffer Event */
    public static native boolean nativeInitSatelliteBufferEvent();

    /** Start Satellite Buffer Event */
    public static native void nativeStartSatelliteBufferEventMonitoring(int[] ifaceIndexConfig,
            int[] eventConfig);

    /** update satellite iface index for monitoring satellite buffer event to ring buffer */
    public static native void nativeUpdateSatelliteBufferEventMonitoringIfaces(
            int[] ifaceIndexConfig);
}
