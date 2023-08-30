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

package com.android.server.thread;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.net.thread.ActiveOperationalDataset;
import android.net.thread.IOperationReceiver;
import android.os.RemoteException;

/** Holds information of a {@link ThreadNetworkControllerService#attach} operation. */
final class AttachOperation {
    private final boolean mDoForm;
    private final ActiveOperationalDataset mActiveOpDataset;
    private IOperationReceiver mReceiver;

    public AttachOperation(
            boolean doForm,
            @NonNull ActiveOperationalDataset activeOpDataset,
            @NonNull IOperationReceiver receiver) {
        this.mDoForm = doForm;
        this.mActiveOpDataset = requireNonNull(activeOpDataset);

        // FIXME(b/271956296): I have to use `requireNonNull`, not because I want to
        // enforce
        // nullness check but `this.mReceiver` will be assigned to `null` even if
        // `receiver` is
        // non-null. I can't think of any reasons how this could happen, unless JVM is
        // critically
        // broken. It's even more strange that this doesn't happen to
        // `this.mActiveOpDataset` even
        // `requireNonNull` is removed.
        this.mReceiver = requireNonNull(receiver);
    }

    public boolean getDoForm() {
        return mDoForm;
    }

    public ActiveOperationalDataset getActiveOperationalDataset() {
        return mActiveOpDataset;
    }

    public void onSuccess() {
        try {
            mReceiver.onSuccess();
        } catch (RemoteException ignored) {
            // Do nothing if the client is dead
        }
    }

    public void onError(int errorCode, String errorMessage) {
        try {
            mReceiver.onError(errorCode, errorMessage);
        } catch (RemoteException ignored) {
            // Do nothing if the client is dead
        }
    }
}
