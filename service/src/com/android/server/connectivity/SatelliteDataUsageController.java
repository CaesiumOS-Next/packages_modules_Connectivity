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
import com.android.net.module.util.PacketReader;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

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
    private boolean isSatelliteNetworkConnected;
    private boolean isSatelliteBufferEventActive;
    private boolean isRegistered;
    private ConnectivityManager mConnectivityManager;
    private int[] mCurrentSatelliteUidBlockingArrayCache;
    private static final int INVALID_IFINDEX = 0;
    private final Handler handler;
    int mReadPipeFd = -1;
    private final Consumer<int[]> mCallback;
    private SatelliteBlockingUidDataListener mSatelliteBlockingUidDataListener;

    private  class SatelliteBlockingUidDataListener extends PacketReader {
        private final FileDescriptor mFd;

        public SatelliteBlockingUidDataListener(@NonNull Handler h, @NonNull FileDescriptor fd) {
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
                final ByteBuffer buffer = ByteBuffer
                        .wrap(recvbuf)
                        .order(ByteOrder.nativeOrder());

                satelliteUidsBlockList = new int[length];
                for (int i = 0; i < length; i++) {
                    satelliteUidsBlockList[i] = buffer.getInt();
                }
                Log.i(TAG, "satellite uid received:" + length
                        + ":" + Arrays.toString(satelliteUidsBlockList));
            }
            if (satelliteUidsBlockList.length > 0) {
                onSatelliteUidBlockingListReceived(satelliteUidsBlockList);
            }
        }
    }

    private void onSatelliteUidBlockingListReceived(int[] satelliteUidsBlockList) {
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
                        isSatelliteNetworkConnected = capabilities.hasTransport(
                                NetworkCapabilities.TRANSPORT_SATELLITE);
                              /*  && !capabilities.hasCapability(NetworkCapabilities
                                                .NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED);*/
                        boolean isWifiConnected = capabilities.hasTransport(
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

    public SatelliteDataUsageController(@NonNull final Context c,
            Consumer<int[]> callback,
            @NonNull final Handler connectivityServiceInternalHandler) {
        this(c, callback, new Dependencies(), connectivityServiceInternalHandler);
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

        /**
         * Set up satellite event config to generate ring buffer at native code, setup max data
         * usage limit allowed for uid and polling interval to monitor the ring buffer at native
         * code.
         *
         */
        private int[] setupSatelliteEventConfig(@NonNull Context context) {
            if ( getResources(context) != null) {
                int[] satelliteEventConfig = getResources(context)
                        .get().getIntArray(R.array.config_satellite_event);

                Log.i(TAG, "satellite event configs set"
                        + Arrays.toString(satelliteEventConfig));

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

        /**
         * sets the int descriptor
         */
        private static FileDescriptor createFileDescriptor(int fd) {
            FileDescriptor fileDescriptor = new FileDescriptor();
            fileDescriptor.setInt$(fd);
            return fileDescriptor;
        }
    }

    @VisibleForTesting
    SatelliteDataUsageController(@NonNull final Context c, Consumer<int[]> callback,
            @NonNull final Dependencies deps,
            @NonNull final Handler connectivityServiceInternalHandler) {
        mContext = c;
        mDeps = deps;
        mConnectivityServiceHandler = connectivityServiceInternalHandler;
        mCallback = callback;
        HandlerThread ht = new HandlerThread("");
        ht.start();
        handler = new Handler(ht.getLooper());
    }

    private void initialiseSatelliteBufferEvent() {
        if(mReadPipeFd == -1) {
            Log.i(TAG, "Initialise pipe");
            mReadPipeFd = nativeInitSatelliteBufferEvent();
        }

        if(mReadPipeFd != -1) {
            monitorPipeForSatelliteUidTobeBlockedUpdates();
        }
    }

    public synchronized void registerForSatelliteNetwork() {
        if (!isRegistered) {
            isRegistered = true;
            mConnectivityManager
                    = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkRequest.Builder builder = new NetworkRequest.Builder();
            builder.addTransportType(
                    NetworkCapabilities.TRANSPORT_SATELLITE);
            builder.addTransportType(
                    NetworkCapabilities.TRANSPORT_WIFI);
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
            // at native code.
            SatelliteEventConfig[3] = SatelliteEventConfig[4];
            Log.i(TAG, "interface index: " + SatelliteEventConfig[4]);
            isSatelliteBufferEventActive = true;

            // Initialise pipe at native code and start FDEventsReader using read fd for monitoring
            // blocking updates on satellite uids
            initialiseSatelliteBufferEvent();

            Thread nativeThread = new Thread(() -> {
                if (mReadPipeFd != -1) {
                    // start satellite events to ring buffer, monitor and write to pipe using write
                    // fd the uids to be blocked, on crossing data usage limits at native code
                    // with different thread.
                    nativeStartSatelliteBufferEvent(SatelliteEventConfig);

                } else {
                    Log.wtf(TAG, "pipe fd not created, dont start satellite event");

                }
            });
            nativeThread.start();
        }
    }

    private void monitorPipeForSatelliteUidTobeBlockedUpdates() {
        Log.i(TAG, "start monitoring");
        mSatelliteBlockingUidDataListener =
                new SatelliteBlockingUidDataListener(handler, Dependencies.createFileDescriptor(mReadPipeFd));
        handler.post(() -> mSatelliteBlockingUidDataListener.start());
    }

    private void unregisterNativeSatelliteBufferEvent() {
        if (isSatelliteBufferEventActive) {
            Log.i(TAG, "stop satellite buffer event");
            isSatelliteBufferEventActive = false;
            if (mCurrentSatelliteUidBlockingArrayCache != null
                    && mCurrentSatelliteUidBlockingArrayCache.length > 0) {
                onSatelliteUidBlockingListReceived(new int[0]);
            }
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
            int[] newSatelliteUidBlockingArray, boolean needBlocking) {

        if (needBlocking) {
            // update uid cache for enforcement
            if (mCurrentSatelliteUidBlockingArrayCache != null
                    && mCurrentSatelliteUidBlockingArrayCache.length > 0) {
                updateSatelliteBlockingListCache(newSatelliteUidBlockingArray);
            } else {
                mCurrentSatelliteUidBlockingArrayCache
                        = Arrays.copyOf(newSatelliteUidBlockingArray,
                        newSatelliteUidBlockingArray.length);
            }
            Log.i(TAG, "enforce blocking rule for uids: " + Arrays.toString(
                    mCurrentSatelliteUidBlockingArrayCache));

            // enforce blocking rule for the uid cache
            if (!mConnectivityManager.getFirewallChainEnabled(
                    FIREWALL_CHAIN_DENY_SATELLITE_NETWORK)) {
                Log.i(TAG, "firewall chain not enabled");
                mConnectivityManager.setFirewallChainEnabled(
                        FIREWALL_CHAIN_DENY_SATELLITE_NETWORK, true);
            }
            mConnectivityManager.replaceFirewallChain(FIREWALL_CHAIN_DENY_SATELLITE_NETWORK,
                    mCurrentSatelliteUidBlockingArrayCache);

            // update dialog for the new uids added part of the cache
            mCallback.accept(newSatelliteUidBlockingArray);
        } else {
            Log.i(TAG, "enforce unblock rule for uids : " + Arrays.toString(
                    mCurrentSatelliteUidBlockingArrayCache));
            mConnectivityManager.replaceFirewallChain(FIREWALL_CHAIN_DENY_SATELLITE_NETWORK,
                    newSatelliteUidBlockingArray);
            mCurrentSatelliteUidBlockingArrayCache = new int[0];
        }
    }

    private void updateSatelliteBlockingListCache(int[] newUidsToBlock) {
        // 1. Use a Set to track existing elements
        Set<Integer> existingElements = new HashSet<>();
        for (int num : mCurrentSatelliteUidBlockingArrayCache) {
            existingElements.add(num);
        }

        // 2. Count the number of new elements to add
        int newElementsCount = 0;
        for (int num : newUidsToBlock) {
            if (!existingElements.contains(num)) {
                newElementsCount++;
            }
        }

        // 3. Create a new array with the correct size
        int[] temp = new int[mCurrentSatelliteUidBlockingArrayCache.length + newElementsCount];

        // 4. Copy existing elements to the new array
        System.arraycopy(mCurrentSatelliteUidBlockingArrayCache, 0, temp, 0,
                mCurrentSatelliteUidBlockingArrayCache.length);

        // 5. Add new, unique elements to the new array
        int index = mCurrentSatelliteUidBlockingArrayCache.length;
        for (int num : newUidsToBlock) {
            if (!existingElements.contains(num)) {
                temp[index++] = num;
            }
        }
        mCurrentSatelliteUidBlockingArrayCache = temp;
    }

    private int findNonZeroLength(byte[] buffer) {
        int length = 0;
        for (int i = 0; i < buffer.length; i += 4/*size of integer*/) {
            int intValue = ByteBuffer.wrap(buffer, i, 4)
                    .order(ByteOrder.nativeOrder()).getInt();
            if (intValue != 0) {
                length++;
            }
        }
        return length;
    }

    /** Initialise Satellite Buffer Event interface for read and write file descriptors */
    public static native int nativeInitSatelliteBufferEvent();

    /** Start Satellite Buffer Event */
    public static native void nativeStartSatelliteBufferEvent(int[] config);

    /** Stop Satellite Buffer Event */
    public static native void nativeStopSatelliteBufferEvent();
}
