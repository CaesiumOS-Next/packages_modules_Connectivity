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

package com.android.networkstack.tethering.metrics;

import static android.net.TetheringManager.TETHERING_BLUETOOTH;
import static android.net.TetheringManager.TETHERING_ETHERNET;
import static android.net.TetheringManager.TETHERING_NCM;
import static android.net.TetheringManager.TETHERING_USB;
import static android.net.TetheringManager.TETHERING_WIFI;
import static android.net.TetheringManager.TETHERING_WIFI_P2P;
import static android.net.TetheringManager.TETHER_ERROR_DHCPSERVER_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_DISABLE_FORWARDING_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_ENABLE_FORWARDING_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_ENTITLEMENT_UNKNOWN;
import static android.net.TetheringManager.TETHER_ERROR_IFACE_CFG_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_INTERNAL_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_NO_ACCESS_TETHERING_PERMISSION;
import static android.net.TetheringManager.TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION;
import static android.net.TetheringManager.TETHER_ERROR_NO_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_PROVISIONING_FAILED;
import static android.net.TetheringManager.TETHER_ERROR_SERVICE_UNAVAIL;
import static android.net.TetheringManager.TETHER_ERROR_TETHER_IFACE_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_UNAVAIL_IFACE;
import static android.net.TetheringManager.TETHER_ERROR_UNKNOWN_IFACE;
import static android.net.TetheringManager.TETHER_ERROR_UNSUPPORTED;
import static android.net.TetheringManager.TETHER_ERROR_UNTETHER_IFACE_ERROR;

import android.stats.connectivity.DownstreamType;
import android.stats.connectivity.ErrorCode;
import android.stats.connectivity.UpstreamType;
import android.stats.connectivity.UserType;
import android.util.Log;
import android.util.SparseArray;

/**
 * Collection of utilities for tethering metrics.
 *
 * To see if the logs are properly sent to statsd, execute following commands
 *
 * $ adb shell cmd stats print-logs
 * $ adb logcat | grep statsd OR $ adb logcat -b stats
 *
 * @hide
 */
public class TetheringMetrics {
    private static final String TAG = TetheringMetrics.class.getSimpleName();
    private static final boolean DBG = false;
    private static final String SETTINGS_PKG_NAME = "com.android.settings";
    private static final String SYSTEMUI_PKG_NAME = "com.android.systemui";
    private static final String GMS_PKG_NAME = "com.google.android.gms";

    private final NetworkTetheringReported.Builder mStatsBuilder =
            NetworkTetheringReported.newBuilder();
    private final SparseArray<StatsLog> mStatsMap = new SparseArray<>();
    private class StatsLog {
        private final UserType mUserType;
        private final DownstreamType mDownstreamType;
        private ErrorCode mErrorCode;
        StatsLog(final String callerPkg, final int type) {
            mUserType = userTypeToEnum(callerPkg);
            mDownstreamType = downstreamTypeToEnum(type);
            mErrorCode = null;
        }

