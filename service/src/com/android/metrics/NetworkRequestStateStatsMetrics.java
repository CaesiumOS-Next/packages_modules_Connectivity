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

package com.android.metrics;

import static com.android.server.ConnectivityStatsLog.NETWORK_REQUEST_STATE_CHANGED;
import static com.android.server.ConnectivityStatsLog.NETWORK_REQUEST_STATE_CHANGED__STATE__NETWORK_REQUEST_STATE_RECEIVED;
import static com.android.server.ConnectivityStatsLog.NETWORK_REQUEST_STATE_CHANGED__STATE__NETWORK_REQUEST_STATE_REMOVED;

import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.BitUtils;
import com.android.server.ConnectivityStatsLog;

/**
 * A Connectivity Service helper class to push atoms capturing network requests have been received
 * and removed and its metadata.
 *
 * Atom events are logged in the ConnectivityStatsLog. Network request id: network request metadata
 * hashmap is stored to calculate network request duration when it is removed.
 *
 * Note that this class is not thread-safe. The instance of the class needs to be
 * synchronized in the callers when being used in multiple threads.
 */
public class NetworkRequestStateStatsMetrics {

    private static final String TAG = "NetworkRequestStateStatsMetrics";
    @VisibleForTesting
    static final int MSG_NETWORK_REQUEST_RECEIVED = 0;
    @VisibleForTesting
    static final int MSG_NETWORK_REQUEST_REMOVED = 1;
    private static final int ATOM_INTERVAL_MS = 1000;
    @VisibleForTesting
    final SparseArray<NetworkRequestStateInfo> mNetworkRequestsActive;

    @VisibleForTesting
    final Handler mStatsLoggingHandler;

    @VisibleForTesting
    private final Dependencies mDependencies;

    public NetworkRequestStateStatsMetrics() {
        this(new Dependencies());
    }

    @VisibleForTesting
    NetworkRequestStateStatsMetrics(Dependencies deps) {
        HandlerThread handlerThread = new HandlerThread(TAG);
        handlerThread.start();
        mStatsLoggingHandler = new StatsLoggingHandler(handlerThread.getLooper());
        mNetworkRequestsActive = new SparseArray<>();
        mDependencies = deps;
    }

    @VisibleForTesting
    boolean checkTransportSpecifiedNR(NetworkRequest networkRequest) {
        return (BitUtils.packBits(networkRequest.networkCapabilities.getTransportTypes()) > 0)
                || networkRequest.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }

    /**
     * Register network request remove event, push RECEIVE atom
     *
     * @param packageUid     Uid of the app package that sent the request
     * @param networkRequest network request removed
     */
    public void onNetworkRequestReceived(int packageUid, NetworkRequest networkRequest) {
        if (!checkTransportSpecifiedNR(networkRequest)) {
            Log.i(TAG, "Received NR with neither transports specified nor NOT_METERED cap,"
                    + "id = " + networkRequest.requestId + ", skipping the NR");
            return;
        }
        if (mNetworkRequestsActive.contains(networkRequest.requestId)) {
            Log.w(TAG, "Received already registered network request, id = "
                    + networkRequest.requestId);
        } else {
            Log.d(TAG, "Registered nr with ID = " + networkRequest.requestId
                    + ", package_uid = " + packageUid);
            NetworkRequestStateInfo networkRequestStateInfo = new NetworkRequestStateInfo(
                    packageUid, networkRequest, mDependencies);
            mNetworkRequestsActive.put(networkRequest.requestId, networkRequestStateInfo);
            mDependencies.sendStatsMessage(mStatsLoggingHandler,
                    MSG_NETWORK_REQUEST_RECEIVED, networkRequestStateInfo);
        }
    }

