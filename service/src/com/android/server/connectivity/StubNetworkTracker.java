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

import android.annotation.Nullable;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.RouteInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.thread.ActiveOperationalDataset;
import android.net.thread.ThreadNetworkSpecifier;
import android.os.Handler;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.HandlerExecutor;

import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Tracks Stub networks on Adjacent Infrastructure Links (AIL).
 *
 * Currently tracks only Thread Border Router on Wi-Fi and Ethernet networks.
 *
 * This class is designed to be used in {@link ConnectivityService} from its single executor
 * thread, so no locks are required for synchronization.
 */
public final class StubNetworkTracker {
    private static final String TAG = "StubNetworkTracker";
    // Thread Border Router is the only Stub router currently supported
    private static final String THREAD_BORDER_ROUTER_MDNS_SERVICE_TYPE = "_meshcop._udp";
    private static final String KEY_THREAD_EXTENDED_PAN_ID_TXT_NAME = "xp";

    private static final int LENGTH_STUB_NETWORK_PREFIX_BITS = 64;
    private static final long MDNS_DISCOVER_TIMEOUT_MILLIS = 10 * 1000;
    private static final long MDNS_RESOLVE_TIMEOUT_MILLIS = 10 * 1000;

    private final Handler mHandler;
    private final AilNetworkCallback mAilNetworkCallback;
    private final Map<Network, StubNetworkResolver> mResolverMap;

    private ConnectivityManager mConnectivityManager;
    private NsdManager mNsdManager;
    private StubNetworkCallback mStubNetworkCallback;

    /** Callbacks for notifying Stub network updates. */
    public interface StubNetworkCallback {
        /** Called whenever a stub network is found or lost on the AIL. */
        void onStubNetworkChanged(Network ailNetwork, Set<NetworkSpecifier> stubNetworkSpecifiers);
    }

    public StubNetworkTracker(Handler handler) {
        mHandler = handler;
        mAilNetworkCallback = new AilNetworkCallback();
        mResolverMap = new ArrayMap<>();
    }

    public void start(ConnectivityManager connectivityManager, NsdManager nsdManager,
            StubNetworkCallback callback) {
        Objects.requireNonNull(connectivityManager);
        Objects.requireNonNull(nsdManager);
        Objects.requireNonNull(callback);

        mConnectivityManager = connectivityManager;
        mNsdManager = nsdManager;
        if (mStubNetworkCallback != null) {
            throw new AssertionError();
        }
        mStubNetworkCallback = callback;

        NetworkRequest ailRequest =
                new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                        .build();
        Log.i(TAG, "StubNetworkTracker: started");
        mConnectivityManager.registerNetworkCallback(ailRequest, mAilNetworkCallback);
    }

    public void stop() {
        mStubNetworkCallback = null;
        mConnectivityManager.unregisterNetworkCallback(mAilNetworkCallback);
    }

    /** Receives updates of AIL networks. */
    private final class AilNetworkCallback extends ConnectivityManager.NetworkCallback {
        @Override
        public void onAvailable(Network network) {
            StubNetworkResolver resolver = new StubNetworkResolver();
            mResolverMap.put(network, resolver);
        }

        @Override
        public void onLost(Network network) {
            StubNetworkResolver resolver = mResolverMap.remove(network);
            if (resolver != null) {
                resolver.stopDiscovery();
            }
            mStubNetworkCallback.onStubNetworkChanged(network, Collections.emptySet());
        }

        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
            if (!mayContainStubNetworkRoutes(linkProperties)) {
                return;
            }

            Set<IpPrefix> newLinkPrefixes = getAllLinkPrefixes(linkProperties);
            Set<IpPrefix> newRoutePrefixes = getAllRoutePrefixes(linkProperties);

            StubNetworkResolver resolver = mResolverMap.get(network);
            StubNetworkInfo sni = resolver.mStubNetworkInfo;

            // Ignores the link properties if there are no link or route prefix changes
            if (newLinkPrefixes.equals(sni.ailLinkPrefixes)
                    && newRoutePrefixes.equals(sni.ailRoutePrefixes)) {
                return;
            }

            sni.ailLinkPrefixes = newLinkPrefixes;
            sni.ailRoutePrefixes = newRoutePrefixes;

