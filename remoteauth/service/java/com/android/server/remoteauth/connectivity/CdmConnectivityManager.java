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

package com.android.server.remoteauth.connectivity;

import static com.android.server.remoteauth.connectivity.DiscoveryFilter.DeviceType;

import android.annotation.NonNull;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/** Discovers devices associated with the companion device manager. */
public class CdmConnectivityManager {
    private static final String TAG = "CdmConnectivityManager";

    private final CompanionDeviceManagerWrapper mCompanionDeviceManagerWrapper;

    private boolean mIsConnected = false;
    private ExecutorService mExecutor;

    public CdmConnectivityManager(
            @NonNull ExecutorService executor,
            @NonNull CompanionDeviceManagerWrapper companionDeviceManagerWrapper) {
        mExecutor = executor;
        mCompanionDeviceManagerWrapper = companionDeviceManagerWrapper;
    }

    /**
     * Runs associated discovery callbacks for discovered devices.
     *
     * @param discoveredDeviceReceiver callback.
     * @param device discovered device.
     */
    private void notifyOnDiscovered(
            @NonNull DiscoveredDeviceReceiver discoveredDeviceReceiver,
            @NonNull DiscoveredDevice device) {
        Log.d(TAG, "Notifying discovered device");
        try {
            mExecutor.execute(
                    () -> {
                        discoveredDeviceReceiver.onDiscovered(device);
                    });
        } catch (RejectedExecutionException e) {
            Log.e(TAG, e.getMessage());
        }
    }

    /**
     * Triggers the discovery for CDM associations.
     *
     * @param discoveryFilter filter for associations.
     * @param discoveredDeviceReceiver callback to be run on discovery result.
     */
    public void startDiscovery(
            @NonNull DiscoveryFilter discoveryFilter,
            @NonNull DiscoveredDeviceReceiver discoveredDeviceReceiver) {
        Preconditions.checkNotNull(mCompanionDeviceManagerWrapper);

        List<AssociationInfo> associations = mCompanionDeviceManagerWrapper.getAllAssociations();
        Log.i(TAG, "salilr: Found associations: " + associations.size());
        for (AssociationInfo association : associations) {
            String deviceProfile = getDeviceProfileFromType(discoveryFilter.getDeviceType());
            if (mCompanionDeviceManagerWrapper.getDeviceProfile(association)
                    .equals(deviceProfile)) {
                notifyOnDiscovered(
                        discoveredDeviceReceiver,
                        createDiscoveredDeviceFromAssociation(association));
            }
        }
    }

    /** Stops discovery. */
    public void stopDiscovery(
            @NonNull DiscoveryFilter discoveryFilter,
            @NonNull DiscoveredDeviceReceiver discoveredDeviceReceiver) {
        // No-op for CDM.
    }

    /**
     * Returns the device profile from association info.
     *
     * @param deviceType Discovery filter device type.
     * @return Device profile string defined in {@link AssociationRequest}.
     * @throws {@link AssertionError} if type cannot be mapped.
     */
    private String getDeviceProfileFromType(@DeviceType int deviceType) {
        switch (deviceType) {
            case DiscoveryFilter.WATCH:
                return AssociationRequest.DEVICE_PROFILE_WATCH;
        }
        // Should not reach here.
        throw new AssertionError(deviceType);
    }

    /**
     * Creates discovered device from association info.
     *
     * @param info Association info.
     * @return discovered device object.
     */
    private @NonNull DiscoveredDevice createDiscoveredDeviceFromAssociation(
            @NonNull AssociationInfo info) {
        return new DiscoveredDevice(
                new CdmConnectionInfo(info.getId(), info),
                info.getDisplayName() == null ? "" : info.getDisplayName().toString());
    }
}
