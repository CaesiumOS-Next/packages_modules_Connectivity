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

import android.net.thread.ActiveOperationalDataset;
import android.net.thread.IScheduleMigrationReceiver;
import android.net.thread.PendingOperationalDataset;
import android.os.RemoteException;

import java.time.Duration;

/** Holds information of a {@link ThreadNetworkControllerService#scheduleMigration} operation. */
final class ScheduleMigrationOperation {
    private final PendingOperationalDataset mPendingOpDataset;
    private final IScheduleMigrationReceiver mReceiver;
    private boolean mIsScheduled;

    public ScheduleMigrationOperation(
            PendingOperationalDataset pendingOpDataset, IScheduleMigrationReceiver receiver) {
        this.mPendingOpDataset = requireNonNull(pendingOpDataset);
        this.mReceiver = requireNonNull(receiver);
        this.mIsScheduled = false;
    }

    public boolean isScheduled() {
        return mIsScheduled;
    }

    public PendingOperationalDataset getPendingOperationalDataset() {
        return mPendingOpDataset;
    }

    public ActiveOperationalDataset getActiveOperationalDataset() {
        return mPendingOpDataset.getActiveOperationalDataset();
    }

    public void onScheduled(Duration delayTimer) {
        mIsScheduled = true;

        try {
            mReceiver.onScheduled(delayTimer.toMillis());
        } catch (RemoteException e) {
            // We can do nothing else if the caller is now dead
        }
    }

    public void onMigrated() {
        try {
            mReceiver.onMigrated();
        } catch (RemoteException e) {
            // We can do nothing else if the caller is now dead
        }
    }

    public void onError(int errorCode, String errorMessage) {
        try {
            mReceiver.onError(errorCode, errorMessage);
        } catch (RemoteException e) {
            // We can do nothing else if the caller is now dead
        }
    }
}
