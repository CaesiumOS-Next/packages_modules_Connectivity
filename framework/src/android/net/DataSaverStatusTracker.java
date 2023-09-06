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

package android.net;

import android.annotation.NonNull;
import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Build;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Helper class to track data saver status.
 *
 * The class will fetch current data saver status from {@link NetworkPolicyManager} when
 * initialized, and listening for status changed intent to cache the latest status.
 *
 * @hide
 */
@TargetApi(Build.VERSION_CODES.S) // NetworkPolicyManager requires S.
public class DataSaverStatusTracker extends BroadcastReceiver {
    private static final Object sDataSaverStatusTrackerLock = new Object();

    private static volatile DataSaverStatusTracker sInstance;

    /**
     * Gets a static instance of the class.
     *
     * @param context A {@link Context} for initialization.
     * @return The static instance of a {@link DataSaverStatusTracker}.
     */
    public static DataSaverStatusTracker getInstance(@NonNull Context context) {
        if (sInstance == null) {
            synchronized (sDataSaverStatusTrackerLock) {
                if (sInstance == null) {
                    sInstance = new DataSaverStatusTracker(context);
                }
            }
        }
        return sInstance;
    }

    private final NetworkPolicyManager mNpm;
    // The value updates on the caller's binder thread or UI thread.
    private final AtomicBoolean mIsDataSaverEnabled;

    @VisibleForTesting(visibility = Visibility.PACKAGE)
    public DataSaverStatusTracker(final Context context) {
        mNpm = context.getSystemService(NetworkPolicyManager.class);
        final IntentFilter filter = new IntentFilter(
                ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED);
        context.registerReceiver(this, filter);
        mIsDataSaverEnabled = new AtomicBoolean();
        updateDataSaverEnabled();
    }

    // Runs on caller's UI thread.
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction().equals(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED)) {
            updateDataSaverEnabled();
        } else {
            throw new IllegalStateException("Unexpected intent " + intent);
        }
    }

    public boolean getDataSaverEnabled() {
        return mIsDataSaverEnabled.get();
    }

    private void updateDataSaverEnabled() {
        final int dataSaverForCallerUid = mNpm.getRestrictBackgroundStatus(
                Binder.getCallingUid());
        mIsDataSaverEnabled.set(
                dataSaverForCallerUid != ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED);
    }
}
