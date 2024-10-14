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
import android.os.HandlerThread;
import android.util.Log;

import com.android.connectivity.resources.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.InterfaceParams;
import com.android.net.module.util.PacketReader;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
    private boolean mSatelliteNetworkConnected;
    private boolean mSatelliteBufferEventActive;
    private boolean mRegistered;
    private ConnectivityManager mConnectivityManager;
    private static final int INVALID_IFINDEX = 0;
    private static final int UNLIMITED_DATA_USAGE_LIMIT = 0; // Unlimited data usage
    // default satellite allowed data usage time measured interval
    private static final int DEFAULT_MEASURED_TIME_INTERVAL_SECS = 30;
    int mReadPipeFd = -1;
    private final Handler mHandler;
    private SatelliteBlockingUidDataListener mSatelliteBlockingUidDataListener;

    private int findNonZeroLength(byte[] buffer) {
        int length = 0;
        for (int i = 0; i < buffer.length; i += 4 /*size of integer*/) {
            int intValue = ByteBuffer.wrap(buffer, i, 4).order(ByteOrder.nativeOrder()).getInt();
            if (intValue != 0) {
                length++;
            }
        }
        return length;
    }

    private class SatelliteBlockingUidDataListener extends PacketReader {
        private final FileDescriptor mFd;

        SatelliteBlockingUidDataListener(@NonNull Handler h, @NonNull FileDescriptor fd) {
            super(h);
            mFd = fd;
        }

        @Override
        protected FileDescriptor createFd() {
            return mFd;
        }

        @Override
        protected int readPacket(@NonNull FileDescriptor fd, @NonNull byte[] buffer) {
            try {
                Arrays.fill(buffer, (byte) 0);
                InputStream input = new FileInputStream(fd);
                if (input.read(buffer) != -1) {
                    return findNonZeroLength(buffer);
                }
            } catch (IOException e) {
                Log.wtf(TAG, "Error reading from pipe: " + e.getMessage());
            }
            return 0;
        }

        @Override
        protected void handlePacket(byte[] recvbuf, int length) {
            int[] satelliteUidsBlockList = new int[0];
            if (recvbuf != null && length > 0) {
                final ByteBuffer buffer = ByteBuffer.wrap(recvbuf).order(ByteOrder.nativeOrder());

                satelliteUidsBlockList = new int[length];
                for (int i = 0; i < length; i++) {
                    satelliteUidsBlockList[i] = buffer.getInt();
                }
                Log.i(
                        TAG,
                        "satellite uid received:"
                                + length
                                + ":"
                                + Arrays.toString(satelliteUidsBlockList));
            }
        }
    }

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
                                    registerNativeSatelliteBufferEvent();
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
                    if (mSatelliteNetworkConnected) {
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

        /**
         * Start satellite buffer event monitoring
         */
        public void startSatelliteBufferEventMonitoring(int[] configs) {
            nativeStartSatelliteBufferEventMonitoring(configs);
        }

        /**
         * initialise satellite buffer event
         */
        public int initSatelliteBufferEvent() {
            return nativeInitSatelliteBufferEvent();
        }

        /**
         * stop satellite buffer event
         */
        public void stopSatelliteBufferEvent() {
            nativeStopSatelliteBufferEvent();
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
        HandlerThread ht = new HandlerThread("");
        ht.start();
        mHandler = new Handler(ht.getLooper());
    }

    private void monitorPipeForSatelliteUidTobeBlockedUpdates() {
        Log.i(TAG, "start monitoring");
        mSatelliteBlockingUidDataListener =
                new SatelliteBlockingUidDataListener(
                        mHandler, Dependencies.createFileDescriptor(mReadPipeFd));
        mHandler.post(() -> mSatelliteBlockingUidDataListener.start());
    }

    private void initialiseSatelliteBufferEvent() {
        if (mReadPipeFd == -1) {
            Log.i(TAG, "Initialise pipe");
            mReadPipeFd = mDeps.initSatelliteBufferEvent();
        }

        if (mReadPipeFd != -1) {
            monitorPipeForSatelliteUidTobeBlockedUpdates();
        }
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

    private int getInterfaceIndexOfActiveNetwork() {
        if (mConnectivityManager != null) {
            Network activeNetwork = mConnectivityManager.getActiveNetwork();
            if (activeNetwork != null) {
                LinkProperties linkProperties =
                        mConnectivityManager.getLinkProperties(activeNetwork);
                if (linkProperties != null) {
                    String interfaceName = linkProperties.getInterfaceName();
                    Log.i(TAG, "active interface name: " + interfaceName);
                    return mDeps.getInterfaceIndex(interfaceName);
                }
            }
        }
        return -1;
    }

    /**
     * Initialise pipe at native code and start FDEventsReader using read fd for monitoring blocking
     * updates of satellite uids. Start satellite events to ring buffer at native code with
     * different thread, monitor and update pipe using write fd the uids to be blocked, on crossing
     * data usage limits at native code with different thread.
     */
    private void registerNativeSatelliteBufferEvent() {
        if (!mSatelliteBufferEventActive) {
            int ifaceIndex = getInterfaceIndexOfActiveNetwork();
            int maxDataUsageLimitPerUid = mDeps.getSatelliteDataUsageLimitPerUid(mContext);

            if (ifaceIndex > 0 && maxDataUsageLimitPerUid > UNLIMITED_DATA_USAGE_LIMIT) {
                // build other satellite event configs to generate satellite buffer event to a ring
                // buffer
                int[] SatelliteEventConfig = mDeps.setupSatelliteEventConfig();
                // time interval for which max usage check measured for
                SatelliteEventConfig[0] =
                        mDeps.getSatelliteAllowedDataUsageTimeMeasuredInterval(mContext);
                // Update max satellite data usage limit per uid
                SatelliteEventConfig[1] = maxDataUsageLimitPerUid;
                // update ifindex4 index as per current network
                SatelliteEventConfig[4] = ifaceIndex;
                // ifindex6 set same as ifindex4 (Used at satellite event config to generate ring
                // buffer
                // at native code. To check the required reason.
                SatelliteEventConfig[3] = SatelliteEventConfig[4];
                Log.i(TAG, "interface index: " + SatelliteEventConfig[4]);

                initialiseSatelliteBufferEvent();
                if (mReadPipeFd != -1) {
                    mSatelliteBufferEventActive = true;
                    Thread nativeThread =
                            new Thread(
                                    () ->
                                            mDeps.startSatelliteBufferEventMonitoring(
                                                    SatelliteEventConfig));
                    nativeThread.start();
                } else {
                    Log.wtf(TAG, "pipe fd not created," + " don't start satellite buffer event");
                }
            }
        }
    }

    private void unregisterNativeSatelliteBufferEvent() {
        if (mSatelliteBufferEventActive) {
            Log.i(TAG, "stop satellite buffer event");
            mSatelliteBufferEventActive = false;
            mDeps.stopSatelliteBufferEvent();
        }
    }

    /** Initialise Satellite Buffer Event interface for read and write file descriptors */
    public static native int nativeInitSatelliteBufferEvent();

    /** Start Satellite Buffer Event */
    public static native void nativeStartSatelliteBufferEventMonitoring(int[] config);

    /** Stop Satellite Buffer Event */
    public static native void nativeStopSatelliteBufferEvent();
}
