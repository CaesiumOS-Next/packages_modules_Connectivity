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

package com.android.networkstack.tethering;

import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_ETHERNET;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.IConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.UserHandle;
import android.util.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Simulates upstream switching and sending NetworkCallbacks and CONNECTIVITY_ACTION broadcasts.
 *
 * TODO: this duplicates a fair amount of code from ConnectivityManager and ConnectivityService.
 * Consider using a ConnectivityService object instead, as used in ConnectivityServiceTest.
 *
 * Things to consider:
 * - ConnectivityService uses a real handler for realism, and these test use TestLooper (or even
 *   invoke callbacks directly inline) for determinism. Using a real ConnectivityService would
 *   require adding dispatchAll() calls and migrating to handlers.
 * - ConnectivityService does not provide a way to order CONNECTIVITY_ACTION before or after the
 *   NetworkCallbacks for the same network change. That ability is useful because the upstream
 *   selection code in Tethering is vulnerable to race conditions, due to its reliance on multiple
 *   separate NetworkCallbacks and BroadcastReceivers, each of which trigger different types of
 *   updates. If/when the upstream selection code is refactored to a more level-triggered model
 *   (e.g., with an idempotent function that takes into account all state every time any part of
 *   that state changes), this may become less important or unnecessary.
 */
public class TestConnectivityManager extends ConnectivityManager {
    public Map<NetworkCallback, Handler> allCallbacks = new HashMap<>();
    public Set<NetworkCallback> trackingDefault = new HashSet<>();
    public TestNetworkAgent defaultNetwork = null;
    public Map<NetworkCallback, NetworkRequest> listening = new HashMap<>();
    public Map<NetworkCallback, NetworkRequest> requested = new HashMap<>();
    public Map<NetworkCallback, Integer> legacyTypeMap = new HashMap<>();

    private final NetworkRequest mDefaultRequest;
    private final Context mContext;

    private int mNetworkId = 100;

    public TestConnectivityManager(Context ctx, IConnectivityManager svc,
            NetworkRequest defaultRequest) {
        super(ctx, svc);
        mContext = ctx;
        mDefaultRequest = defaultRequest;
    }

    boolean hasNoCallbacks() {
        return allCallbacks.isEmpty()
                && trackingDefault.isEmpty()
                && listening.isEmpty()
                && requested.isEmpty()
                && legacyTypeMap.isEmpty();
    }

    boolean onlyHasDefaultCallbacks() {
        return (allCallbacks.size() == 1)
                && (trackingDefault.size() == 1)
                && listening.isEmpty()
                && requested.isEmpty()
                && legacyTypeMap.isEmpty();
    }

    boolean isListeningForAll() {
        final NetworkCapabilities empty = new NetworkCapabilities();
        empty.clearAll();

        for (NetworkRequest req : listening.values()) {
            if (req.networkCapabilities.equalRequestableCapabilities(empty)) {
                return true;
            }
        }
        return false;
    }

    int getNetworkId() {
        return ++mNetworkId;
    }

    void makeDefaultNetwork(TestNetworkAgent agent) {
        if (Objects.equals(defaultNetwork, agent)) return;

        final TestNetworkAgent formerDefault = defaultNetwork;
        defaultNetwork = agent;

        if (formerDefault != null) {
            sendConnectivityAction(formerDefault.legacyType, false /* connected */);
        }
        if (defaultNetwork != null) {
            sendConnectivityAction(defaultNetwork.legacyType, true /* connected */);
        }

        for (NetworkCallback cb : trackingDefault) {
            if (defaultNetwork != null) {
                cb.onAvailable(defaultNetwork.networkId);
                cb.onCapabilitiesChanged(
                        defaultNetwork.networkId, defaultNetwork.networkCapabilities);
                cb.onLinkPropertiesChanged(
                        defaultNetwork.networkId, defaultNetwork.linkProperties);
            }
        }
    }

    @Override
    public void requestNetwork(NetworkRequest req, NetworkCallback cb, Handler h) {
        assertFalse(allCallbacks.containsKey(cb));
        allCallbacks.put(cb, h);
        if (mDefaultRequest.equals(req)) {
            assertFalse(trackingDefault.contains(cb));
            trackingDefault.add(cb);
        } else {
            assertFalse(requested.containsKey(cb));
            requested.put(cb, req);
        }
    }

    @Override
    public void requestNetwork(NetworkRequest req, NetworkCallback cb) {
        fail("Should never be called.");
    }

    @Override
    public void requestNetwork(NetworkRequest req,
            int timeoutMs, int legacyType, Handler h, NetworkCallback cb) {
        assertFalse(allCallbacks.containsKey(cb));
        allCallbacks.put(cb, h);
        assertFalse(requested.containsKey(cb));
        requested.put(cb, req);
        assertFalse(legacyTypeMap.containsKey(cb));
        if (legacyType != ConnectivityManager.TYPE_NONE) {
            legacyTypeMap.put(cb, legacyType);
        }
    }

    @Override
    public void registerNetworkCallback(NetworkRequest req, NetworkCallback cb, Handler h) {
        assertFalse(allCallbacks.containsKey(cb));
        allCallbacks.put(cb, h);
        assertFalse(listening.containsKey(cb));
        listening.put(cb, req);
    }

