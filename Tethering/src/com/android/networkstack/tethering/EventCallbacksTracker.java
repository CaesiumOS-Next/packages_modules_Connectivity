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

package com.android.networkstack.tethering;

import static android.net.TetheringManager.TETHER_HARDWARE_OFFLOAD_STOPPED;

import android.net.ITetheringEventCallback;
import android.net.Network;
import android.net.TetherStatesParcel;
import android.net.TetheredClient;
import android.net.TetheringConfigurationParcel;
import android.net.TetheringInterface;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

/** A class that tracking registered tethering event callbacks. */
public class EventCallbacksTracker {
    private final RemoteCallbackList<ITetheringEventCallback> mTetheringEventCallbacks =
            new RemoteCallbackList<>();
    @NonNull private final TetherStatesParcel mTetherStatesParcel;

    private volatile int mOffloadStatus = TETHER_HARDWARE_OFFLOAD_STOPPED;
    // AIDL doesn't support Set<Integer>. Maintain a int bitmap here. When the bitmap is passed to
    // TetheringManager, TetheringManager would convert it to a set of Integer types.
    // mSupportedTypeBitmap should always be updated inside tethering internal thread but it may be
    // read from binder thread which called TetheringService directly.
    private volatile long mSupportedTypeBitmap;
    @Nullable private Network mTetherUpstream;
    @NonNull private List<TetheredClient> mTetheredClients = Collections.emptyList();

    public EventCallbacksTracker() {
        mTetherStatesParcel = emptyTetherStatesParcel();
    }

    private TetherStatesParcel emptyTetherStatesParcel() {
        final TetherStatesParcel parcel = new TetherStatesParcel();
        parcel.availableList = new TetheringInterface[0];
        parcel.tetheredList = new TetheringInterface[0];
        parcel.localOnlyList = new TetheringInterface[0];
        parcel.erroredIfaceList = new TetheringInterface[0];
        parcel.lastErrorList = new int[0];

        return parcel;
    }

    /**
     * Cookie added when registering {@link android.net.TetheringManager.TetheringEventCallback}.
     */
    private static class CallbackCookie {
        public final boolean hasListClientsPermission;

        private CallbackCookie(boolean hasListClientsPermission) {
            this.hasListClientsPermission = hasListClientsPermission;
        }
    }

    /** Add tethering event callback. */
    public void add(final ITetheringEventCallback callback, final boolean hasListPermission) {
        mTetheringEventCallbacks.register(callback, new CallbackCookie(hasListPermission));
    }

    /** Remove tethering event callback. */
    public void remove(final ITetheringEventCallback callback) {
        mTetheringEventCallbacks.unregister(callback);
    }

    /** Get supported downstream type bitmap. */
    public long getSupportedTypeBitmap() {
        return mSupportedTypeBitmap;
    }

    /** Notify tethering supported change. */
    public void updateTetheringSupportedChange(final long supportedBitmap) {
        mSupportedTypeBitmap = supportedBitmap;

        final int length = mTetheringEventCallbacks.beginBroadcast();
        try {
            for (int i = 0; i < length; i++) {
                try {
                    mTetheringEventCallbacks.getBroadcastItem(i).onSupportedTetheringTypes(
                            supportedBitmap);
                } catch (RemoteException e) {
                    // Not really very much to do here.
                }
            }
        } finally {
            mTetheringEventCallbacks.finishBroadcast();
        }
    }

    /** Get tethering upstream network. */
    public Network getTetherUpstream() {
        return mTetherUpstream;
    }

    /** Notify tethering upstream change. */
    public void updateUpstreamChanged(final Network network) {
        mTetherUpstream = network;

        final int length = mTetheringEventCallbacks.beginBroadcast();
        try {
            for (int i = 0; i < length; i++) {
                try {
                    mTetheringEventCallbacks.getBroadcastItem(i).onUpstreamChanged(network);
                } catch (RemoteException e) {
                    // Not really very much to do here.
                }
            }
        } finally {
            mTetheringEventCallbacks.finishBroadcast();
        }
    }

    /** Notify tethering configuration change. */
    public void reportConfigurationChanged(TetheringConfigurationParcel config) {
        final int length = mTetheringEventCallbacks.beginBroadcast();
        try {
            for (int i = 0; i < length; i++) {
                try {
                    mTetheringEventCallbacks.getBroadcastItem(i).onConfigurationChanged(config);
                } catch (RemoteException e) {
                    // Not really very much to do here.
                }
            }
        } finally {
            mTetheringEventCallbacks.finishBroadcast();
        }
    }

    /** Notify tethering state change. */
    public void updateTetherStateChanged(
            final List<TetheringInterface> available,
            final List<TetheringInterface> localOnly,
            final List<TetheringInterface> tethered,
            final List<TetheringInterface> errored,
            final List<Integer> lastErrors) {
        mTetherStatesParcel.availableList = available.toArray(new TetheringInterface[0]);
        mTetherStatesParcel.tetheredList = tethered.toArray(new TetheringInterface[0]);
        mTetherStatesParcel.localOnlyList = localOnly.toArray(new TetheringInterface[0]);
        mTetherStatesParcel.erroredIfaceList = errored.toArray(new TetheringInterface[0]);
        mTetherStatesParcel.lastErrorList = new int[lastErrors.size()];
        for (int i = 0; i < lastErrors.size(); i++) {
            mTetherStatesParcel.lastErrorList[i] = lastErrors.get(i);
        }

        final int length = mTetheringEventCallbacks.beginBroadcast();
        try {
            for (int i = 0; i < length; i++) {
                try {
                    mTetheringEventCallbacks.getBroadcastItem(i).onTetherStatesChanged(
                            mTetherStatesParcel);
                } catch (RemoteException e) {
                    // Not really very much to do here.
                }
            }
        } finally {
            mTetheringEventCallbacks.finishBroadcast();
        }
    }

    /** Get mTetherStatesParcel object. */
    public TetherStatesParcel getTetherStatesParcel() {
        return mTetherStatesParcel;
    }

    /** Get connected clinets. */
    public List<TetheredClient> getTetheredClients() {
        return mTetheredClients;
    }

    /** Notify connected clients change. */
    public void updateTetherClientsChanged(List<TetheredClient> clients) {
        mTetheredClients = clients;

        final int length = mTetheringEventCallbacks.beginBroadcast();
        try {
            for (int i = 0; i < length; i++) {
                try {
                    final CallbackCookie cookie =
                            (CallbackCookie) mTetheringEventCallbacks.getBroadcastCookie(i);
                    if (!cookie.hasListClientsPermission) continue;
                    mTetheringEventCallbacks.getBroadcastItem(i).onTetherClientsChanged(clients);
                } catch (RemoteException e) {
                    // Not really very much to do here.
                }
            }
        } finally {
            mTetheringEventCallbacks.finishBroadcast();
        }
    }

    /** Get current offload status. */
    public int getOffloadStatus() {
        return mOffloadStatus;
    }

    /** Notify hardware offload status change. */
    public void updateOffloadStatusChanged(final int status) {
        mOffloadStatus = status;

        final int length = mTetheringEventCallbacks.beginBroadcast();
        try {
            for (int i = 0; i < length; i++) {
                try {
                    mTetheringEventCallbacks.getBroadcastItem(i).onOffloadStatusChanged(status);
                } catch (RemoteException e) {
                    // Not really very much to do here.
                }
            }
        } finally {
            mTetheringEventCallbacks.finishBroadcast();
        }
    }

    /** Get the number of registered callbacks. */
    public int getCallbackCount() {
        return mTetheringEventCallbacks.getRegisteredCallbackCount();
    }
}
