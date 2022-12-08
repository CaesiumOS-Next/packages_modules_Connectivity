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
import static android.net.TetheringManager.TETHER_ERROR_UNKNOWN_TYPE;
import static android.net.TetheringManager.TETHER_ERROR_UNSUPPORTED;
import static android.net.TetheringManager.TETHER_ERROR_UNTETHER_IFACE_ERROR;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.net.NetworkCapabilities;
import android.stats.connectivity.DownstreamType;
import android.stats.connectivity.ErrorCode;
import android.stats.connectivity.UpstreamType;
import android.stats.connectivity.UserType;
import android.util.Pair;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.networkstack.tethering.UpstreamNetworkState;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public final class TetheringMetricsTest {
    private static final String TEST_CALLER_PKG = "com.test.caller.pkg";
    private static final String SETTINGS_PKG = "com.android.settings";
    private static final String SYSTEMUI_PKG = "com.android.systemui";
    private static final String GMS_PKG = "com.google.android.gms";
    private static final long TEST_START_TIME = 1670395936033L;
    private static final long SECOND_IN_MILLIS = 1_000L;

    // Sync from frameworks/proto_logging/stats/enums/stats/connectivity/network_stack.proto
    private static final int TRANSPORT_CELLULAR_VPN = 7;
    private static final int TRANSPORT_WIFI_VPN = 8;
    private static final int TRANSPORT_BLUETOOTH_VPN = 9;
    private static final int TRANSPORT_ETHERNET_VPN = 10;
    private static final int TRANSPORT_WIFI_CELLULAR_VPN = 11;

    private TetheringMetrics mTetheringMetrics;
    private final NetworkTetheringReported.Builder mStatsBuilder =
            NetworkTetheringReported.newBuilder();

    private long mElapsedRealtime;

    private class MockTetheringMetrics extends TetheringMetrics {
        @Override
        public void write(final NetworkTetheringReported reported) { }
        @Override
        public long timeNow() {
            return currentTimeMillis();
        }

    }

    private long currentTimeMillis() {
        return TEST_START_TIME + mElapsedRealtime;
    }

    private void incrementCurrentTime(final long duration) {
        mElapsedRealtime += duration;
        mTetheringMetrics.timeNow();
    }

    private long getElapsedRealtime() {
        return mElapsedRealtime;
    }

    private void clearElapsedRealtime() {
        mElapsedRealtime = 0;
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTetheringMetrics = spy(new MockTetheringMetrics());
        mElapsedRealtime = 0L;
    }

    private void verifyReport(final DownstreamType downstream, final ErrorCode error,
            final UserType user, final UpstreamEvents.Builder upstreamEvents, final long duration)
            throws Exception {
        final NetworkTetheringReported expectedReport =
                mStatsBuilder.setDownstreamType(downstream)
                .setUserType(user)
                .setUpstreamType(UpstreamType.UT_UNKNOWN)
                .setErrorCode(error)
                .setUpstreamEvents(upstreamEvents)
                .setDurationMillis(duration)
                .build();
        verify(mTetheringMetrics).write(expectedReport);
    }

    private void updateErrorAndSendReport(final int downstream, final int error) {
        mTetheringMetrics.updateErrorCode(downstream, error);
        mTetheringMetrics.sendReport(downstream);
    }

    private static UpstreamNetworkState buildUpstreamState(final int transport) {
        return new UpstreamNetworkState(
                null,
                buildUpstreamCapabilities(transport),
                null);
    }

    private static NetworkCapabilities buildUpstreamCapabilities(final int transport) {
        final NetworkCapabilities nc = new NetworkCapabilities();
        switch(transport) {
            case TRANSPORT_CELLULAR_VPN:
                nc.addTransportType(TRANSPORT_VPN);
                nc.addTransportType(TRANSPORT_CELLULAR);
                break;
            case TRANSPORT_WIFI_VPN:
                nc.addTransportType(TRANSPORT_VPN);
                nc.addTransportType(TRANSPORT_WIFI);
                break;
            case TRANSPORT_BLUETOOTH_VPN:
                nc.addTransportType(TRANSPORT_VPN);
                nc.addTransportType(TRANSPORT_BLUETOOTH);
                break;
            case TRANSPORT_ETHERNET_VPN:
                nc.addTransportType(TRANSPORT_VPN);
                nc.addTransportType(TRANSPORT_ETHERNET);
                break;
            case TRANSPORT_WIFI_CELLULAR_VPN:
                nc.addTransportType(TRANSPORT_VPN);
                nc.addTransportType(TRANSPORT_WIFI);
                nc.addTransportType(TRANSPORT_CELLULAR);
                break;
            default:
                nc.addTransportType(transport);
                break;

        }
        return nc;
    }

    private void runDownstreamTypesTest(final Pair<Integer, DownstreamType>... testPairs)
            throws Exception {
        for (Pair<Integer, DownstreamType> testPair : testPairs) {
            final int type = testPair.first;
            final DownstreamType expectedResult = testPair.second;
            mTetheringMetrics.maybeUpdateUpstreamType(null);
            mTetheringMetrics.createBuilder(type, TEST_CALLER_PKG);
            incrementCurrentTime(2 * SECOND_IN_MILLIS);

            UpstreamEvent.Builder upstreamEvent = UpstreamEvent.newBuilder()
                    .setUpstreamType(UpstreamType.UT_NO_NETWORK)
                    .setDurationMillis(2 * SECOND_IN_MILLIS);
            UpstreamEvents.Builder upstreamEvents = UpstreamEvents.newBuilder()
                    .addUpstreamEvent(upstreamEvent);
            updateErrorAndSendReport(type, TETHER_ERROR_NO_ERROR);

            verifyReport(expectedResult, ErrorCode.EC_NO_ERROR, UserType.USER_UNKNOWN,
                    upstreamEvents, getElapsedRealtime());
            reset(mTetheringMetrics);
            clearElapsedRealtime();
            mTetheringMetrics.cleanup();
        }
    }

    @Test
    public void testDownstreamTypes() throws Exception {
        runDownstreamTypesTest(new Pair<>(TETHERING_WIFI, DownstreamType.DS_TETHERING_WIFI));
        runDownstreamTypesTest(new Pair<>(TETHERING_WIFI_P2P,
                DownstreamType.DS_TETHERING_WIFI_P2P));
        runDownstreamTypesTest(new Pair<>(TETHERING_BLUETOOTH,
                DownstreamType.DS_TETHERING_BLUETOOTH));
        runDownstreamTypesTest(new Pair<>(TETHERING_USB, DownstreamType.DS_TETHERING_USB));
        runDownstreamTypesTest(new Pair<>(TETHERING_NCM, DownstreamType.DS_TETHERING_NCM));
        runDownstreamTypesTest(new Pair<>(TETHERING_ETHERNET,
                DownstreamType.DS_TETHERING_ETHERNET));
    }

    private void runErrorCodesTest(final Pair<Integer, ErrorCode>... testPairs)
            throws Exception {
        for (Pair<Integer, ErrorCode> testPair : testPairs) {
            final int errorCode = testPair.first;
            final ErrorCode expectedResult = testPair.second;

            mTetheringMetrics.createBuilder(TETHERING_WIFI, TEST_CALLER_PKG);
            mTetheringMetrics.maybeUpdateUpstreamType(null);
            incrementCurrentTime(1 * SECOND_IN_MILLIS);
            mTetheringMetrics.maybeUpdateUpstreamType(buildUpstreamState(TRANSPORT_WIFI));
            incrementCurrentTime(2 * SECOND_IN_MILLIS);
            updateErrorAndSendReport(TETHERING_WIFI, errorCode);

            UpstreamEvent.Builder upstreamEvent = UpstreamEvent.newBuilder()
                    .setUpstreamType(UpstreamType.UT_NO_NETWORK)
                    .setDurationMillis(1 * SECOND_IN_MILLIS);
            UpstreamEvents.Builder upstreamEvents = UpstreamEvents.newBuilder()
                    .addUpstreamEvent(upstreamEvent);

            upstreamEvent.setUpstreamType(UpstreamType.UT_WIFI)
                        .setDurationMillis(2 * SECOND_IN_MILLIS);
            upstreamEvents.addUpstreamEvent(upstreamEvent);

            verifyReport(DownstreamType.DS_TETHERING_WIFI, expectedResult, UserType.USER_UNKNOWN,
                        upstreamEvents, getElapsedRealtime());
            reset(mTetheringMetrics);
            clearElapsedRealtime();
            mTetheringMetrics.cleanup();
        }
    }

    @Test
    public void testErrorCodes() throws Exception {
        runErrorCodesTest(new Pair<>(TETHER_ERROR_NO_ERROR, ErrorCode.EC_NO_ERROR));
        runErrorCodesTest(new Pair<>(TETHER_ERROR_UNKNOWN_IFACE, ErrorCode.EC_UNKNOWN_IFACE));
        runErrorCodesTest(new Pair<>(TETHER_ERROR_SERVICE_UNAVAIL, ErrorCode.EC_SERVICE_UNAVAIL));
        runErrorCodesTest(new Pair<>(TETHER_ERROR_UNSUPPORTED, ErrorCode.EC_UNSUPPORTED));
        runErrorCodesTest(new Pair<>(TETHER_ERROR_UNAVAIL_IFACE, ErrorCode.EC_UNAVAIL_IFACE));
        runErrorCodesTest(new Pair<>(TETHER_ERROR_INTERNAL_ERROR, ErrorCode.EC_INTERNAL_ERROR));
        runErrorCodesTest(new Pair<>(TETHER_ERROR_TETHER_IFACE_ERROR,
                ErrorCode.EC_TETHER_IFACE_ERROR));
        runErrorCodesTest(new Pair<>(TETHER_ERROR_UNTETHER_IFACE_ERROR,
                ErrorCode.EC_UNTETHER_IFACE_ERROR));
        runErrorCodesTest(new Pair<>(TETHER_ERROR_ENABLE_FORWARDING_ERROR,
                ErrorCode.EC_ENABLE_FORWARDING_ERROR));
        runErrorCodesTest(new Pair<>(TETHER_ERROR_DISABLE_FORWARDING_ERROR,
                ErrorCode.EC_DISABLE_FORWARDING_ERROR));
        runErrorCodesTest(new Pair<>(TETHER_ERROR_IFACE_CFG_ERROR, ErrorCode.EC_IFACE_CFG_ERROR));
        runErrorCodesTest(new Pair<>(TETHER_ERROR_PROVISIONING_FAILED,
                ErrorCode.EC_PROVISIONING_FAILED));
        runErrorCodesTest(new Pair<>(TETHER_ERROR_DHCPSERVER_ERROR,
                ErrorCode.EC_DHCPSERVER_ERROR));
        runErrorCodesTest(new Pair<>(TETHER_ERROR_ENTITLEMENT_UNKNOWN,
                ErrorCode.EC_ENTITLEMENT_UNKNOWN));
        runErrorCodesTest(new Pair<>(TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION,
                ErrorCode.EC_NO_CHANGE_TETHERING_PERMISSION));
        runErrorCodesTest(new Pair<>(TETHER_ERROR_NO_ACCESS_TETHERING_PERMISSION,
                ErrorCode.EC_NO_ACCESS_TETHERING_PERMISSION));
        runErrorCodesTest(new Pair<>(TETHER_ERROR_UNKNOWN_TYPE, ErrorCode.EC_UNKNOWN_TYPE));
    }

    private void runUserTypesTest(final Pair<String, UserType>... testPairs)
            throws Exception {
        for (Pair<String, UserType> testPair : testPairs) {
            final String callerPkg = testPair.first;
            final UserType expectedResult = testPair.second;

            mTetheringMetrics.createBuilder(TETHERING_WIFI, callerPkg);
            mTetheringMetrics.maybeUpdateUpstreamType(null);
            incrementCurrentTime(1 * SECOND_IN_MILLIS);
            updateErrorAndSendReport(TETHERING_WIFI, TETHER_ERROR_NO_ERROR);

            UpstreamEvent.Builder upstreamEvent = UpstreamEvent.newBuilder()
                    .setUpstreamType(UpstreamType.UT_NO_NETWORK)
                    .setDurationMillis(1 * SECOND_IN_MILLIS);
            UpstreamEvents.Builder upstreamEvents = UpstreamEvents.newBuilder()
                    .addUpstreamEvent(upstreamEvent);

            verifyReport(DownstreamType.DS_TETHERING_WIFI, ErrorCode.EC_NO_ERROR, expectedResult,
                        upstreamEvents, getElapsedRealtime());
            reset(mTetheringMetrics);
            clearElapsedRealtime();
            mTetheringMetrics.cleanup();
        }
    }

    @Test
    public void testUserTypes() throws Exception {
        runUserTypesTest(new Pair<>(TEST_CALLER_PKG, UserType.USER_UNKNOWN),
                new Pair<>(SETTINGS_PKG, UserType.USER_SETTINGS),
                new Pair<>(SYSTEMUI_PKG, UserType.USER_SYSTEMUI),
                new Pair<>(GMS_PKG, UserType.USER_GMS));
    }

    private void runUpstreamTypesTest(final Pair<Integer, UpstreamType>... testPairs)
            throws Exception {
        for (Pair<Integer, UpstreamType> testPair : testPairs) {
            final int upstream = testPair.first;
            final UpstreamType expectedResult = testPair.second;
            mTetheringMetrics.createBuilder(TETHERING_WIFI, TEST_CALLER_PKG);
            mTetheringMetrics.maybeUpdateUpstreamType(null);
            incrementCurrentTime(1 * SECOND_IN_MILLIS);
            mTetheringMetrics.maybeUpdateUpstreamType(buildUpstreamState(upstream));
            incrementCurrentTime(2 * SECOND_IN_MILLIS);
            updateErrorAndSendReport(TETHERING_WIFI, TETHER_ERROR_NO_ERROR);

            UpstreamEvents.Builder upstreamEvents = UpstreamEvents.newBuilder();
            UpstreamEvent.Builder upstreamEvent = UpstreamEvent.newBuilder();

            if (upstream == -1) {
                upstreamEvent.setUpstreamType(UpstreamType.UT_NO_NETWORK)
                            .setDurationMillis(3 * SECOND_IN_MILLIS);
                upstreamEvents.addUpstreamEvent(upstreamEvent);
            } else {
                upstreamEvent.setUpstreamType(UpstreamType.UT_NO_NETWORK)
                            .setDurationMillis(1 * SECOND_IN_MILLIS);
                upstreamEvents.addUpstreamEvent(upstreamEvent);
                upstreamEvent.setUpstreamType(expectedResult)
                            .setDurationMillis(2 * SECOND_IN_MILLIS);
                upstreamEvents.addUpstreamEvent(upstreamEvent);
            }
            verifyReport(DownstreamType.DS_TETHERING_WIFI, ErrorCode.EC_NO_ERROR,
                        UserType.USER_UNKNOWN, upstreamEvents, getElapsedRealtime());
            reset(mTetheringMetrics);
            clearElapsedRealtime();
            mTetheringMetrics.cleanup();
        }
    }

    @Test
    public void testUpstreamTypes() throws Exception {
        runUpstreamTypesTest(new Pair<>(TRANSPORT_CELLULAR, UpstreamType.UT_CELLULAR));
        runUpstreamTypesTest(new Pair<>(TRANSPORT_WIFI, UpstreamType.UT_WIFI));
        runUpstreamTypesTest(new Pair<>(TRANSPORT_BLUETOOTH, UpstreamType.UT_BLUETOOTH));
        runUpstreamTypesTest(new Pair<>(TRANSPORT_ETHERNET, UpstreamType.UT_ETHERNET));
        runUpstreamTypesTest(new Pair<>(TRANSPORT_WIFI_AWARE, UpstreamType.UT_WIFI_AWARE));
        runUpstreamTypesTest(new Pair<>(TRANSPORT_LOWPAN, UpstreamType.UT_LOWPAN));
        runUpstreamTypesTest(new Pair<>(TRANSPORT_CELLULAR_VPN, UpstreamType.UT_CELLULAR_VPN));
        runUpstreamTypesTest(new Pair<>(TRANSPORT_WIFI_VPN, UpstreamType.UT_WIFI_VPN));
        runUpstreamTypesTest(new Pair<>(TRANSPORT_BLUETOOTH_VPN, UpstreamType.UT_BLUETOOTH_VPN));
        runUpstreamTypesTest(new Pair<>(TRANSPORT_ETHERNET_VPN, UpstreamType.UT_ETHERNET_VPN));
        runUpstreamTypesTest(new Pair<>(TRANSPORT_WIFI_CELLULAR_VPN,
                UpstreamType.UT_WIFI_CELLULAR_VPN));
    }

    @Test
    public void testMultiBuildersCreatedBeforeSendReport() throws Exception {
        mTetheringMetrics.createBuilder(TETHERING_WIFI, SETTINGS_PKG);
        mTetheringMetrics.maybeUpdateUpstreamType(null);
        incrementCurrentTime(1 * SECOND_IN_MILLIS);
        mTetheringMetrics.createBuilder(TETHERING_USB, SYSTEMUI_PKG);
        incrementCurrentTime(2 * SECOND_IN_MILLIS);
        mTetheringMetrics.createBuilder(TETHERING_BLUETOOTH, GMS_PKG);
        incrementCurrentTime(3 * SECOND_IN_MILLIS);
        updateErrorAndSendReport(TETHERING_WIFI, TETHER_ERROR_DHCPSERVER_ERROR);

        UpstreamEvent.Builder upstreamEvent = UpstreamEvent.newBuilder()
                .setUpstreamType(UpstreamType.UT_NO_NETWORK)
                .setDurationMillis(getElapsedRealtime());
        UpstreamEvents.Builder upstreamEvents = UpstreamEvents.newBuilder()
                .addUpstreamEvent(upstreamEvent);
        verifyReport(DownstreamType.DS_TETHERING_WIFI, ErrorCode.EC_DHCPSERVER_ERROR,
                UserType.USER_SETTINGS, upstreamEvents, getElapsedRealtime());

        incrementCurrentTime(1 * SECOND_IN_MILLIS);
        updateErrorAndSendReport(TETHERING_USB, TETHER_ERROR_ENABLE_FORWARDING_ERROR);

        upstreamEvent.setUpstreamType(UpstreamType.UT_NO_NETWORK)
                    .setDurationMillis(getElapsedRealtime() - (1 * SECOND_IN_MILLIS));
        upstreamEvents.clearUpstreamEvent();
        upstreamEvents.addUpstreamEvent(upstreamEvent);
        verifyReport(DownstreamType.DS_TETHERING_USB, ErrorCode.EC_ENABLE_FORWARDING_ERROR,
                UserType.USER_SYSTEMUI, upstreamEvents,
                getElapsedRealtime() - (1 * SECOND_IN_MILLIS));

        incrementCurrentTime(1 * SECOND_IN_MILLIS);
        updateErrorAndSendReport(TETHERING_BLUETOOTH, TETHER_ERROR_TETHER_IFACE_ERROR);

        upstreamEvents.clearUpstreamEvent();
        upstreamEvent.setUpstreamType(UpstreamType.UT_NO_NETWORK)
                    .setDurationMillis(getElapsedRealtime() - (1 * SECOND_IN_MILLIS)
                    - (2 * SECOND_IN_MILLIS));
        upstreamEvents.addUpstreamEvent(upstreamEvent);
        verifyReport(DownstreamType.DS_TETHERING_BLUETOOTH, ErrorCode.EC_TETHER_IFACE_ERROR,
                UserType.USER_GMS, upstreamEvents,
                getElapsedRealtime() - (1 * SECOND_IN_MILLIS) - (2 * SECOND_IN_MILLIS));
    }

    @Test
    public void testSingleUpstreamCreatedAfterStartingTethering() throws Exception {
        mTetheringMetrics.createBuilder(TETHERING_WIFI, SETTINGS_PKG);
        mTetheringMetrics.maybeUpdateUpstreamType(null);
        incrementCurrentTime(2 * SECOND_IN_MILLIS);
        mTetheringMetrics.maybeUpdateUpstreamType(buildUpstreamState(TRANSPORT_WIFI));
        incrementCurrentTime(10 * SECOND_IN_MILLIS);
        updateErrorAndSendReport(TETHERING_WIFI, TETHER_ERROR_NO_ERROR);

        UpstreamEvent.Builder upstreamEvent = UpstreamEvent.newBuilder()
                .setUpstreamType(UpstreamType.UT_NO_NETWORK)
                .setDurationMillis(2 * SECOND_IN_MILLIS);
        UpstreamEvents.Builder upstreamEvents = UpstreamEvents.newBuilder()
                .addUpstreamEvent(upstreamEvent);

        upstreamEvent.setUpstreamType(UpstreamType.UT_WIFI)
                .setDurationMillis(10 * SECOND_IN_MILLIS);
        upstreamEvents.addUpstreamEvent(upstreamEvent);

        verifyReport(DownstreamType.DS_TETHERING_WIFI, ErrorCode.EC_NO_ERROR,
                UserType.USER_SETTINGS, upstreamEvents,
                getElapsedRealtime());
    }

    @Test
    public void testMultiUpstreamsCreatedAfterStartingTethering() throws Exception {
        mTetheringMetrics.createBuilder(TETHERING_WIFI, SETTINGS_PKG);
        mTetheringMetrics.maybeUpdateUpstreamType(null);
        incrementCurrentTime(1 * SECOND_IN_MILLIS);
        mTetheringMetrics.maybeUpdateUpstreamType(buildUpstreamState(TRANSPORT_WIFI));
        incrementCurrentTime(5 * SECOND_IN_MILLIS);
        mTetheringMetrics.createBuilder(TETHERING_USB, SYSTEMUI_PKG);
        incrementCurrentTime(5 * SECOND_IN_MILLIS);
        updateErrorAndSendReport(TETHERING_USB, TETHER_ERROR_NO_ERROR);

        UpstreamEvent.Builder upstreamEvent = UpstreamEvent.newBuilder()
                .setUpstreamType(UpstreamType.UT_WIFI)
                .setDurationMillis(5 * SECOND_IN_MILLIS);
        UpstreamEvents.Builder upstreamEvents = UpstreamEvents.newBuilder()
                .addUpstreamEvent(upstreamEvent);

        verifyReport(DownstreamType.DS_TETHERING_USB, ErrorCode.EC_NO_ERROR,
                UserType.USER_SYSTEMUI, upstreamEvents,
                5 * SECOND_IN_MILLIS);

        incrementCurrentTime(7 * SECOND_IN_MILLIS);
        updateErrorAndSendReport(TETHERING_WIFI, TETHER_ERROR_NO_ERROR);
        upstreamEvents.clearUpstreamEvent();
        upstreamEvent.setUpstreamType(UpstreamType.UT_NO_NETWORK)
                .setDurationMillis(1 * SECOND_IN_MILLIS);
        upstreamEvents.addUpstreamEvent(upstreamEvent);

        upstreamEvent.setUpstreamType(UpstreamType.UT_WIFI)
                .setDurationMillis((5 * SECOND_IN_MILLIS)
                    + (5 * SECOND_IN_MILLIS)
                    + (7 * SECOND_IN_MILLIS));
        upstreamEvents.addUpstreamEvent(upstreamEvent);
        verifyReport(DownstreamType.DS_TETHERING_WIFI, ErrorCode.EC_NO_ERROR,
                UserType.USER_SETTINGS, upstreamEvents, getElapsedRealtime());
    }

    @Test
    public void testMultiUpstreamsCreatedBeforeStartingTethering() throws Exception {
        mTetheringMetrics.maybeUpdateUpstreamType(null);
        incrementCurrentTime(1 * SECOND_IN_MILLIS);
        mTetheringMetrics.maybeUpdateUpstreamType(buildUpstreamState(TRANSPORT_WIFI));
        incrementCurrentTime(5 * SECOND_IN_MILLIS);
        mTetheringMetrics.createBuilder(TETHERING_WIFI, SETTINGS_PKG);
        incrementCurrentTime(5 * SECOND_IN_MILLIS);
        mTetheringMetrics.maybeUpdateUpstreamType(buildUpstreamState(TRANSPORT_BLUETOOTH));
        incrementCurrentTime(15 * SECOND_IN_MILLIS);
        mTetheringMetrics.maybeUpdateUpstreamType(buildUpstreamState(TRANSPORT_CELLULAR));
        incrementCurrentTime(20 * SECOND_IN_MILLIS);
        updateErrorAndSendReport(TETHERING_WIFI, TETHER_ERROR_NO_ERROR);

        UpstreamEvent.Builder upstreamEvent = UpstreamEvent.newBuilder()
                .setUpstreamType(UpstreamType.UT_WIFI)
                .setDurationMillis(5 * SECOND_IN_MILLIS);
        UpstreamEvent.Builder upstreamEvent1 = UpstreamEvent.newBuilder()
                .setUpstreamType(UpstreamType.UT_BLUETOOTH)
                .setDurationMillis(15 * SECOND_IN_MILLIS);
        UpstreamEvent.Builder upstreamEvent2 = UpstreamEvent.newBuilder()
                .setUpstreamType(UpstreamType.UT_CELLULAR)
                .setDurationMillis(20 * SECOND_IN_MILLIS);
        UpstreamEvents.Builder upstreamEvents = UpstreamEvents.newBuilder()
                .addUpstreamEvent(upstreamEvent)
                .addUpstreamEvent(upstreamEvent1)
                .addUpstreamEvent(upstreamEvent2);

        verifyReport(DownstreamType.DS_TETHERING_WIFI, ErrorCode.EC_NO_ERROR,
                UserType.USER_SETTINGS, upstreamEvents,
                getElapsedRealtime() - (1 * SECOND_IN_MILLIS) - (5 * SECOND_IN_MILLIS));
    }
}