    @Override
    public void registerNetworkCallback(NetworkRequest req, NetworkCallback cb) {
        fail("Should never be called.");
    }

    @Override
    public void registerDefaultNetworkCallback(NetworkCallback cb, Handler h) {
        fail("Should never be called.");
    }

    @Override
    public void registerDefaultNetworkCallback(NetworkCallback cb) {
        fail("Should never be called.");
    }

    @Override
    public void unregisterNetworkCallback(NetworkCallback cb) {
        if (trackingDefault.contains(cb)) {
            trackingDefault.remove(cb);
        } else if (listening.containsKey(cb)) {
            listening.remove(cb);
        } else if (requested.containsKey(cb)) {
            requested.remove(cb);
            legacyTypeMap.remove(cb);
        } else {
            fail("Unexpected callback removed");
        }
        allCallbacks.remove(cb);

        assertFalse(allCallbacks.containsKey(cb));
        assertFalse(trackingDefault.contains(cb));
        assertFalse(listening.containsKey(cb));
        assertFalse(requested.containsKey(cb));
    }

    private void sendConnectivityAction(int type, boolean connected) {
        NetworkInfo ni = new NetworkInfo(type, 0 /* subtype */,  getNetworkTypeName(type),
                "" /* subtypeName */);
        NetworkInfo.DetailedState state = connected
                ? NetworkInfo.DetailedState.CONNECTED
                : NetworkInfo.DetailedState.DISCONNECTED;
        ni.setDetailedState(state, "" /* reason */, "" /* extraInfo */);
        Intent intent = new Intent(CONNECTIVITY_ACTION);
        intent.putExtra(EXTRA_NETWORK_INFO, ni);
Log.d("TetheringTest", "Sending CONNECTIVITY_ACTION: " + mContext + " " + intent.getExtras());
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    public static class TestNetworkAgent {
        public final TestConnectivityManager cm;
        public final Network networkId;
        public final NetworkCapabilities networkCapabilities;
        public final LinkProperties linkProperties;
        // TODO: delete when tethering no longer uses CONNECTIVITY_ACTION.
        public final int legacyType;

        public TestNetworkAgent(TestConnectivityManager cm, int transportType) {
            this.cm = cm;
            this.networkId = new Network(cm.getNetworkId());
            networkCapabilities = new NetworkCapabilities();
            networkCapabilities.addTransportType(transportType);
            networkCapabilities.addCapability(NET_CAPABILITY_INTERNET);
            linkProperties = new LinkProperties();
            legacyType = transportToLegacyType(transportType);
        }

        public TestNetworkAgent(TestConnectivityManager cm, UpstreamNetworkState state) {
            this.cm = cm;
            networkId = state.network;
            networkCapabilities = state.networkCapabilities;
            linkProperties = state.linkProperties;
            this.legacyType = transportToLegacyType(networkCapabilities.getTransportTypes()[0]);
        }

        private static int transportToLegacyType(int transportType) {
            switch (transportType) {
                case TRANSPORT_CELLULAR: return TYPE_MOBILE;
                case TRANSPORT_WIFI: return TYPE_WIFI;
                case TRANSPORT_BLUETOOTH: return TYPE_BLUETOOTH;
                case TRANSPORT_ETHERNET: return TYPE_ETHERNET;
                default: throw new IllegalArgumentException(("Unknown transport") + transportType);
            }
        }

        private boolean matchesLegacyType(int legacyType) {
            if (legacyType == TYPE_NONE) return false;
            final NetworkCapabilities nc = cm.networkCapabilitiesForType(legacyType);
            return nc.satisfiedByNetworkCapabilities(networkCapabilities);
        }

        public void fakeConnect() {
            for (NetworkRequest request : cm.requested.values()) {
                if (matchesLegacyType(request.legacyType)) {
                    cm.sendConnectivityAction(legacyType, true /* connected */);
                    // In practice, a given network can match only one legacy type.
                    break;
                }
            }
            for (NetworkCallback cb : cm.listening.keySet()) {
                cb.onAvailable(networkId);
                cb.onCapabilitiesChanged(networkId, copy(networkCapabilities));
                cb.onLinkPropertiesChanged(networkId, copy(linkProperties));
            }
        }

        public void fakeDisconnect() {
            for (NetworkRequest request : cm.requested.values()) {
                if (matchesLegacyType(request.legacyType)) {
                    cm.sendConnectivityAction(legacyType, false /* connected */);
                    break;
                }
            }
            for (NetworkCallback cb : cm.listening.keySet()) {
                cb.onLost(networkId);
            }
        }

        public void sendLinkProperties() {
            for (NetworkCallback cb : cm.listening.keySet()) {
                cb.onLinkPropertiesChanged(networkId, copy(linkProperties));
            }
        }

        @Override
        public String toString() {
            return String.format("TestNetworkAgent: %s %s", networkId, networkCapabilities);
        }
    }

    static NetworkCapabilities copy(NetworkCapabilities nc) {
        return new NetworkCapabilities(nc);
    }

    static LinkProperties copy(LinkProperties lp) {
        return new LinkProperties(lp);
    }
}
