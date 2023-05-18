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

import static com.android.server.connectivity.mdns.util.MdnsUtils.SocketKey;
import static com.android.server.connectivity.mdns.util.MdnsUtils.ensureRunningOnHandlerThread;
import static com.android.server.connectivity.mdns.util.MdnsUtils.isRunningOnHandlerThread;

import android.Manifest.permission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.os.Handler;
import android.os.Looper;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.SharedLog;
import com.android.server.connectivity.mdns.util.MdnsUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * This class keeps tracking the set of registered {@link MdnsServiceBrowserListener} instances, and
 * notify them when a mDNS service instance is found, updated, or removed?
 */
public class MdnsDiscoveryManager implements MdnsSocketClientBase.Callback {
    private static final String TAG = MdnsDiscoveryManager.class.getSimpleName();
    public static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    private final ExecutorProvider executorProvider;
    private final MdnsSocketClientBase socketClient;
    @NonNull private final SharedLog sharedLog;

    @NonNull private final PerSocketServiceTypeClients perSocketServiceTypeClients;
    @NonNull private final Handler handler;

    private static class PerSocketServiceTypeClients {
        private final ArrayMap<Pair<String, SocketKey>, MdnsServiceTypeClient> clients =
                new ArrayMap<>();

        public void put(@NonNull String serviceType, @NonNull SocketKey socketKey,
                @NonNull MdnsServiceTypeClient client) {
            final String dnsLowerServiceType = MdnsUtils.toDnsLowerCase(serviceType);
            final Pair<String, SocketKey> perSocketServiceType = new Pair<>(dnsLowerServiceType,
                    socketKey);
            clients.put(perSocketServiceType, client);
        }

        @Nullable
        public MdnsServiceTypeClient get(
                @NonNull String serviceType, @NonNull SocketKey socketKey) {
            final String dnsLowerServiceType = MdnsUtils.toDnsLowerCase(serviceType);
            final Pair<String, SocketKey> perSocketServiceType = new Pair<>(dnsLowerServiceType,
                    socketKey);
            return clients.getOrDefault(perSocketServiceType, null);
        }

        public List<MdnsServiceTypeClient> getByServiceType(@NonNull String serviceType) {
            final String dnsLowerServiceType = MdnsUtils.toDnsLowerCase(serviceType);
            final List<MdnsServiceTypeClient> list = new ArrayList<>();
            for (int i = 0; i < clients.size(); i++) {
                final Pair<String, SocketKey> perSocketServiceType = clients.keyAt(i);
                if (dnsLowerServiceType.equals(perSocketServiceType.first)) {
                    list.add(clients.valueAt(i));
                }
            }
            return list;
        }

        public List<MdnsServiceTypeClient> getBySocketKey(@NonNull SocketKey socketKey) {
            final List<MdnsServiceTypeClient> list = new ArrayList<>();
            for (int i = 0; i < clients.size(); i++) {
                final Pair<String, SocketKey> perSocketServiceType = clients.keyAt(i);
                if (socketKey.equals(perSocketServiceType.second)) {
                    list.add(clients.valueAt(i));
                }
            }
            return list;
        }

        public void remove(@NonNull MdnsServiceTypeClient client) {
            final int index = clients.indexOfValue(client);
            clients.removeAt(index);
        }

        public boolean isEmpty() {
            return clients.isEmpty();
        }
    }

    public MdnsDiscoveryManager(@NonNull ExecutorProvider executorProvider,
            @NonNull MdnsSocketClientBase socketClient, @NonNull SharedLog sharedLog,
            @NonNull Looper looper) {
        this.executorProvider = executorProvider;
        this.socketClient = socketClient;
        this.sharedLog = sharedLog;
        perSocketServiceTypeClients = new PerSocketServiceTypeClients();
        handler = new Handler(looper);
    }

    private void checkAndRunOnHandlerThread(@NonNull Runnable function) {
        if (isRunningOnHandlerThread(handler)) {
            function.run();
        } else {
            handler.post(function);
        }
    }

    /**
     * Starts (or continue) to discovery mDNS services with given {@code serviceType}, and registers
     * {@code listener} for receiving mDNS service discovery responses.
     *
     * @param serviceType   The type of the service to discover.
     * @param listener      The {@link MdnsServiceBrowserListener} listener.
     * @param searchOptions The {@link MdnsSearchOptions} to be used for discovering {@code
     *                      serviceType}.
     */
    @RequiresPermission(permission.CHANGE_WIFI_MULTICAST_STATE)
    public void registerListener(
            @NonNull String serviceType,
            @NonNull MdnsServiceBrowserListener listener,
            @NonNull MdnsSearchOptions searchOptions) {
        sharedLog.i("Registering listener for serviceType: " + serviceType);
        checkAndRunOnHandlerThread(() ->
                handleRegisterListener(serviceType, listener, searchOptions));
    }