            resolver.startDiscovery(network);
        }
    }


    /** Returns {@code true} if there may be stub network routes in the given link properties. */
    private static boolean mayContainStubNetworkRoutes(LinkProperties lp) {
        for (RouteInfo routeInfo : lp.getAllRoutes()) {
            if (mayBeStubNetworkRoute(routeInfo, lp)) {
                return true;
            }
        }

        return false;
    }

    private static boolean mayBeStubNetworkRoute(RouteInfo routeInfo, LinkProperties lp) {
        final IpPrefix prefix = routeInfo.getDestination();

        if (prefix.getPrefixLength() != LENGTH_STUB_NETWORK_PREFIX_BITS) {
            return false;
        }

        // If the route prefix matches any of the link address on the AIL, this route prefix is
        // definitely not a stub network prefix.
        for (LinkAddress linkAddr : lp.getAllLinkAddresses()) {
            if (prefix.getPrefixLength() == linkAddr.getPrefixLength()
                    && prefix.contains(linkAddr.getAddress())) {
                return false;
            }
        }

        return true;
    }

    private static Set<IpPrefix> getAllRoutePrefixes(LinkProperties lp) {
        final List<RouteInfo> allRoutes = lp.getAllRoutes();
        final Set<IpPrefix> allPrefixes = new ArraySet<>(allRoutes.size());
        for (RouteInfo routeInfo : allRoutes) {
            allPrefixes.add(routeInfo.getDestination());
        }
        return allPrefixes;
    }

    private static Set<IpPrefix> getAllLinkPrefixes(LinkProperties lp) {
        final List<LinkAddress> allLinkAddrs = lp.getAllLinkAddresses();
        final Set<IpPrefix> allPrefixes = new ArraySet<>(allLinkAddrs.size());
        for (LinkAddress linkAddr : allLinkAddrs) {
            allPrefixes.add(new IpPrefix(linkAddr.getAddress(), linkAddr.getPrefixLength()));
        }
        return allPrefixes;
    }

    @Nullable
    private static ThreadNetworkSpecifier getThreadNetworkSpecifier(NsdServiceInfo meshcopInfo) {
        final byte[] xPanId = meshcopInfo.getAttributes().get(KEY_THREAD_EXTENDED_PAN_ID_TXT_NAME);
        if (xPanId == null || xPanId.length < 8) {
            return null;
        }
        return new ThreadNetworkSpecifier.Builder(xPanId).build();
    }

    private static final class StubNetworkInfo {
        public Set<IpPrefix> ailLinkPrefixes = new ArraySet<>();
        public Set<IpPrefix> ailRoutePrefixes = new ArraySet<>();
        public final Set<NetworkSpecifier> stubNetworkSpecifiers = new ArraySet<>();
    }

    /** Resolves stub networks on a single AIL network. */
    private final class StubNetworkResolver {
        private final StubNetworkInfo mStubNetworkInfo;
        private StubNetworkDiscoveryListener mDiscoveryListener;
        private StubNetworkResolveListener mResolveListener;
        private long mDiscoverStopTimeMillis;

        private StubNetworkResolver() {
            mDiscoveryListener = null;
            mResolveListener = null;
            mStubNetworkInfo = new StubNetworkInfo();
        }

        /**
         * Starts mDNS discovery for attached stub networks on the AIL network.
         *
         * This method is called when the AIL becomes available or LinkProperties changes indicate
         * there could be stub networks update.
         *
         * When called, this method clears all already found stub networks, so that a stub network
         * is lost if it can't be discovered via mDNS.
         */
        private void startDiscovery(Network ailNetwork) {
            Log.i(TAG, "Starting stub network discovery on AIL network " + ailNetwork);
            if (mDiscoveryListener != null) {
                stopDiscovery();
            }

            mDiscoveryListener = new StubNetworkDiscoveryListener();
            mNsdManager.discoverServices(
                    THREAD_BORDER_ROUTER_MDNS_SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    ailNetwork,
                    new HandlerExecutor(mHandler),
                    mDiscoveryListener);

            // Schedules a discovery cancellation in 10 seconds. If two discoveries are started at
            // T0 and T0 + 1, the discovery will be eventually cancelled at T0 + 1 + 10 but not
            // T0 + 10.

            mDiscoverStopTimeMillis = SystemClock.uptimeMillis() + MDNS_DISCOVER_TIMEOUT_MILLIS;
            mHandler.postAtTime(
                () -> {
                    if (mDiscoverStopTimeMillis > SystemClock.uptimeMillis()) {
                        return;
                    }
                    stopDiscovery();
                },
                mDiscoverStopTimeMillis);
        }

        private void stopDiscovery() {
            if (mDiscoveryListener != null) {
                mNsdManager.stopServiceDiscovery(mDiscoveryListener);
                mDiscoveryListener = null;
            }

            if (mResolveListener != null) {
                mNsdManager.unregisterServiceInfoCallback(mResolveListener);
                mResolveListener = null;
            }
        }

        private final class StubNetworkDiscoveryListener implements NsdManager.DiscoveryListener {
            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                Log.d(TAG, "Found meshcop service on AIL network: "
                        + serviceInfo.getNetwork() + ", service: " + serviceInfo.getServiceName());
                mResolveListener = new StubNetworkResolveListener();
                mNsdManager.registerServiceInfoCallback(
                    serviceInfo, new HandlerExecutor(mHandler), mResolveListener);
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                // TODO: handle service lost?
            }


            @Override
            public void onDiscoveryStarted(String serviceType) {
                // Do nothing
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                // Do nothing
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                // Do nothing
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                // Do nothing
            }
        }

        private final class StubNetworkResolveListener implements NsdManager.ServiceInfoCallback {
            @Override
            public void onServiceUpdated(NsdServiceInfo serviceInfo) {
                Log.d(TAG, "Resolved meshcop service on AIL network: " + serviceInfo.getNetwork()
                        + ", service: " + serviceInfo.getServiceName());
                Network ailNetwork = serviceInfo.getNetwork();
                ThreadNetworkSpecifier specifier = getThreadNetworkSpecifier(serviceInfo);
                if (ailNetwork == null || specifier == null) {
                    return;
                }

                if (mStubNetworkInfo == null) {
                    return;
                }
                if (mStubNetworkInfo.stubNetworkSpecifiers.add(specifier)) {
                    Log.i(TAG, "New stub network found on AIL network: " + serviceInfo.getNetwork()
                            + ", stubNetwork: " + specifier);
                    mStubNetworkCallback.onStubNetworkChanged(
                            ailNetwork, mStubNetworkInfo.stubNetworkSpecifiers);
                }
            }

            @Override
            public void onServiceLost() {
                // TODO: handle service lost?
            }

            @Override
            public void onServiceInfoCallbackRegistrationFailed(int errorCode) {
                // Do nothing
            }

            @Override
            public void onServiceInfoCallbackUnregistered() {
                // Do nothing
            }
        }
    }
}
