/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.cts.net.hostside;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Network;
import android.net.NetworkUtils;
import android.net.ProxyInfo;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;

public class MyVpnService extends VpnService {

    private static final String TAG = "MyVpnService";
    private static final int MTU = 1799;

    private static final String COMMAND_CONNECT = "connect";
    private static final String COMMAND_DISCONNECT = "disconnect";

    private static final String PACKAGE_NAME = "com.android.cts.net.hostside";

    public static final String ACTION_ESTABLISHED = PACKAGE_NAME + ".ESTABNLISHED";

    public static final String EXTRA_ALWAYS_ON = "is-always-on";
    public static final String EXTRA_LOCKDOWN_ENABLED = "is-lockdown-enabled";

    private static final String EXTRA_COMMAND = PACKAGE_NAME + ".cmd";
    private static final String EXTRA_ADDRESSES = PACKAGE_NAME + ".addresses";
    private static final String EXTRA_INCLUDED_ROUTES = PACKAGE_NAME + ".includedRoutes";
    private static final String EXTRA_EXCLUDED_ROUTES = PACKAGE_NAME + ".excludedRoutes";
    private static final String EXTRA_ALLOWED_APPLICATIONS = PACKAGE_NAME + ".allowedApplications";
    private static final String EXTRA_DISALLOWED_APPLICATIONS =
            PACKAGE_NAME + ".disallowedApplications";
    private static final String EXTRA_UNDERLYING_NETWORKS = PACKAGE_NAME + ".underlyingNetworks";
    private static final String EXTRA_HTTP_PROXY = PACKAGE_NAME + ".httpProxy";
    private static final String EXTRA_IS_ALWAYS_METERED = PACKAGE_NAME + ".isAlwaysMetered";

    private ParcelFileDescriptor mFd = null;
    private PacketReflector mPacketReflector = null;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String cmd = intent.getStringExtra(EXTRA_COMMAND);
        if (COMMAND_DISCONNECT.equals(cmd)) {
            stop();
        } else if (COMMAND_CONNECT.equals(cmd)) {
            start(intent);
        }