    private void handleRegisterListener(
            @NonNull String serviceType,
            @NonNull MdnsServiceBrowserListener listener,
            @NonNull MdnsSearchOptions searchOptions) {
        if (perSocketServiceTypeClients.isEmpty()) {
            // First listener. Starts the socket client.
            try {
                socketClient.startDiscovery();
            } catch (IOException e) {
                sharedLog.e("Failed to start discover.", e);
                return;
            }
        }
        // Request the network for discovery.
        socketClient.notifyNetworkRequested(listener, searchOptions.getNetwork(),
                new MdnsSocketClientBase.SocketCreationCallback() {
                    @Override
                    public void onSocketCreated(@NonNull SocketKey socketKey) {
                        ensureRunningOnHandlerThread(handler);
                        // All listeners of the same service types shares the same
                        // MdnsServiceTypeClient.
                        MdnsServiceTypeClient serviceTypeClient =
                                perSocketServiceTypeClients.get(serviceType, socketKey);
                        if (serviceTypeClient == null) {
                            serviceTypeClient = createServiceTypeClient(serviceType, socketKey);
                            perSocketServiceTypeClients.put(serviceType, socketKey,
                                    serviceTypeClient);
                        }
                        serviceTypeClient.startSendAndReceive(listener, searchOptions);
                    }

                    @Override
                    public void onAllSocketsDestroyed(@NonNull SocketKey socketKey) {
                        ensureRunningOnHandlerThread(handler);
                        final MdnsServiceTypeClient serviceTypeClient =
                                perSocketServiceTypeClients.get(serviceType, socketKey);
                        if (serviceTypeClient == null) return;
                        // Notify all listeners that all services are removed from this socket.
                        serviceTypeClient.notifySocketDestroyed();
                        perSocketServiceTypeClients.remove(serviceTypeClient);
                    }
                });
    }

    /**
     * Unregister {@code listener} for receiving mDNS service discovery responses. IF no listener is
     * registered for the given service type, stops discovery for the service type.
     *
     * @param serviceType The type of the service to discover.
     * @param listener    The {@link MdnsServiceBrowserListener} listener.
     */
    @RequiresPermission(permission.CHANGE_WIFI_MULTICAST_STATE)
    public void unregisterListener(
            @NonNull String serviceType, @NonNull MdnsServiceBrowserListener listener) {
        sharedLog.i("Unregistering listener for serviceType:" + serviceType);
        checkAndRunOnHandlerThread(() -> handleUnregisterListener(serviceType, listener));
    }

    private void handleUnregisterListener(
            @NonNull String serviceType, @NonNull MdnsServiceBrowserListener listener) {
        final List<MdnsServiceTypeClient> serviceTypeClients =
                perSocketServiceTypeClients.getByServiceType(serviceType);
        if (serviceTypeClients.isEmpty()) {
            return;
        }
        for (int i = 0; i < serviceTypeClients.size(); i++) {
            final MdnsServiceTypeClient serviceTypeClient = serviceTypeClients.get(i);
            if (serviceTypeClient.stopSendAndReceive(listener)) {
                // No listener is registered for the service type anymore, remove it from the list
                // of the service type clients.
                perSocketServiceTypeClients.remove(serviceTypeClient);
            }
        }
        if (perSocketServiceTypeClients.isEmpty()) {
            // No discovery request. Stops the socket client.
            socketClient.stopDiscovery();
        }
        // Unrequested the network.
        socketClient.notifyNetworkUnrequested(listener);
    }

    @Override
    public void onResponseReceived(@NonNull MdnsPacket packet, @NonNull SocketKey socketKey) {
        checkAndRunOnHandlerThread(() ->
                handleOnResponseReceived(packet, socketKey));
    }

    private void handleOnResponseReceived(@NonNull MdnsPacket packet,
            @NonNull SocketKey socketKey) {
        for (MdnsServiceTypeClient serviceTypeClient
                : perSocketServiceTypeClients.getBySocketKey(socketKey)) {
            serviceTypeClient.processResponse(
                    packet, socketKey.getInterfaceIndex(), socketKey.getNetwork());
        }
    }

    @Override
    public void onFailedToParseMdnsResponse(int receivedPacketNumber, int errorCode,
            @NonNull SocketKey socketKey) {
        checkAndRunOnHandlerThread(() ->
                handleOnFailedToParseMdnsResponse(receivedPacketNumber, errorCode, socketKey));
    }

    private void handleOnFailedToParseMdnsResponse(int receivedPacketNumber, int errorCode,
            @NonNull SocketKey socketKey) {
        for (MdnsServiceTypeClient serviceTypeClient
                : perSocketServiceTypeClients.getBySocketKey(socketKey)) {
            serviceTypeClient.onFailedToParseMdnsResponse(receivedPacketNumber, errorCode);
        }
    }

    @VisibleForTesting
    MdnsServiceTypeClient createServiceTypeClient(@NonNull String serviceType,
            @NonNull SocketKey socketKey) {
        sharedLog.log("createServiceTypeClient for type:" + serviceType + " " + socketKey);
        return new MdnsServiceTypeClient(
                serviceType, socketClient,
                executorProvider.newServiceTypeClientSchedulerExecutor(), socketKey,
                sharedLog.forSubComponent(serviceType + "-" + socketKey));
    }
}