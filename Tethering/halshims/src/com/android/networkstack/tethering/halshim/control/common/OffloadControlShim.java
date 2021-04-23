/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.networkstack.tethering.halshim.control.common;

import android.os.RemoteException;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;

import com.android.networkstack.tethering.halshim.control.v1_0.OffloadCallbackShimImpl;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;

/**
 * Interface used to access API methods in
 * {@code android.hardware.tetheroffload.control.*.IOffloadControl}, with no fallbacks if the
 * methods are not yet part of the released API.
 */
public interface OffloadControlShim {
    String TAG = OffloadControlShim.class.getSimpleName();

    // TODO: Use major-minor version control to prevent from defining new constants.
    int OFFLOAD_HAL_VERSION_NONE = 0;
    int OFFLOAD_HAL_VERSION_1_0 = 1;
    int OFFLOAD_HAL_VERSION_1_1 = 2;
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            OFFLOAD_HAL_VERSION_NONE,
            OFFLOAD_HAL_VERSION_1_0,
            OFFLOAD_HAL_VERSION_1_1
    })
    @interface OffloadHalVersion {}

    /**
     * Get the human-readable string for the given HAL version integer.
     */
    @NonNull
    static String halVerToString(int version) {
        switch(version) {
            case OFFLOAD_HAL_VERSION_1_0:
                return "1.0";
            case OFFLOAD_HAL_VERSION_1_1:
                return "1.1";
            case OFFLOAD_HAL_VERSION_NONE:
                return "None";
            default:
                throw new IllegalArgumentException("Unsupported version int " + version);
        }
    }

    @FunctionalInterface
    interface ResultCallback {
        void onValues(boolean success, String errMsg);
    }

    @FunctionalInterface
    interface ForwardedStatsCallback {
        void onValues(long rxBytes, long txBytes);
    }

    /**
     * Get version int for this object.
     */
    int getVersion();

    /**
     * Initialize the tethering offload HAL.
     */
    void initOffload(OffloadCallbackShimImpl iTetheringOffloadCallback, ResultCallback cb)
            throws RemoteException;

    /** Stop IOffloadControl. */
    void stopOffload(ResultCallback cb) throws RemoteException;

    /** Get Tx/Rx usage from last query. */
    void getForwardedStats(String s, ForwardedStatsCallback cb)
                            throws RemoteException;

    /** Set local prefixes to offload management process. */
    void setLocalPrefixes(ArrayList<String> arrayList, ResultCallback cb)
            throws RemoteException;

    /** Set data limit value to offload management process. */
    void setDataLimit(String s, long l, ResultCallback cb) throws RemoteException;

    /** Set upstream parameters to offload management process. */
    void setUpstreamParameters(String s, String s1, String s2, ArrayList<String> arrayList,
            ResultCallback cb) throws RemoteException;

    /** Add downstream prefix to offload management process. */
    void addDownstream(String s, String s1, ResultCallback cb) throws RemoteException;

    /** Remove downstream prefix from offload management process. */
    void removeDownstream(String s, String s1, ResultCallback cb) throws RemoteException;

    /** Set data warning and limit value to offload management process. */
    default void setDataWarningAndLimit(String iface, long warning, long limit, ResultCallback cb)
            throws RemoteException {
        throw new UnsupportedOperationException();
    }
}
