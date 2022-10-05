/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.connectivity.mdns;

import android.annotation.Nullable;
import android.annotation.NonNull;
import android.annotation.TargetApi;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.ConnectivityThread;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TetheringManager;
import android.os.Build;
import android.os.Handler;
import android.system.OsConstants;
import android.util.Log;

import com.android.net.module.util.ip.NetlinkMonitor;
import com.android.net.module.util.netlink.NetlinkConstants;
import com.android.net.module.util.netlink.NetlinkMessage;
import com.android.server.connectivity.mdns.util.MdnsLogger;

import java.util.List;

/** Class for monitoring connectivity changes using {@link ConnectivityManager}. */
public class ConnectivityMonitorWithConnectivityManager implements ConnectivityMonitor {
    private static final String TAG = "ConnMntrWConnMgr";
    private static final MdnsLogger LOGGER = new MdnsLogger(TAG);

    private final Listener listener;
    private final ConnectivityManager.NetworkCallback networkCallback;
    private final TetheringManager.TetheringEventCallback tetheringCallback;
    private final NetlinkMonitor netlinkMonitor;
    private final Context context;
    // TODO(b/71901993): Ideally we shouldn't need this flag. However we still don't have clues why
    // the receiver is unregistered twice yet.
    private boolean isCallbackRegistered = false;
    private Network lastAvailableNetwork = null;

    @SuppressWarnings({"nullness:assignment", "nullness:method.invocation"})
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public ConnectivityMonitorWithConnectivityManager(Context context, Listener listener) {
        this.listener = listener;
        this.context = context;
        networkCallback =
                new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        LOGGER.log("network available.");
                        lastAvailableNetwork = network;
                        notifyConnectivityChange();
                    }

                    @Override
                    public void onLost(Network network) {
                        LOGGER.log("network lost.");
                        notifyConnectivityChange();
                    }

                    @Override
                    public void onUnavailable() {
                        LOGGER.log("network unavailable.");
                        notifyConnectivityChange();
                    }
                };
        // TODO: don't just notifyConnectivityChange; surface Network objects that map to each
        // interface so the Network <-> interface mapping is known
        tetheringCallback = new TetheringManager.TetheringEventCallback() {
            @Override
            public void onTetheredInterfacesChanged(@NonNull List<String> interfaces) {
                notifyConnectivityChange();
            }

            @Override
            public void onLocalOnlyInterfacesChanged(@NonNull List<String> interfaces) {
                notifyConnectivityChange();
            }
        };
        // MulticastNetworkInterfaceProvider excludes interfaces that have no address: need to
        // refresh on address change, both for upstream and downstream interfaces
        // TODO: technically LinkPropertiesChanged may be enough, since for tethering downstreams
        // the available callback is sent after interfaces are configured (maybe ?)
        netlinkMonitor = new NetlinkMonitor(new Handler(ConnectivityThread.getInstanceLooper()),
                LOGGER.mLog, TAG, OsConstants.NETLINK_ROUTE,
                NetlinkConstants.RTMGRP_IPV4_IFADDR | NetlinkConstants.RTMGRP_IPV6_IFADDR) {
            @Override
            protected void processNetlinkMessage(NetlinkMessage nlMsg, long whenMs) {
                // TODO: remove this log
                Log.i(TAG, "Got netlink message for address change on iface: " + nlMsg);
                notifyConnectivityChange();
            }
        };
    }

    @Override
    public void notifyConnectivityChange() {
        listener.onConnectivityChanged();
    }

    /**
     * Starts monitoring changes of connectivity of this device, which may indicate that the list of
     * network interfaces available for multi-cast messaging has changed.
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Override
    public void startWatchingConnectivityChanges() {
        LOGGER.log("Start watching connectivity changes");
        if (isCallbackRegistered) {
            return;
        }

        context.getSystemService(ConnectivityManager.class).registerNetworkCallback(
                new NetworkRequest.Builder().addTransportType(
                        NetworkCapabilities.TRANSPORT_WIFI).build(),
                networkCallback);
        context.getSystemService(TetheringManager.class).registerTetheringEventCallback(
                Runnable::run, tetheringCallback);
        netlinkMonitor.getHandler().post(netlinkMonitor::start);
        isCallbackRegistered = true;
    }

    /** Stops monitoring changes of connectivity. */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Override
    public void stopWatchingConnectivityChanges() {
        LOGGER.log("Stop watching connectivity changes");
        if (!isCallbackRegistered) {
            return;
        }

        context.getSystemService(ConnectivityManager.class)
                .unregisterNetworkCallback(networkCallback);
        context.getSystemService(TetheringManager.class).unregisterTetheringEventCallback(
                tetheringCallback);
        netlinkMonitor.getHandler().post(netlinkMonitor::stop);
        isCallbackRegistered = false;
    }

    @Override
    @Nullable
    public Network getAvailableNetwork() {
        return lastAvailableNetwork;
    }
}