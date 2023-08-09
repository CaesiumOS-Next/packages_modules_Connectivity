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
package com.android.server.remote_auth.ranging;

import static android.content.pm.PackageManager.FEATURE_UWB;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.core.uwb.backend.impl.internal.UwbFeatureFlags;
import androidx.core.uwb.backend.impl.internal.UwbServiceImpl;

import com.android.internal.util.Preconditions;
import com.android.server.remote_auth.ranging.RangingCapabilities.RangingMethod;

/**
 * Class for managing generic device to device ranging.
 *
 * <p>Out-of-band channel for ranging capabilities/parameters exchange is assumed being handled
 * outside of this class.
 */
public class RangingManager {
    private static final String TAG = "RangingManager";

    private Context mContext;
    @NonNull private RangingCapabilities mCachedRangingCapabilities;
    @NonNull private UwbServiceImpl mUwbServiceImpl;

    public RangingManager(@NonNull Context context) {
        mContext = context;
        if (mContext.getPackageManager().hasSystemFeature(FEATURE_UWB)) {
            initiateUwb();
        }
    }

    /** Shutdown and stop all listeners and tasks. */
    public void shutdown() {
        if (mUwbServiceImpl != null) {
            mUwbServiceImpl.shutdown();
        }
        Log.i(TAG, "shutdown");
    }

    /**
     * Gets the {@link RangingCapabilities} of this device.
     *
     * @return RangingCapabilities.
     */
    @NonNull
    public RangingCapabilities getRangingCapabilities() {
        if (mCachedRangingCapabilities == null) {
            RangingCapabilities.Builder builder = new RangingCapabilities.Builder();
            if (mUwbServiceImpl != null) {
                builder.addSupportedRangingMethods(RangingMethod.UWB);
                builder.setUwbRangingCapabilities(mUwbServiceImpl.getRangingCapabilities());
            }
            mCachedRangingCapabilities = builder.build();
        }
        return mCachedRangingCapabilities;
    }

    /**
     * Creates a {@link RangingSession} based on the given {@link SessionParameters}.
     *
     * @param sessionParameters parameters used to setup the session.
     * @return the created RangingSession. Null if session creation failed.
     * @throws IllegalArgumentException if sessionParameters is invalid.
     */
    @Nullable
    public RangingSession createSession(@NonNull SessionParameters sessionParameters) {
        Preconditions.checkNotNull(sessionParameters, "sessionParameters must not be null");
        switch (sessionParameters.getRangingMethod()) {
            case UWB:
                if (mUwbServiceImpl == null) {
                    Log.w(TAG, "createSession with UWB failed - UWB not supported");
                    break;
                }
                return new UwbRangingSession(mContext, sessionParameters, mUwbServiceImpl);
            case UNKNOWN:
                return null;
        }
        return null;
    }

    /** Initiation required for ranging with UWB. */
    private void initiateUwb() {
        UwbFeatureFlags uwbFeatureFlags =
                new UwbFeatureFlags.Builder()
                        .setSkipRangingCapabilitiesCheck(
                                Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2)
                        .setReversedByteOrderFiraParams(
                                Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU)
                        .build();
        mUwbServiceImpl = new UwbServiceImpl(mContext, uwbFeatureFlags);
        Log.i(TAG, "RangingManager initiateUwb complete");
    }
}
