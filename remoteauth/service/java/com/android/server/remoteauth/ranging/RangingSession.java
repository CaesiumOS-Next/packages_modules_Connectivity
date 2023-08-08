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

import android.annotation.NonNull;
import android.content.Context;
import android.util.Log;

import com.android.internal.util.Preconditions;
import com.android.server.remote_auth.util.Crypto;

import com.google.common.hash.Hashing;

/** Abstract class for managing a ranging session. */
public abstract class RangingSession {
    private static final String TAG = "RangingSession";

    /** Types of ranging error. */
    public enum RangingError {
        UNKNOWN,
        INVALID_PARAMETERS,
        STOPPED_BY_REQUEST,
        STOPPED_BY_PEER,
        FAILED_TO_START,
        FAILED_TO_STOP,
        SYSTEM_ERROR,
        SYSTEM_TIMEOUT
    }

    /** Interface for ranging update callbacks. */
    public interface RangingCallback {
        /**
         * Call upon new {@link RangingReport}.
         *
         * @param sessionInfo info about this ranging session.
         * @param rangingReport new ranging report
         */
        void onRangingReport(
                @NonNull SessionInfo sessionInfo, @NonNull RangingReport rangingReport);

        /**
         * Call upon any ranging error events.
         *
         * @param sessionInfo info about this ranging session.
         * @param rangingError error type
         */
        void onError(@NonNull SessionInfo sessionInfo, @NonNull RangingError rangingError);
    }

    protected Context mContext;
    protected SessionInfo mSessionInfo;
    protected float mLowerProximityBoundaryM;
    protected float mUpperProximityBoundaryM;
    protected boolean mAutoDeriveParams;
    protected byte[] mBaseKey;
    protected byte[] mSyncData;
    protected int mSyncCounter;
    protected byte[] mDerivedData;
    protected int mDerivedDataLength;

    protected RangingSession(
            @NonNull Context context,
            @NonNull SessionParameters sessionParameters,
            int derivedDataLength) {
        Preconditions.checkNotNull(context);
        Preconditions.checkNotNull(sessionParameters);
        mContext = context;
        mSessionInfo =
                new SessionInfo.Builder()
                        .setDeviceId(sessionParameters.getDeviceId())
                        .setRangingMethod(sessionParameters.getRangingMethod())
                        .build();
        mLowerProximityBoundaryM = sessionParameters.getLowerProximityBoundaryM();
        mUpperProximityBoundaryM = sessionParameters.getUpperProximityBoundaryM();
        mAutoDeriveParams = sessionParameters.getAutoDeriveParams();
        Log.i(
                TAG,
                "Creating a new RangingSession {info = "
                        + mSessionInfo
                        + ", autoDeriveParams = "
                        + mAutoDeriveParams
                        + "}");
        if (mAutoDeriveParams) {
            Preconditions.checkArgument(
                    derivedDataLength > 0, "derivedDataLength must be greater than 0");
            mDerivedDataLength = derivedDataLength;
            resetBaseKey(sessionParameters.getBaseKey());
            resetSyncData(sessionParameters.getSyncData());
        }
    }

    /**
     * Starts ranging based on the given {@link RangingParameters}.
     *
     * @param rangingParameters parameters to start the ranging.
     * @param rangingCallback callback to notify of ranging events.
     * @throws NullPointerException if params are null.
     * @throws IllegalArgumentException if rangingParameters is invalid.
     */
    public abstract void start(
            @NonNull RangingParameters rangingParameters, @NonNull RangingCallback rangingCallback);

    /** Stops ranging */
    public abstract void stop();

    /**
     * Resets the base key that's used to derive all necessary ranging parameters.
     *
     * @param baseKey new baseKey must be 16 or 32 bytes.
     * @throws NullPointerException if baseKey is null.
     * @throws IllegalArgumentException if baseKey has invalid length.
     */
    public void resetBaseKey(@NonNull byte[] baseKey) {
        if (!mAutoDeriveParams) {
            Log.w(TAG, "autoDeriveParams is disabled, new baseKey is ignored.");
            return;
        }
        Preconditions.checkNotNull(baseKey);
        if (baseKey.length != 16 && baseKey.length != 32) {
            throw new IllegalArgumentException("Invalid baseKey length: " + baseKey.length);
        }
        mBaseKey = baseKey;
        updateDerivedData();
        Log.i(TAG, "resetBaseKey");
    }

    /**
     * Resets the synchronization by giving a new sync data used for derivation.
     *
     * @param syncData new syncData must be 16 bytes.
     * @throws NullPointerException if baseKey is null.
     * @throws IllegalArgumentException if syncData has invalid length.
     */
    public void resetSyncData(@NonNull byte[] syncData) {
        if (!mAutoDeriveParams) {
            Log.w(TAG, "autoDeriveParams is disabled, new syncData is ignored.");
            return;
        }
        Preconditions.checkNotNull(syncData);
        if (syncData.length != 16) {
            throw new IllegalArgumentException("Invalid syncData length: " + syncData.length);
        }
        mSyncData = syncData;
        mSyncCounter = 0;
        updateDerivedData();
        Log.i(TAG, "resetSyncData");
    }

    /** Update mDerivedData */
    protected boolean updateDerivedData() {
        if (!mAutoDeriveParams) {
            Log.w(TAG, "autoDeriveParams is disabled, updateDerivedData is skipped.");
            return false;
        }
        if (mBaseKey == null
                || mBaseKey.length == 0
                || mSyncData == null
                || mSyncData.length == 0) {
            Log.w(TAG, "updateDerivedData: Missing baseKey/syncData");
            return false;
        }
        byte[] hashedSyncData =
                Hashing.sha256()
                        .newHasher()
                        .putBytes(mSyncData)
                        .putInt(mSyncCounter)
                        .hash()
                        .asBytes();
        byte[] newDerivedData = Crypto.computeHkdf(mBaseKey, hashedSyncData, mDerivedDataLength);
        if (newDerivedData == null) {
            Log.w(TAG, "updateDerivedData: computeHkdf failed");
            return false;
        }
        mDerivedData = newDerivedData;
        mSyncCounter++;
        Log.i(TAG, "updateDerivedData");
        return true;
    }
}
