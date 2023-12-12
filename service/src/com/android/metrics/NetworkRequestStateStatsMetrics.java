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

import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.ConnectivityStatsLog;

import java.util.HashMap;


public class NetworkRequestStateStatsMetrics {

    private static final String TAG = "NetworkRequestStateStatsMetrics";
    @VisibleForTesting
    static final int MSG_SEND_RECEIVED = 0;
    @VisibleForTesting
    static final int MSG_SEND_REMOVED = 1;
    private static final int ATOM_INTERVAL_MS = 1000;
    @VisibleForTesting
    HashMap<Integer, NetworkRequestStateInfo> mNetworkRequestsActive;

    @VisibleForTesting
    Handler mStatsLoggingHandler;

    public NetworkRequestStateStatsMetrics() {
        HandlerThread handlerThread = new HandlerThread(TAG);
        handlerThread.start();
        mStatsLoggingHandler = new StatsLoggingHandler(handlerThread.getLooper());
        mNetworkRequestsActive = new HashMap<Integer, NetworkRequestStateInfo>();
    }

    private NetworkRequestStateInfo createNetworkRequestStateInfo(
            int packageUid, NetworkRequest networkRequest,
            int networkRequestReceivedTime, int networkRequestDuration
    ) {
        return new NetworkRequestStateInfo.Builder().setPackageUid(packageUid)
                .setNetworkRequestId(networkRequest.requestId)
                .setTransportTypes(
                        (int) networkRequest.networkCapabilities.getTransportTypesInternal())
                .setNetCapabilityNotMetered(
                        networkRequest.hasCapability(
                                NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                .setNetCapabilityInternet(
                        networkRequest.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                .setNetworkRequestReceivedTime(networkRequestReceivedTime)
                .setNetworkRequestDuration(networkRequestDuration)
                .build();
    }

    /**
     *  Register network request remove event, push RECEIVE atom
     * @param packageUid Uid of the app package that sent the request
     * @param networkRequest network request removed
     */
    public void registerNetworkRequestReceived(int packageUid, NetworkRequest networkRequest) {
        if (mNetworkRequestsActive.containsKey(networkRequest.requestId)) {
            Log.i(TAG, "Received already registered network request, id = "
                    + networkRequest.requestId);
        } else {
            Log.d(TAG, "Registered nr with ID = " + networkRequest.requestId
                    + ", package_uid = " + packageUid);
            NetworkRequestStateInfo networkRequestStateInfo = createNetworkRequestStateInfo(
                    packageUid, networkRequest, (int) SystemClock.elapsedRealtime(), 0);
            mNetworkRequestsActive.put(networkRequest.requestId, networkRequestStateInfo);
            mStatsLoggingHandler.sendMessage(
                    Message.obtain(mStatsLoggingHandler, MSG_SEND_RECEIVED,
                            networkRequestStateInfo));
        }
    }

    /**
     *  Register network request remove event, push REMOVE atom
     * @param networkRequest network request removed
     */
    public void registerNetworkRequestRemoved(NetworkRequest networkRequest) {
        if (mNetworkRequestsActive.containsKey(networkRequest.requestId)) {
            Log.d(TAG, "Removed nr with ID = " + networkRequest.requestId);
            NetworkRequestStateInfo networkRequestStateInfo = mNetworkRequestsActive.get(
                    networkRequest.requestId);
            networkRequestStateInfo.updateNetworkRequestDuration(
                    (int) SystemClock.elapsedRealtime()
                            - networkRequestStateInfo.mNetworkRequestReceivedTime);
            mStatsLoggingHandler.sendMessage(Message.obtain(mStatsLoggingHandler, MSG_SEND_REMOVED,
                    networkRequestStateInfo));
            mNetworkRequestsActive.remove(networkRequest.requestId);
        } else {
            Log.e(TAG, "This NR hasn't been registered. NR id = " + networkRequest.requestId);
        }
    }

    protected class StatsLoggingHandler extends Handler {
        private static final String TAG = "NetworkRequestsStateStatsLoggingHandler";
        private static final int NETWORK_REQUEST_STATE_RECEIVED =
                ConnectivityStatsLog
                        .NETWORK_REQUEST_STATE_CHANGED__STATE__NETWORK_REQUEST_STATE_RECEIVED;
        private static final int NETWORK_REQUEST_STATE_REMOVED =
                ConnectivityStatsLog
                        .NETWORK_REQUEST_STATE_CHANGED__STATE__NETWORK_REQUEST_STATE_REMOVED;
        private long mLastLogTime = 0;

        StatsLoggingHandler(Looper looper) {
            super(looper);
        }

        private void checkStatsLoggingTimeout() {
            // Cool down before next execution. Required by atom logging frequency.
            try {
                long now = SystemClock.elapsedRealtime();
                if (now - mLastLogTime < ATOM_INTERVAL_MS) {
                    Thread.sleep(ATOM_INTERVAL_MS);
                }
                mLastLogTime = now;
            } catch (InterruptedException e) {
                Log.w(TAG, "Cool down interrupted!", e);
            }
        }

        @Override
        public void handleMessage(Message msg) {
            NetworkRequestStateInfo loggingInfo;
            switch (msg.what) {
                case MSG_SEND_RECEIVED:
                    checkStatsLoggingTimeout();
                    loggingInfo = (NetworkRequestStateInfo) msg.obj;

                    Log.i(TAG, "Writing to log NetworkRequest receive state: " + loggingInfo);
                    ConnectivityStatsLog.write(
                            ConnectivityStatsLog.NETWORK_REQUEST_STATE_CHANGED,
                            loggingInfo.mPackageUid,
                            loggingInfo.mTransportTypes,
                            loggingInfo.mNetCapabilityNotMetered,
                            loggingInfo.mNetCapabilityInternet,
                            NETWORK_REQUEST_STATE_RECEIVED,
                            0);
                    break;
                case MSG_SEND_REMOVED:
                    checkStatsLoggingTimeout();
                    loggingInfo = (NetworkRequestStateInfo) msg.obj;

                    Log.i(TAG, "Writing to log NetworkRequest remove state: " + loggingInfo);
                    ConnectivityStatsLog.write(
                            ConnectivityStatsLog.NETWORK_REQUEST_STATE_CHANGED,
                            loggingInfo.mPackageUid,
                            loggingInfo.mTransportTypes,
                            loggingInfo.mNetCapabilityNotMetered,
                            loggingInfo.mNetCapabilityInternet,
                            NETWORK_REQUEST_STATE_REMOVED,
                            loggingInfo.mNetworkRequestDurationMillis);
                    break;
                default: // fall out
            }
        }
    }
}