    /**
     * Register network request remove event, push REMOVE atom
     *
     * @param networkRequest network request removed
     */
    public void onNetworkRequestRemoved(NetworkRequest networkRequest) {
        NetworkRequestStateInfo networkRequestStateInfo = mNetworkRequestsActive.get(
                networkRequest.requestId);
        if (networkRequestStateInfo == null) {
            Log.w(TAG, "This NR hasn't been registered. NR id = " + networkRequest.requestId);
        } else {
            Log.d(TAG, "Removed nr with ID = " + networkRequest.requestId);

            mNetworkRequestsActive.remove(networkRequest.requestId);
            networkRequestStateInfo.captureNetworkRequestCurrentDuration();
            mDependencies.sendStatsMessage(mStatsLoggingHandler,
                    MSG_NETWORK_REQUEST_REMOVED, networkRequestStateInfo);
        }
    }

    /** Dependency class */
    public static class Dependencies {
        // Returns a timestamp with the time base of SystemClock.elapsedRealtime to keep durations
        // relative to start time and avoid timezone change, including time spent in deep sleep.
        public long getElapsedRealtime() {
            return SystemClock.elapsedRealtime();
        }

        /**
         * Sends a NETWORK_REQUEST_STATE event message to ConnectivityStatsLog logger.
         *
         * @param handler                 the handler to send message to the logger thread;
         * @param msgType                type of the message sent to the logger thread;
         * @param networkRequestStateInfo object NetworkRequestStateInfo containing event info.
         */
        public void sendStatsMessage(Handler handler, int msgType,
                NetworkRequestStateInfo networkRequestStateInfo) {
            handler.sendMessage(Message.obtain(handler, msgType, networkRequestStateInfo));
        }
    }

    protected class StatsLoggingHandler extends Handler {
        private static final String TAG = "NetworkRequestsStateStatsLoggingHandler";
        private static final int NETWORK_REQUEST_STATE_RECEIVED =
                NETWORK_REQUEST_STATE_CHANGED__STATE__NETWORK_REQUEST_STATE_RECEIVED;
        private static final int NETWORK_REQUEST_STATE_REMOVED =
                NETWORK_REQUEST_STATE_CHANGED__STATE__NETWORK_REQUEST_STATE_REMOVED;
        private long mLastLogTime = 0;

        StatsLoggingHandler(Looper looper) {
            super(looper);
        }

        private void checkStatsLoggingTimeout() {
            // Cool down before next execution. Required by atom logging frequency.
            try {
                long now = mDependencies.getElapsedRealtime();
                if (now - mLastLogTime < ATOM_INTERVAL_MS) {
                    Thread.sleep(ATOM_INTERVAL_MS);
                }
                mLastLogTime = now;
            } catch (InterruptedException e) {
                Log.w(TAG, "Cool down interrupted!", e);
            }
        }

        private void writeStats(NetworkRequestStateInfo networkRequestStateInfo,
                int networkRequestState) {
            ConnectivityStatsLog.write(
                    NETWORK_REQUEST_STATE_CHANGED,
                    networkRequestStateInfo.mPackageUid,
                    networkRequestStateInfo.getTransportTypes(),
                    networkRequestStateInfo.getNetCapabilityNotMetered(),
                    networkRequestStateInfo.getNetCapabilityInternet(),
                    networkRequestState,
                    networkRequestStateInfo.mNetworkRequestDurationMillis);
        }

        @Override
        public void handleMessage(Message msg) {
            NetworkRequestStateInfo loggingInfo;
            switch (msg.what) {
                case MSG_NETWORK_REQUEST_RECEIVED:
                    checkStatsLoggingTimeout();
                    loggingInfo = (NetworkRequestStateInfo) msg.obj;

                    Log.d(TAG, "Writing to log NetworkRequest receive state: " + loggingInfo);
                    writeStats(loggingInfo, NETWORK_REQUEST_STATE_RECEIVED);
                    break;
                case MSG_NETWORK_REQUEST_REMOVED:
                    checkStatsLoggingTimeout();
                    loggingInfo = (NetworkRequestStateInfo) msg.obj;

                    Log.d(TAG, "Writing to log NetworkRequest remove state: " + loggingInfo);
                    writeStats(loggingInfo, NETWORK_REQUEST_STATE_REMOVED);
                    break;
                default: // fall out
            }
        }
    }
}