        public void setErrorCode(final int errCode) {
            mErrorCode = errorCodeToEnum(errCode);
        }
        public boolean isReadyToWrite() {
            return mErrorCode != null;
        }
        public NetworkTetheringReported getNetworkTetheringReported() {
            return mStatsBuilder.setDownstreamType(mDownstreamType)
                    .setErrorCode(mErrorCode)
                    .setUserType(mUserType)
                    .setUpstreamType(UpstreamType.UT_UNKNOWN)
                    .build();
        }
    }
    /** Update Tethering stats about package name of caller and downstream type */
    public void createStats(final String callerPkg, final int downstreamType) {
        mStatsMap.put(downstreamType, new StatsLog(callerPkg, downstreamType));
    }
    /** Remove Tethering stats. If stats is ready to write then write it before removing */
    public void writeStats(final int downstreamType) {
        final StatsLog stats = mStatsMap.get(downstreamType);
        if (stats == null || !stats.isReadyToWrite()) return;

        final NetworkTetheringReported statsReported = stats.getNetworkTetheringReported();
        write(statsReported.getErrorCode(), statsReported.getDownstreamType(),
                statsReported.getUpstreamType(), statsReported.getUserType());
        mStatsMap.remove(downstreamType);
    }
    /** Update error code of downstreamType */
    public void updateErrorCode(final int errCode, final int downstreamType) {
        final StatsLog stats = mStatsMap.get(downstreamType);
        if (stats == null) return;

        stats.setErrorCode(errCode);
    }
    /** Write Tethering metrics to {@link StatsLog} */
    public void write(final ErrorCode errorCode, final DownstreamType downstreamType,
            final UpstreamType upstreamType, final UserType userType) {
        TetheringStatsLog.write(TetheringStatsLog.NETWORK_TETHERING_REPORTED,
                errorCode.getNumber(),
                downstreamType.getNumber(),
                upstreamType.getNumber(),
                userType.getNumber());
        if (DBG) {
            Log.d(TAG, "write errorCode: " + errorCode.getNumber() + ", downstreamType: "
                    + downstreamType.getNumber() + ", upstreamType: " + upstreamType.getNumber()
                    + ", userType: " + userType.getNumber());
        }
    }
    /**
     * Map {@link TetheringType} to {@link DownstreamType}
    */
    private DownstreamType downstreamTypeToEnum(final int ifaceType) {
        switch(ifaceType) {
            case TETHERING_WIFI:
                return DownstreamType.DS_TETHERING_WIFI;
            case TETHERING_WIFI_P2P:
                return DownstreamType.DS_TETHERING_WIFI_P2P;
            case TETHERING_USB:
                return DownstreamType.DS_TETHERING_USB;
            case TETHERING_BLUETOOTH:
                return DownstreamType.DS_TETHERING_BLUETOOTH;
            case TETHERING_NCM:
                return DownstreamType.DS_TETHERING_NCM;
            case TETHERING_ETHERNET:
                return DownstreamType.DS_TETHERING_ETHERNET;
            default:
                return DownstreamType.DS_UNSPECIFIED;
        }
    }
    /**
     * Map {@link StartTetheringError} to {@link ErrorCode}
    */
    private ErrorCode errorCodeToEnum(final int lastError) {
        switch(lastError) {
            case TETHER_ERROR_NO_ERROR:
                return ErrorCode.EC_NO_ERROR;
            case TETHER_ERROR_UNKNOWN_IFACE:
                return ErrorCode.EC_UNKNOWN_IFACE;
            case TETHER_ERROR_SERVICE_UNAVAIL:
                return ErrorCode.EC_SERVICE_UNAVAIL;
            case TETHER_ERROR_UNSUPPORTED:
                return ErrorCode.EC_UNSUPPORTED;
            case TETHER_ERROR_UNAVAIL_IFACE:
                return ErrorCode.EC_UNAVAIL_IFACE;
            case TETHER_ERROR_INTERNAL_ERROR:
                return ErrorCode.EC_INTERNAL_ERROR;
            case TETHER_ERROR_TETHER_IFACE_ERROR:
                return ErrorCode.EC_TETHER_IFACE_ERROR;
            case TETHER_ERROR_UNTETHER_IFACE_ERROR:
                return ErrorCode.EC_UNTETHER_IFACE_ERROR;
            case TETHER_ERROR_ENABLE_FORWARDING_ERROR:
                return ErrorCode.EC_ENABLE_FORWARDING_ERROR;
            case TETHER_ERROR_DISABLE_FORWARDING_ERROR:
                return ErrorCode.EC_DISABLE_FORWARDING_ERROR;
            case TETHER_ERROR_IFACE_CFG_ERROR:
                return ErrorCode.EC_IFACE_CFG_ERROR;
            case TETHER_ERROR_PROVISIONING_FAILED:
                return ErrorCode.EC_PROVISIONING_FAILED;
            case TETHER_ERROR_DHCPSERVER_ERROR:
                return ErrorCode.EC_DHCPSERVER_ERROR;
            case TETHER_ERROR_ENTITLEMENT_UNKNOWN:
                return ErrorCode.EC_ENTITLEMENT_UNKNOWN;
            case TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION:
                return ErrorCode.EC_NO_CHANGE_TETHERING_PERMISSION;
            case TETHER_ERROR_NO_ACCESS_TETHERING_PERMISSION:
                return ErrorCode.EC_NO_ACCESS_TETHERING_PERMISSION;
            default:
                return ErrorCode.EC_UNKNOWN_TYPE;
        }
    }
    /**
     * Map callerPkg to {@link UserType}
    */
    private UserType userTypeToEnum(final String callerPkg) {
        if (callerPkg.equals(SETTINGS_PKG_NAME)) {
            return UserType.USER_SETTINGS;
        } else if (callerPkg.equals(SYSTEMUI_PKG_NAME)) {
            return UserType.USER_SYSTEMUI;
        } else if (callerPkg.equals(GMS_PKG_NAME)) {
            return UserType.USER_GMS;
        } else {
            return UserType.USER_UNKNOWN;
        }
    }
}