        return START_NOT_STICKY;
    }

    public static IntentBuilder connect(Context context) {
        return new IntentBuilder(context, COMMAND_CONNECT);
    }

    public static class IntentBuilder {
        private final Intent mIntent;

        private IntentBuilder(Context context, String command) {
            mIntent = new Intent(context, MyVpnService.class);
            mIntent.putExtra(EXTRA_COMMAND, command);
        }

        public IntentBuilder setAddresses(String[] addresses) {
            mIntent.putExtra(EXTRA_ADDRESSES, TextUtils.join(",", addresses));
            return this;
        }

        public IntentBuilder setIncludedRoutes(String[] routes) {
            mIntent.putExtra(EXTRA_INCLUDED_ROUTES, TextUtils.join(",", routes));
            return this;
        }

        public IntentBuilder setExcludedRoutes(String[] excludeRoutes) {
            mIntent.putExtra(EXTRA_EXCLUDED_ROUTES, TextUtils.join(",", excludeRoutes));
            return this;
        }

        public IntentBuilder setAllowedApplications(String allowedApplications) {
            mIntent.putExtra(EXTRA_ALLOWED_APPLICATIONS, allowedApplications);
            return this;
        }

        public IntentBuilder setDisallowedApplications(String disallowedApplications) {
            mIntent.putExtra(EXTRA_DISALLOWED_APPLICATIONS, disallowedApplications);
            return this;
        }

        public IntentBuilder setProxyInfo(ProxyInfo proxyInfo) {
            mIntent.putExtra(EXTRA_HTTP_PROXY, proxyInfo);
            return this;
        }

        public IntentBuilder setUnderlyingNetworks(ArrayList<Network> underlyingNetworks) {
            mIntent.putExtra(EXTRA_UNDERLYING_NETWORKS, underlyingNetworks);
            return this;
        }

        public IntentBuilder setAlwaysMetered(boolean isAlwaysMetered) {
            mIntent.putExtra(EXTRA_IS_ALWAYS_METERED, isAlwaysMetered);
            return this;
        }

        public Intent buildIntent() {
            return mIntent;
        }
    }

    private void start(Intent intent) {
        Builder builder = new Builder();

        String addresses = intent.getStringExtra(EXTRA_ADDRESSES);
        if (addresses != null) {
            String[] addressArray = addresses.split(",");
            for (int i = 0; i < addressArray.length; i++) {
                try {
                    Pair<InetAddress, Integer> ipAndMask = NetworkUtils.parseIpAndMask(
                            addressArray[i]);
                    builder.addAddress(ipAndMask.first, ipAndMask.second);
                } catch (IllegalArgumentException e) {
                    continue;
                }
            }
        }

        String routes = intent.getStringExtra(EXTRA_INCLUDED_ROUTES);
        if (routes != null) {
            String[] routeArray = routes.split(",");
            for (int i = 0; i < routeArray.length; i++) {
                try {
                    Pair<InetAddress, Integer> ipAndMask = NetworkUtils.parseIpAndMask(
                            routeArray[i]);
                    builder.addRoute(ipAndMask.first, ipAndMask.second);
                } catch (IllegalArgumentException e) {
                    continue;
                }
            }
        }

        String excludeRoutes = intent.getStringExtra(EXTRA_EXCLUDED_ROUTES);
        if (excludeRoutes != null) {
            String[] routeArray = excludeRoutes.split(",");
            for (int i = 0; i < routeArray.length; i++) {
                try {
                    Pair<InetAddress, Integer> ipAndMask = NetworkUtils.parseIpAndMask(
                            routeArray[i]);
                    builder.excludeRoute(ipAndMask.first, ipAndMask.second);
                } catch (IllegalArgumentException e) {
                    continue;
                }
            }
        }

        String allowed = intent.getStringExtra(EXTRA_ALLOWED_APPLICATIONS);
        if (allowed != null) {
            String[] packageArray = allowed.split(",");
            for (int i = 0; i < packageArray.length; i++) {
                String allowedPackage = packageArray[i];
                if (!TextUtils.isEmpty(allowedPackage)) {
                    try {
                        builder.addAllowedApplication(allowedPackage);
                    } catch (NameNotFoundException e) {
                        continue;
                    }
                }
            }
        }

        String disallowed = intent.getStringExtra(EXTRA_DISALLOWED_APPLICATIONS);
        if (disallowed != null) {
            String[] packageArray = disallowed.split(",");
            for (int i = 0; i < packageArray.length; i++) {
                String disallowedPackage = packageArray[i];
                if (!TextUtils.isEmpty(disallowedPackage)) {
                    try {
                        builder.addDisallowedApplication(disallowedPackage);
                    } catch (NameNotFoundException e) {
                        continue;
                    }
                }
            }
        }

        ArrayList<Network> underlyingNetworks =
                intent.getParcelableArrayListExtra(EXTRA_UNDERLYING_NETWORKS);
        if (underlyingNetworks == null) {
            // VPN tracks default network
            builder.setUnderlyingNetworks(null);
        } else {
            builder.setUnderlyingNetworks(underlyingNetworks.toArray(new Network[0]));
        }

        boolean isAlwaysMetered = intent.getBooleanExtra(EXTRA_IS_ALWAYS_METERED, false);
        builder.setMetered(isAlwaysMetered);

        ProxyInfo vpnProxy = intent.getParcelableExtra(EXTRA_HTTP_PROXY);
        builder.setHttpProxy(vpnProxy);
        builder.setMtu(MTU);
        builder.setBlocking(true);
        builder.setSession("MyVpnService");

        Log.i(TAG, "Establishing VPN,"
                + " addresses=" + addresses
                + " routes=" + routes
                + " allowedApplications=" + allowed
                + " disallowedApplications=" + disallowed);

        mFd = builder.establish();
        Log.i(TAG, "Established, fd=" + (mFd == null ? "null" : mFd.getFd()));

        broadcastEstablished();

        mPacketReflector = new PacketReflector(mFd.getFileDescriptor(), MTU);
        mPacketReflector.start();
    }

    private void broadcastEstablished() {
        final Intent bcIntent = new Intent(ACTION_ESTABLISHED);
        bcIntent.putExtra(EXTRA_ALWAYS_ON, isAlwaysOn());
        bcIntent.putExtra(EXTRA_LOCKDOWN_ENABLED, isLockdownEnabled());
        sendBroadcast(bcIntent);
    }

    private void stop() {
        if (mPacketReflector != null) {
            mPacketReflector.interrupt();
            mPacketReflector = null;
        }
        try {
            if (mFd != null) {
                Log.i(TAG, "Closing filedescriptor");
                mFd.close();
            }
        } catch (IOException e) {
        } finally {
            mFd = null;
        }
    }

    @Override
    public void onDestroy() {
        stop();
        super.onDestroy();
    }
}
