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
import android.net.nsd.NsdServiceInfo;
import android.os.Looper;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
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
    private final byte[] mPacketCreationBuffer = new byte[1500];
    private final byte[] mPacketReaderBuffer = new byte[1500];

    private final ArrayMap<NetworkInterface, MdnsInterfaceAdvertiser> mInterfaceAdvertisers
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
            mCb.onRegisterServiceSucceeded(serviceId, registration.serviceInfo);
        }

        @Override
        public void onServiceConflict(@NonNull MdnsInterfaceAdvertiser advertiser, int serviceId) {
            if (DBG) {
                Log.v(TAG, "Found conflict, restarted probing for service " + serviceId);
            }

            final Registration registration = mRegistrations.get(serviceId);
            if (registration.notifiedRegistrationSuccess) {
                // TODO: make sure that's the actual callback that gets called here
                mCb.onUnregisterServiceSucceeded(serviceId);

                // If the service was done probing, just reset it to probing state (RFC6762 9.)
                for (int i = 0; i < mInterfaceAdvertisers.size(); i++) {
                    mInterfaceAdvertisers.valueAt(i).restartProbing(serviceId);
                }
                return;
            }

            final NsdServiceInfo newInfo = registration.updateForConflict();
            for (int i = 0; i < mInterfaceAdvertisers.size(); i++) {
                mInterfaceAdvertisers.valueAt(i).renameServiceForConflict(serviceId, newInfo);
            }
        }
    };

    private static class Registration {
        @NonNull
        final String originalName;
        @NonNull
        NsdServiceInfo serviceInfo;
        boolean notifiedRegistrationSuccess;
        private int mConflictCount;

        private Registration(@NonNull NsdServiceInfo serviceInfo) {
            this.originalName = serviceInfo.getServiceName();
            this.serviceInfo = serviceInfo;
        }

        @NonNull
        private NsdServiceInfo updateForConflict() {
            mConflictCount++;
            // In case of conflict choose a different service name. After the first conflict use
            // "Name (2)", then "Name (3)" etc.
            serviceInfo = serviceInfo.withName(originalName + " (" + (mConflictCount + 1) + ")");
            return serviceInfo;
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

    public MdnsAdvertiser(Looper looper, AdvertiserCallback cb) {
        mLooper = looper;
        mCb = cb;
    }

    // NsdService should work with Network objects and file a NetworkCallback to get LinkProperties
    // updates. It sends services to advertise down to MdnsAdvertiser after resolving the interface
    // that goes with the Network.
    public void addService(int id, NsdServiceInfo service, List<NetworkInterface> ifaces) {
        // TODO: check thread
        if (DBG) {
            Log.i(TAG, "Adding service " + service + " with ID " + id + " to interfaces " + ifaces);
        }
        try {
            for (NetworkInterface iface : ifaces) {
                MdnsInterfaceAdvertiser advertiser = mInterfaceAdvertisers.get(iface);
                if (advertiser == null) {
                    advertiser = new MdnsInterfaceAdvertiser(iface, mLooper,
                            mPacketReaderBuffer, mPacketCreationBuffer, mInterfaceAdvertiserCb);
                    mInterfaceAdvertisers.put(iface, advertiser);
                }
                advertiser.addService(id, service);
            }

            mRegistrations.put(id, new Registration(service));
        } catch (IOException e) {
            Log.e(TAG, "Error adding service " + service, e);
            removeService(id);
        }
    }

    public void removeService(int id) {
        // TODO: check thread
        if (DBG) {
            Log.i(TAG, "Removing service with ID " + id);
        }
        for (int i = mInterfaceAdvertisers.size() - 1; i >= 0; i--) {
            final MdnsInterfaceAdvertiser advertiser = mInterfaceAdvertisers.valueAt(i);
            if (advertiser.removeServiceAndMaybeDestroy(id)) {
                mInterfaceAdvertisers.removeAt(i);
            }
        }
        mRegistrations.remove(id);
    }

    private boolean isDoneProbing(int serviceId) {
        for (int i = 0; i < mInterfaceAdvertisers.size(); i++) {
            if (!mInterfaceAdvertisers.valueAt(i).isDoneProbing(serviceId)) {
                return false;
            }
        }
        return true;
    }
}
