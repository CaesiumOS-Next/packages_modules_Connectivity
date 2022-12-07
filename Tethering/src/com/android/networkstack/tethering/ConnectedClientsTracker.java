/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.net.TetheringManager.TETHERING_WIFI;

import android.net.MacAddress;
import android.net.TetheredClient;
import android.net.TetheredClient.AddressInfo;
import android.net.ip.IpServer;
import android.net.wifi.WifiClient;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tracker for clients connected to downstreams.
 *
 * <p>This class is not thread safe, it is intended to be used only from the tethering handler
 * thread.
 */
public class ConnectedClientsTracker {
    private final Clock mClock;
    private final List<IpServer> mActiveIpServers;
    private final EventCallbacksTracker mCallbacksTracker;

    @NonNull
    private List<WifiClient> mLastWifiClients = Collections.emptyList();

    @VisibleForTesting
    static class Clock {
        public long elapsedRealtime() {
            return SystemClock.elapsedRealtime();
        }
    }

    public ConnectedClientsTracker(final List<IpServer> ipServers,
            final EventCallbacksTracker tracker) {
        this(new Clock(), ipServers, tracker);
    }

    @VisibleForTesting
    ConnectedClientsTracker(Clock clock, final List<IpServer> ipServers,
            final EventCallbacksTracker tracker) {
        mClock = clock;
        mActiveIpServers = ipServers;
        mCallbacksTracker = tracker;
    }

    /**
     * Update the tracker with new connected clients.
     *
     * <p>The new list can be obtained through {@link #getLastTetheredClients()}.
     * @param wifiClients The list of L2-connected WiFi clients. Null for no change since last
     *                    update.
     */
    public void updateConnectedClients(@Nullable List<WifiClient> wifiClients) {
        final long now = mClock.elapsedRealtime();

        if (wifiClients != null) {
            mLastWifiClients = wifiClients;
        }
        final Set<MacAddress> wifiClientMacs = getClientMacs(mLastWifiClients);

        // Build the list of non-expired leases from all IpServers, grouped by mac address
        final Map<MacAddress, TetheredClient> clientsMap = new HashMap<>();
        for (IpServer server : mActiveIpServers) {
            for (TetheredClient client : server.getAllLeases()) {
                if (client.getTetheringType() == TETHERING_WIFI
                        && !wifiClientMacs.contains(client.getMacAddress())) {
                    // Skip leases of WiFi clients that are not (or no longer) L2-connected
                    continue;
                }
                final TetheredClient prunedClient = pruneExpired(client, now);
                if (prunedClient == null) continue; // All addresses expired

                addLease(clientsMap, prunedClient);
            }
        }

        // TODO: add IPv6 addresses from netlink

        // Add connected WiFi clients that do not have any known address
        for (MacAddress client : wifiClientMacs) {
            if (clientsMap.containsKey(client)) continue;
            clientsMap.put(client, new TetheredClient(
                    client, Collections.emptyList() /* addresses */, TETHERING_WIFI));
        }

        final HashSet<TetheredClient> clients = new HashSet<>(clientsMap.values());
        final List<TetheredClient> lastTetheredClients = getLastTetheredClients();
        final boolean clientsChanged = clients.size() != lastTetheredClients.size()
                || !clients.containsAll(lastTetheredClients);
        if (clientsChanged) {
            mCallbacksTracker.updateTetherClientsChanged(
                    Collections.unmodifiableList(new ArrayList<>(clients)));
        }
    }

    private static void addLease(Map<MacAddress, TetheredClient> clientsMap, TetheredClient lease) {
        final TetheredClient aggregateClient = clientsMap.getOrDefault(
                lease.getMacAddress(), lease);
        if (aggregateClient == lease) {
            // This is the first lease with this mac address
            clientsMap.put(lease.getMacAddress(), lease);
            return;
        }

        // Only add the address info; this assumes that the tethering type is the same when the mac
        // address is the same. If a client is connected through different tethering types with the
        // same mac address, connected clients callbacks will report all of its addresses under only
        // one of these tethering types. This keeps the API simple considering that such a scenario
        // would really be a rare edge case.
        clientsMap.put(lease.getMacAddress(), aggregateClient.addAddresses(lease));
    }

    /**
     * Get the last list of tethered clients, as calculated in {@link #updateConnectedClients}.
     *
     * <p>The returned list is immutable.
     */
    @NonNull
    public List<TetheredClient> getLastTetheredClients() {
        return mCallbacksTracker.getTetheredClients();
    }

    private static boolean hasExpiredAddress(List<AddressInfo> addresses, long now) {
        for (AddressInfo info : addresses) {
            if (info.getExpirationTime() <= now) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static TetheredClient pruneExpired(TetheredClient client, long now) {
        final List<AddressInfo> addresses = client.getAddresses();
        if (addresses.size() == 0) return null;
        if (!hasExpiredAddress(addresses, now)) return client;

        final ArrayList<AddressInfo> newAddrs = new ArrayList<>(addresses.size() - 1);
        for (AddressInfo info : addresses) {
            if (info.getExpirationTime() > now) {
                newAddrs.add(info);
            }
        }

        if (newAddrs.size() == 0) {
            return null;
        }
        return new TetheredClient(client.getMacAddress(), newAddrs, client.getTetheringType());
    }

    @NonNull
    private static Set<MacAddress> getClientMacs(@NonNull List<WifiClient> clients) {
        final Set<MacAddress> macs = new HashSet<>(clients.size());
        for (WifiClient c : clients) {
            macs.add(c.getMacAddress());
        }
        return macs;
    }
}
