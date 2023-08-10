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

package com.google.android.server.remoteauth.connectivity;

//import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.annotation.NonNull;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.CompanionDeviceManager;
import android.content.Context;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Wrapper over CDM implementing connectivity functionalities. */
public class CdmConnectivityModule {
    private static final String TAG = "CdmDiscoveryModule";
    private static final String USE_TRANSPORT =
            "android.Manifest.permission.USE_COMPANION_TRANSPORTS";
    private static final String MANAGE_COMPANION_DEVICE =
            "android.permission.MANAGE_COMPANION_DEVICES";

    private final Context mContext;
    private final CompanionDeviceManager mCompanionDeviceManager;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private boolean mIsConnected = false;
    private ConnectionInfo mConnectionInfo;
    private EventListener mEventListener;

    public CdmConnectivityModule(Context context) {
        mContext = context;
        mCompanionDeviceManager = mContext.getSystemService(CompanionDeviceManager.class);
    }

    /**
     * Method to trigger discovery for CDM connections.
     *
     * @param discoveryFilter to filter for discovered associations.
     * @param discoveredDeviceReceiver callback to be run on discovery result.
     */
    public void startDiscovery(
            @NonNull DiscoveryFilter discoveryFilter,
            @NonNull DiscoveredDeviceReceiver discoveredDeviceReceiver) {
        Preconditions.checkNotNull(mCompanionDeviceManager);

        List<AssociationInfo> associations = mCompanionDeviceManager.getAllAssociations();
        for (AssociationInfo association : associations) {
            if (association
                    .getDeviceProfile()
                    .equals(getDeviceProfileFromType(discoveryFilter.getDeviceType()))) {
                Log.d(TAG, "Device found" + association);
                discoveredDeviceReceiver.onDiscovered(
                        createDiscoveredDeviceFromAssociation(association));
                return;
            }
        }
    }

    /**
     * Method to stop discovery.
     */
    public void stopDiscovery() {
        // Not implemented.
    }

    /**
     * Method to create a connection over CDM association.
     *
     * @param connectionInfo representing the peer device.
     * @param eventListener callback to be called on getting events on this connection.
     * @return {@link Connection} object encapsulating the connection.
     */
    @NonNull
    public Connection connect(
            @NonNull ConnectionInfo connectionInfo, @NonNull EventListener eventListener) {
        Preconditions.checkNotNull(mCompanionDeviceManager);

        mConnectionInfo = connectionInfo;
        mEventListener = eventListener;
        // Setup message listener.
        //        mCompanionDeviceManager.addOnMessageReceivedListener(
        //                mExecutor, CompanionDeviceManager.MESSAGE_REQUEST_REMOTE_AUTHENTICATION,
        //                new CompanionDeviceManager.OnMessageReceivedListener() {
        //                    @Override
        //                    void onMessageReceived(int associationId, byte[] data) {
        //                        handleIncomingMessage(associationId, data);
        //                    }
        //                });

        // Setup transports changed listener.
        //        mCompanionDeviceManager.addOnTransportsChangedListener(
        //                mExecutor, new OnTransportsChangedListener() {
        //                    @Override
        //                    void onTransportsChanged(List<AssociationInfo> associations) {
        //                        handleTransportsChanged(associations);
        //                    }
        //                }
        //        );

        mIsConnected = true;
        return null;
    }

    /**
     * Starts listening for messages from all CDM-associated devices.
     * @param messageReceiver callback to be run when message is received.
     */
    public void startListening(ConnectivityModule.MessageReceiver messageReceiver) {
        // Not implemented.
    }

    /**
     * Stops message listening.
     */
    public void stopListening() {
        // Not implemented.
    }

    /**
     * Helper method to handle changed transports. If the existing device does not exist in the
     * newAssociations, this indicates that the transport is lost.
     *
     * @param newAssociations
     */
    private void handleTransportsChanged(@NonNull List<AssociationInfo> newAssociations) {
        if (newAssociations.isEmpty() && mIsConnected) {
            // All associated transports are removed.
            Log.w(TAG, "All transports are removed!");
            mIsConnected = false;
            return;
        }

        // If previously connected and updated transports has this device -> noop.
        // If previously connected and updated transports does not have this device -> disconnect.
        // If previously not connected and updated transports has this device -> reconnect.
        // If previously not connected and updated changed does not have this device -> noop.
        boolean updatedTransportsHasDevice = updatedTransportsHasDevice(newAssociations);
        if (mIsConnected && !updatedTransportsHasDevice) {
            Log.w(TAG, "Transports changed, device disconnected");
            mIsConnected = false;

        } else if (!mIsConnected && updatedTransportsHasDevice) {
            Log.w(TAG, "Possible reconnect.");
            // TODO(salilr): Initiate reconnect here.
        } else {
            Log.w(TAG, "Transports changed but nothing to do since there was no connection.");
        }
    }

    /**
     * Helper method to get this device from associations.
     *
     * @param associations List of associations.
     * @return true if this device is found in the associations, false otherwise.
     */
    private boolean updatedTransportsHasDevice(@NonNull List<AssociationInfo> newAssociations) {
        int id = mConnectionInfo.getConnectionId();
        for (AssociationInfo association : newAssociations) {
            if (id == association.getId()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Helper method to handle incoming message.
     *
     * @param associationId
     * @param data
     */
    private void handleIncomingMessage(int associationId, byte[] data) {
        if (!mIsConnected || associationId != mConnectionInfo.getConnectionId()) {
            Log.w(
                    TAG,
                    "Message not intended for this association or device not connected, "
                            + "ignoring incoming message.");
            return;
        }

        // TODO(salilr): Handle message.
    }

    /**
     * Method to return the device profile set during association.
     *
     * @param type defined in {@link DiscoveryFilter.DeviceType}
     * @return Device profile string defined in {@link AssociationRequest}.
     * @throws AssertionError if type cannot be mapped.
     */
    private String getDeviceProfileFromType(@DiscoveryFilter.DeviceType int type) {
        switch (type) {
            case DiscoveryFilter.DeviceType.WATCH:
                return AssociationRequest.DEVICE_PROFILE_WATCH;
        }
        throw new AssertionError(type);
    }

    /**
     * Helper method to create a {@link DiscoveredDevice} object from {@AssociationInfo}.
     *
     * @param info Association info.
     * @return DiscoveredDevice object.
     */
    private @NonNull DiscoveredDevice createDiscoveredDeviceFromAssociation(
            @NonNull AssociationInfo info) {
        return DiscoveredDevice.Builder.newInstance()
                .setDisplayName(info.getDisplayName().toString())
                .setConnectionInfo(
                        ConnectionInfo.Builder.newInstance()
                                .setConnectionId(info.getId())
                                .setConnectionType(ConnectionInfo.ConnectionType.BLUETOOTH)
                                .setPeerAddress(info.getDeviceMacAddress().toString())
                                .build())
                .build();
    }
}
