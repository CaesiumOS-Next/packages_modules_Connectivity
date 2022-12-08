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

import static android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_ETHERNET;
import static android.net.NetworkCapabilities.TRANSPORT_LOWPAN;
import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI_AWARE;
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

import android.net.NetworkCapabilities;
import android.stats.connectivity.DownstreamType;
import android.stats.connectivity.ErrorCode;
import android.stats.connectivity.UpstreamType;
import android.stats.connectivity.UserType;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.networkstack.tethering.UpstreamNetworkState;

import java.util.ArrayList;
import java.util.Arrays;

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
    private final SparseArray<NetworkTetheringReported.Builder> mBuilderMap = new SparseArray<>();
    private final SparseArray<Long> mDownstreamStartTime = new SparseArray<Long>();
    private final ArrayList<RecordUpstreamEvent> mUpstreamEventList = new ArrayList<>();
    private UpstreamType mCurrentUpstream = null;
    private Long mCurrentUpStreamStartTime = 0L;


    /** Return current system time in milliseconds. */
    public long timeNow() {
        return System.currentTimeMillis();
    }

    /**
     * To record each upstream type and its start time and stop time.
     * @hide
     */
    private static class RecordUpstreamEvent {
        public final long mStartTime;
        public final long mStopTime;
        public final UpstreamType mUpstreamType;

        RecordUpstreamEvent(final long startTime, final long stopTime,
                final UpstreamType upstream) {
            mStartTime = startTime;
            mStopTime = stopTime;
            mUpstreamType = upstream;
        }
    }

    /** Update Tethering stats about caller's package name and downstream type. */
    public void createBuilder(final int downstreamType, final String callerPkg) {
        NetworkTetheringReported.Builder statsBuilder = NetworkTetheringReported.newBuilder()
                .setDownstreamType(downstreamTypeToEnum(downstreamType))
                .setUserType(userTypeToEnum(callerPkg))
                .setUpstreamType(UpstreamType.UT_UNKNOWN)
                .setErrorCode(ErrorCode.EC_NO_ERROR)
                .setUpstreamEvents(UpstreamEvents.newBuilder())
                .setDurationMillis(0);
        mBuilderMap.put(downstreamType, statsBuilder);
        mDownstreamStartTime.put(downstreamType, timeNow());
    }

    /** Update error code of given downstreamType. */
    public void updateErrorCode(final int downstreamType, final int errCode) {
        NetworkTetheringReported.Builder statsBuilder = mBuilderMap.get(downstreamType);
        if (statsBuilder == null) {
            Log.e(TAG, "Given downstreamType does not exist, this is a bug!");
            return;
        }
        statsBuilder.setErrorCode(errorCodeToEnum(errCode));
    }

    /**
     *  Update each upstream type and duration in the ArrayList when every time |mCurrentUpstream|
     *  updates.
     */
    public void maybeUpdateUpstreamType(final UpstreamNetworkState nc) {
        UpstreamType upstream = transportTypeToUpstreamTypeEnum(nc);
        if (upstream.equals(mCurrentUpstream)) return;

        final long newTime = timeNow();
        mUpstreamEventList.add(new RecordUpstreamEvent(mCurrentUpStreamStartTime, newTime,
                mCurrentUpstream));
        mCurrentUpstream = upstream;
        mCurrentUpStreamStartTime = newTime;
    }


    private long getStartTime(long first, long second) {
        return first > second ? first : second;
    }

    /**
     *  A wrap that updates upstream events.
     */
    private void updateUpstreamEvents(final UpstreamEvents.Builder upstreamEventsBuilder,
            final long start, final long stop, final UpstreamType upstream) {
        final UpstreamEvent.Builder upstreamEventBuilder = UpstreamEvent.newBuilder()
                .setUpstreamType(upstream == null ? UpstreamType.UT_NO_NETWORK : upstream)
                .setDurationMillis(stop - start);
        upstreamEventsBuilder.addUpstreamEvent(upstreamEventBuilder);
    }

    /**
     * Update the latest upstream event and sort out relevant upstream events into
     * |statsBuilder|.
     */
    private void updateStatsBuilderToWrite(final NetworkTetheringReported.Builder statsBuilder,
                    final long downstreamStartTime) {
        UpstreamEvents.Builder upstreamEventsBuilder = UpstreamEvents.newBuilder();

        for (RecordUpstreamEvent event : mUpstreamEventList) {
            if (downstreamStartTime > event.mStartTime) {
                if (downstreamStartTime < event.mStopTime) {
                    updateUpstreamEvents(upstreamEventsBuilder, downstreamStartTime,
                            event.mStopTime, event.mUpstreamType);
                }
                continue;
            }

            final long startTime = getStartTime(downstreamStartTime, event.mStartTime);
            updateUpstreamEvents(upstreamEventsBuilder, startTime, event.mStopTime,
                    event.mUpstreamType);
        }

        final long startTime = getStartTime(downstreamStartTime, mCurrentUpStreamStartTime);
        final long stopTime = timeNow();

        updateUpstreamEvents(upstreamEventsBuilder, startTime, stopTime, mCurrentUpstream);
        statsBuilder.setUpstreamEvents(upstreamEventsBuilder);
        statsBuilder.setDurationMillis(stopTime - downstreamStartTime);
    }

    /**
     *  Remove Tethering stats. If Tethering stats is ready to write then write it before
     *  removing.
     */
    public void sendReport(final int downstreamType) {
        final NetworkTetheringReported.Builder statsBuilder = mBuilderMap.get(downstreamType);
        if (statsBuilder == null) {
            Log.e(TAG, "Given downstreamType does not exist, this is a bug!");
            return;
        }

        updateStatsBuilderToWrite(statsBuilder, mDownstreamStartTime.get(downstreamType));
        write(statsBuilder.build());

        mBuilderMap.remove(downstreamType);
        mDownstreamStartTime.remove(downstreamType);
    }

    /** Collect Tethering stats and write metrics data to statsd pipeline. */
    @VisibleForTesting
    public void write(@NonNull final NetworkTetheringReported reported) {
        final byte[] upstreamEvents = reported.getUpstreamEvents().toByteArray();

        TetheringStatsLog.write(
                TetheringStatsLog.NETWORK_TETHERING_REPORTED,
                reported.getErrorCode().getNumber(),
                reported.getDownstreamType().getNumber(),
                reported.getUpstreamType().getNumber(),
                reported.getUserType().getNumber(),
                upstreamEvents,
                reported.getDurationMillis());
        if (DBG) {
            Log.d(
                    TAG,
                    "Write errorCode: "
                    + reported.getErrorCode().getNumber()
                    + ", downstreamType: "
                    + reported.getDownstreamType().getNumber()
                    + ", upstreamType: "
                    + reported.getUpstreamType().getNumber()
                    + ", userType: "
                    + reported.getUserType().getNumber()
                    + ", upstreamTypes: "
                    + Arrays.toString(upstreamEvents)
                    + ", durationMillis: "
                    + reported.getDurationMillis());
        }
    }

    /** Clean-up upstream revelant variables when turn off Tethering. */
    public void cleanup() {
        mUpstreamEventList.clear();
        mCurrentUpstream = null;
        mCurrentUpStreamStartTime = 0L;
    }

    private DownstreamType downstreamTypeToEnum(final int ifaceType) {
        switch (ifaceType) {
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

    private ErrorCode errorCodeToEnum(final int lastError) {
        switch (lastError) {
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

    private UpstreamType transportTypeToUpstreamTypeEnum(final UpstreamNetworkState ns) {
        final NetworkCapabilities nc = (ns != null) ? ns.networkCapabilities : null;
        if (nc == null) return UpstreamType.UT_NO_NETWORK;

        final int typeCount = nc.getTransportTypes().length;

        boolean hasCellular = nc.hasTransport(TRANSPORT_CELLULAR);
        boolean hasWifi = nc.hasTransport(TRANSPORT_WIFI);
        boolean hasBT = nc.hasTransport(TRANSPORT_BLUETOOTH);
        boolean hasEthernet = nc.hasTransport(TRANSPORT_ETHERNET);
        boolean hasVpn = nc.hasTransport(TRANSPORT_VPN);
        boolean hasWifiAware = nc.hasTransport(TRANSPORT_WIFI_AWARE);
        boolean hasLopan = nc.hasTransport(TRANSPORT_LOWPAN);

        if (typeCount == 3 && hasCellular && hasWifi && hasVpn) {
            return UpstreamType.UT_WIFI_CELLULAR_VPN;
        }

        if (typeCount == 2 && hasVpn) {
            if (hasCellular) return UpstreamType.UT_CELLULAR_VPN;
            if (hasWifi) return UpstreamType.UT_WIFI_VPN;
            if (hasBT) return UpstreamType.UT_BLUETOOTH_VPN;
            if (hasEthernet) return UpstreamType.UT_ETHERNET_VPN;
        }

        if (typeCount == 1) {
            if (hasCellular) return UpstreamType.UT_CELLULAR;
            if (hasWifi) return UpstreamType.UT_WIFI;
            if (hasBT) return UpstreamType.UT_BLUETOOTH;
            if (hasEthernet) return UpstreamType.UT_ETHERNET;
            if (hasWifiAware) return UpstreamType.UT_WIFI_AWARE;
            if (hasLopan) return UpstreamType.UT_LOWPAN;
        }
        return UpstreamType.UT_UNKNOWN;
    }
}
