/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.NonNull;
import android.content.Context;
import android.net.LinkAddress;
import android.net.Network;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Looper;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * MdnsAdvertiser manages advertising services per {@link com.android.server.NsdService} requests.
 */
public class MdnsAdvertiser {
    private static final String TAG = MdnsAdvertiser.class.getSimpleName();
    static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    static final InetSocketAddress IPV6_ADDR = new InetSocketAddress(
            MdnsConstants.getMdnsIPv6Address(), MdnsConstants.MDNS_PORT);
    static final InetSocketAddress IPV4_ADDR = new InetSocketAddress(
                    MdnsConstants.getMdnsIPv4Address(), MdnsConstants.MDNS_PORT);

    private final Looper mLooper;
    private final AdvertiserCallback mCb;

    // Max-sized buffers to be used as temporary buffer to read/build packets. May be used by
    // multiple components, but only for self-contained operations in the looper thread, so not
    // concurrently.
    // TODO: set according to MTU.
    private final byte[] mPacketCreationBuffer = new byte[1300];
    private final byte[] mPacketReaderBuffer = new byte[1300];

    private final MdnsSocketProvider mSocketProvider;
    private final ArrayMap<Network, InterfaceAdvertiserRequest> mInterfaceAdvertisers
            = new ArrayMap<>();
    private final SparseArray<Registration> mRegistrations = new SparseArray<>();

    private final MdnsInterfaceAdvertiser.Callback mInterfaceAdvertiserCb =
            new MdnsInterfaceAdvertiser.Callback() {
        @Override
        public void onRegisterServiceSucceeded(
                @NonNull MdnsInterfaceAdvertiser advertiser, int serviceId) {
            // Wait for all current interfaces to be done probing before notifying of success.
            if (!isDoneProbing(serviceId)) return;
            // The service may still be unregistered/renamed if a conflict is found on a later added
            // interface, or if a conflicting announcement/reply is detected (RFC6762 9.)

            final Registration registration = mRegistrations.get(serviceId);
            if (registration == null) {
                Log.wtf(TAG, "Register succeeded for unknown registration");
                return;
            }
            mCb.onRegisterServiceSucceeded(serviceId, registration.getServiceInfo());
            registration.notifiedRegistrationSuccess = true;
        }

        @Override
        public void onServiceConflict(@NonNull MdnsInterfaceAdvertiser advertiser, int serviceId) {
            if (DBG) {
                Log.v(TAG, "Found conflict, restarted probing for service " + serviceId);
            }

            final Registration registration = mRegistrations.get(serviceId);
            if (registration.notifiedRegistrationSuccess) {
                // Notify that the service is now unregistered due to the conflict
                // TODO: make sure that's the actual callback that gets called here
                mCb.onUnregisterServiceSucceeded(serviceId);
                registration.notifiedRegistrationSuccess = false;

                // The service was done probing, just reset it to probing state (RFC6762 9.)
                for (int i = 0; i < mInterfaceAdvertisers.size(); i++) {
                    final MdnsInterfaceAdvertiser av = mInterfaceAdvertisers.valueAt(i).mAdvertiser;
                    if (av == null) continue;
                    av.restartProbingForConflict(serviceId);
                }
                return;
            }

            final NsdServiceInfo newInfo = registration.updateForConflict();
            for (int i = 0; i < mInterfaceAdvertisers.size(); i++) {
                final MdnsInterfaceAdvertiser av = mInterfaceAdvertisers.valueAt(i).mAdvertiser;
                if (av == null) continue;
                av.renameServiceForConflict(serviceId, newInfo);
            }
        }

        @Override
        public void onDestroyed(@Nullable Network network) {
            mInterfaceAdvertisers.remove(network);
        }
    };

    /**
     * A request for a {@link MdnsInterfaceAdvertiser}.
     *
     * This class tracks services to be advertised in a {@link MdnsRecordRepository} until the
     * advertiser socket is available and the advertiser can be created, and notifies it when the
     * interface is lost or the addresses change after that.
     */
    private class InterfaceAdvertiserRequest implements MdnsSocketProvider.SocketCallback {
        @Nullable
        private final Network mNetwork;
        @NonNull
        private final MdnsRecordRepository mRecordRepository;
        @Nullable
        private MdnsInterfaceAdvertiser mAdvertiser;

        InterfaceAdvertiserRequest(@Nullable Network network) {
            mNetwork = network;
            mRecordRepository = new MdnsRecordRepository(mLooper);
            mSocketProvider.requestSocket(network, this);
        }

        public void addService(int id, NsdServiceInfo info) throws IOException,
                MdnsRecordRepository.NameConflictException {
            if (mAdvertiser == null) {
                mRecordRepository.addService(id, info);
            } else {
                mAdvertiser.addService(id, info);
            }
        }

        public void removeService(int id) {
            if (mAdvertiser == null) {
                mRecordRepository.removeService(id);
            } else {
                mAdvertiser.removeService(id);
            }
        }

        @Override
        public void onSocketCreated(@NonNull Network network,
                @NonNull MdnsInterfaceSocket socket,
                @NonNull List<LinkAddress> addresses) {
            // mNetwork may be different from the received network here: if mNetwork is null,
            // network may be LOCAL_NET_ID.
            mRecordRepository.updateAddresses(addresses);
            mAdvertiser = new MdnsInterfaceAdvertiser(mNetwork, mRecordRepository, socket,
                    mLooper, mPacketCreationBuffer, mInterfaceAdvertiserCb);
            // TODO: set socket packet handler here !
            mAdvertiser.startAllProbing();
        }

