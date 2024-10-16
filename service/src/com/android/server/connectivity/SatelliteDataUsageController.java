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
    private int[] mCurrentSatelliteUidBlockingArrayCache;
    private static final int INVALID_IFINDEX = 0;
    private static final int UNLIMITED_DATA_USAGE_LIMIT = 0; // Unlimited data usage
    private final Handler mHandler;
    int mReadPipeFd = -1;
    private SatelliteBlockingUidDataListener mSatelliteBlockingUidDataListener;
    private final Consumer<int[]> mCallback;
    int[] mUnlimitedSatelliteDataUsageAllowedArrayCache;

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
            if (satelliteUidsBlockList.length > 0) {
                onSatelliteUidBlockingListReceived(satelliteUidsBlockList);
            }
        }
    }

    private void onSatelliteUidBlockingListReceived(int[] satelliteUidsBlockList) {
        final Runnable r =
                () ->
                        updateFirewallRuleOfSatelliteUids(
                                satelliteUidsBlockList, satelliteUidsBlockList.length > 0);

        mConnectivityServiceHandler.post(r);
    }

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
                                                        NetworkCapabilities.TRANSPORT_SATELLITE)
                                                && !capabilities.hasCapability(
                                                        NetworkCapabilities
                                                                .NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED);
                                boolean isWifiConnected =
                                        capabilities.hasTransport(
                                                NetworkCapabilities.TRANSPORT_WIFI);
                                // Satellite network with wifi network, don't monitor satellite data
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
            @NonNull final Context c,
            Consumer<int[]> callback,
            @NonNull final Handler connectivityServiceInternalHandler) {
        this(c, callback, new Dependencies(), connectivityServiceInternalHandler);
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
                30, /*polling_count mapped to secs*/
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
            Consumer<int[]> callback,
            @NonNull final Dependencies deps,
            @NonNull final Handler connectivityServiceInternalHandler) {
        mContext = c;
        mDeps = deps;
        mConnectivityServiceHandler = connectivityServiceInternalHandler;
        mCallback = callback;
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
            mReadPipeFd = nativeInitSatelliteBufferEvent();
        }

        if (mReadPipeFd != -1) {
            monitorPipeForSatelliteUidTobeBlockedUpdates();
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
        int maxDataUsageLimitPerUid = mDeps.getSatelliteDataUsageLimitPerUid(mContext);

        if (ifaceIndex > 0 && maxDataUsageLimitPerUid > UNLIMITED_DATA_USAGE_LIMIT) {
            // build other satellite event configs
            int[] SatelliteEventConfig = mDeps.setupSatelliteEventConfig();
            // Update max satellite data usage limit per uid
            SatelliteEventConfig[1] = maxDataUsageLimitPerUid;
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
                                    Log.wtf(
                                            TAG,
                                            "pipe fd not created,"
                                                    + " don't start satellite buffer event");
                                }
                            });
            nativeThread.start();
        }
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

    public static int[] exemptAllowedListUids(int[] unlimitedDataAllowedUids, int[] satelliteUids) {
        Set<Integer> set1 = new HashSet<>();
        for (int num : unlimitedDataAllowedUids) {
            set1.add(num);
        }

        Set<Integer> set2 = new HashSet<>();
        for (int num : satelliteUids) {
            set2.add(num);
        }

        set2.removeAll(set1);

        int[] satelliteUidEnforcementList = new int[set2.size()];
        int i = 0;
        for (int num : set2) {
            satelliteUidEnforcementList[i++] = num;
        }

        return satelliteUidEnforcementList;
    }

    private void updateSatelliteUidBlockingArrayCache(int[] newUidsToBlock) {
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
        System.arraycopy(
                mCurrentSatelliteUidBlockingArrayCache,
                0,
                temp,
                0,
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

    private void buildUidListCacheForFirewallEnforcement(int[] uidBlockingArray) {
        // update uid cache for enforcement
        if (mCurrentSatelliteUidBlockingArrayCache != null
                && mCurrentSatelliteUidBlockingArrayCache.length > 0) {
            updateSatelliteUidBlockingArrayCache(uidBlockingArray);
        } else {
            mCurrentSatelliteUidBlockingArrayCache =
                    Arrays.copyOf(uidBlockingArray, uidBlockingArray.length);
        }
        Log.i(
                TAG,
                "enforce blocking rule for uids: "
                        + Arrays.toString(mCurrentSatelliteUidBlockingArrayCache));
    }

    // update firewall rule for satellite uids for blocking and unblocking network access.
    // On Callback received from native code with uid list to block or on satellite network
    // disconnected, call this method with uids to update firewall rule
    private void updateFirewallRuleOfSatelliteUids(
            int[] newSatelliteUidBlockingArray, boolean needBlocking) {

        if (needBlocking) {
            int[] satelliteUidBlockingArray;
            // filter out the uids part of unlimited satellite data usage allowed cache
            if (mUnlimitedSatelliteDataUsageAllowedArrayCache.length > 0) {
                satelliteUidBlockingArray =
                        exemptAllowedListUids(
                                mUnlimitedSatelliteDataUsageAllowedArrayCache,
                                newSatelliteUidBlockingArray);
            } else {
                satelliteUidBlockingArray = newSatelliteUidBlockingArray;
            }
            Log.i(
                    TAG,
                    "uid list after allow list exception: "
                            + Arrays.toString(satelliteUidBlockingArray));

            if (satelliteUidBlockingArray.length > 0) {
                buildUidListCacheForFirewallEnforcement(satelliteUidBlockingArray);

                // enforce deny firewall rule for the uid cache
                if (!mConnectivityManager.getFirewallChainEnabled(
                        FIREWALL_CHAIN_DENY_SATELLITE_NETWORK)) {
                    Log.i(TAG, "firewall chain not enabled");
                    mConnectivityManager.setFirewallChainEnabled(
                            FIREWALL_CHAIN_DENY_SATELLITE_NETWORK, true);
                }
                mConnectivityManager.replaceFirewallChain(
                        FIREWALL_CHAIN_DENY_SATELLITE_NETWORK,
                        mCurrentSatelliteUidBlockingArrayCache);

                // update dialog for the new uids added part of the cache
                mCallback.accept(satelliteUidBlockingArray);
            }
        } else {
            Log.i(
                    TAG,
                    "enforce unblock rule for uids: "
                            + Arrays.toString(mCurrentSatelliteUidBlockingArrayCache));
            mConnectivityManager.replaceFirewallChain(
                    FIREWALL_CHAIN_DENY_SATELLITE_NETWORK, newSatelliteUidBlockingArray);
            mCurrentSatelliteUidBlockingArrayCache = new int[0];
        }
    }

    public static int[] convertSatelliteDataUsageAllowedListToArray(Set<Integer> uidList) {
        int[] activeMessagingUidsOverSatellite = new int[uidList.size()];
        int i = 0;
        for (Integer value : uidList) {
            activeMessagingUidsOverSatellite[i++] = value;
        }
        return activeMessagingUidsOverSatellite;
    }

    public void updateSatelliteDataUsageAllowedList(Set<Integer> satelliteDataUsageAllowedList) {
        mUnlimitedSatelliteDataUsageAllowedArrayCache =
                convertSatelliteDataUsageAllowedListToArray(satelliteDataUsageAllowedList);
        Log.i(
                TAG,
                "received uid at exception list:"
                        + Arrays.toString(mUnlimitedSatelliteDataUsageAllowedArrayCache));
    }

    /** Initialise Satellite Buffer Event interface for read and write file descriptors */
    public static native int nativeInitSatelliteBufferEvent();

    /** Start Satellite Buffer Event */
    public static native void nativeStartSatelliteBufferEvent(int[] config);

    /** Stop Satellite Buffer Event */
    public static native void nativeStopSatelliteBufferEvent();
}