        @Override
        public void onInterfaceDestroyed(@NonNull Network network,
                @NonNull MdnsInterfaceSocket socket) {
            if (mAdvertiser != null) mAdvertiser.destroyNow();
        }

        @Override
        public void onAddressesChanged(@NonNull Network network,
                @NonNull List<LinkAddress> addresses) {
            // TODO: restart advertising, but figure out what exit messages need to be sent for the
            // previous addresses
            mRecordRepository.updateAddresses(addresses);
        }
    }

    private static class Registration {
        @NonNull
        final String originalName;
        boolean notifiedRegistrationSuccess;
        private int mConflictCount;
        @NonNull
        private NsdServiceInfo mServiceInfo;

        private Registration(@NonNull NsdServiceInfo serviceInfo) {
            this.originalName = serviceInfo.getServiceName();
            this.mServiceInfo = serviceInfo;
        }

        @NonNull
        private NsdServiceInfo updateForConflict() {
            mConflictCount++;
            // In case of conflict choose a different service name. After the first conflict use
            // "Name (2)", then "Name (3)" etc.
            mServiceInfo = mServiceInfo.withName(originalName + " (" + (mConflictCount + 1) + ")");
            return mServiceInfo;
        }

        @NonNull
        public NsdServiceInfo getServiceInfo() {
            return mServiceInfo;
        }
    }

    /**
     * Callbacks for advertising services.
     *
     * Every method is called on the MdnsAdvertiser looper thread.
     */
    public interface AdvertiserCallback {
        /**
         * Called when a service was successfully registered, after probing.
         *
         * @param serviceId ID of the service provided when registering.
         * @param registeredInfo Registered info, which may be different from the requested info,
         *                       after probing and possibly choosing alternative service names.
         */
        void onRegisterServiceSucceeded(int serviceId, NsdServiceInfo registeredInfo);

        /**
         * Called when service registration failed.
         *
         * @param serviceId ID of the service provided when registering.
         * @param errorCode One of {@code NsdManager.FAILURE_*}
         */
        void onRegisterServiceFailed(int serviceId, int errorCode);

        /**
         * Called when service unregistration succeeded.
         *
         * @param serviceId ID of the service provided when registering.
         */
        void onUnregisterServiceSucceeded(int serviceId);
    }

    public MdnsAdvertiser(@NonNull Context context, @NonNull Looper looper,
            @NonNull MdnsSocketProvider socketProvider, @NonNull AdvertiserCallback cb) {
        mLooper = looper;
        mCb = cb;
        mSocketProvider = socketProvider;
    }

    public void addService(int id, NsdServiceInfo service, Network network) {
        // TODO: check thread
        if (mRegistrations.get(id) != null) {
            Log.e(TAG, "Adding duplicate registration for " + service);
            mCb.onRegisterServiceFailed(id, NsdManager.FAILURE_INTERNAL_ERROR);
            return;
        }

        if (DBG) {
            Log.i(TAG, "Adding service " + service + " with ID " + id + " to network " + network);
        }

        try {
            final Registration registration = new Registration(service);
            while (!tryAddRegistration(id, registration, network)) {
                registration.updateForConflict();
            }

            mRegistrations.put(id, registration);
        } catch (IOException e) {
            Log.e(TAG, "Error adding service " + service, e);
            removeService(id);
            mCb.onRegisterServiceFailed(id, NsdManager.FAILURE_INTERNAL_ERROR);
        }
    }

    private boolean tryAddRegistration(int id, @NonNull Registration registration,
            @Nullable Network network) throws IOException {
        final NsdServiceInfo serviceInfo = registration.getServiceInfo();
        try {
            InterfaceAdvertiserRequest advertiser = mInterfaceAdvertisers.get(network);
            if (advertiser == null) {
                advertiser = new InterfaceAdvertiserRequest(network);
                mInterfaceAdvertisers.put(network, advertiser);
            }
            advertiser.addService(id, serviceInfo);
        } catch (MdnsRecordRepository.NameConflictException e) {
            if (DBG) {
                Log.i(TAG, "Service name conflicts: " +
                        serviceInfo.getServiceName());
            }
            removeService(id);
            return false;
        }

        // When adding a service to a specific network, check that it does not conflict with other
        // registrations advertising on all networks
        final InterfaceAdvertiserRequest allNetworksAdvertiser = mInterfaceAdvertisers.get(null);
        if (network != null && allNetworksAdvertiser != null
                && allNetworksAdvertiser.mRecordRepository.hasConflictingService(serviceInfo)) {
            if (DBG) {
                Log.i(TAG, "Service conflicts with advertisement on all networks: "
                        + serviceInfo.getServiceName());
            }
            removeService(id);
            return false;
        }

        mRegistrations.put(id, registration);
        return true;
    }

    public void removeService(int id) {
        // TODO: check thread
        if (DBG) {
            Log.i(TAG, "Removing service with ID " + id);
        }
        for (int i = mInterfaceAdvertisers.size() - 1; i >= 0; i--) {
            final InterfaceAdvertiserRequest advertiser = mInterfaceAdvertisers.valueAt(i);
            advertiser.removeService(id);
        }
        mRegistrations.remove(id);
    }

    private boolean isDoneProbing(int serviceId) {
        for (int i = 0; i < mInterfaceAdvertisers.size(); i++) {
            final MdnsInterfaceAdvertiser advertiser = mInterfaceAdvertisers.valueAt(i).mAdvertiser;
            if (advertiser == null || !advertiser.isDoneProbing(serviceId)) {
                return false;
            }
        }
        return true;
    }
}
