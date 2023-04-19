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

package com.android.server.connectivity;

import static com.android.testutils.ContextUtils.mockService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telephony.CarrierConfigManager;
import android.telephony.CarrierConfigManager.CarrierConfigChangeListener;
import android.telephony.TelephonyManager;

import androidx.test.filters.SmallTest;

import com.android.metrics.DailykeepaliveInfoReported;
import com.android.metrics.DurationForNumOfKeepalive;
import com.android.metrics.DurationPerNumOfKeepalive;
import com.android.metrics.KeepaliveLifetimeForCarrier;
import com.android.metrics.KeepaliveLifetimePerCarrier;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(DevSdkIgnoreRunner.class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
public class KeepaliveStatsTrackerTest {
    private static final int TEST_KEEPALIVE_INTERVAL_SEC = 3;
    private static final int TEST_UID = 1234;
    private static final int TEST_CARRIER_ID = 1;
    private static final Network TEST_NETWORK = new Network(123);
    private static final NetworkCapabilities TEST_NETWORK_CAPABILITIES =
            new NetworkCapabilities.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .build();

    private Handler mHandler;
    private KeepaliveStatsTracker mKeepaliveStatsTracker;

    @Mock private Context mContext;
    @Mock private KeepaliveStatsTracker.Dependencies mDependencies;
    @Mock private TelephonyManager mTelephonyManager;
    @Mock private CarrierConfigManager mCarrierConfigManager;

    @Captor private ArgumentCaptor<CarrierConfigChangeListener> mCarrierConfigListenerCaptor;
    private CarrierConfigChangeListener mCarrierConfigChangeListener;

    private static final class KeepaliveCarrierStats {
        public final int carrierId;
        public final int transportTypes;
        public final int intervalsMsec;
        public final int lifetimeMsec;
        public final int activeLifetimeMsec;

        KeepaliveCarrierStats(
                int carrierId,
                int transportTypes,
                int intervalsMsec,
                int lifetimeMsec,
                int activeLifetimeMsec) {
            this.carrierId = carrierId;
            this.transportTypes = transportTypes;
            this.intervalsMsec = intervalsMsec;
            this.lifetimeMsec = lifetimeMsec;
            this.activeLifetimeMsec = activeLifetimeMsec;
        }
    }

    // Use the default test carrier id, transportType and keepalive interval.
    private KeepaliveCarrierStats getDefaultCarrierStats(int lifetimeMsec, int activeLifetimeMsec) {
        return new KeepaliveCarrierStats(
                TEST_CARRIER_ID,
                // Cellular transport type
                /* transportTypes= */ 1,
                TEST_KEEPALIVE_INTERVAL_SEC * 1000,
                lifetimeMsec,
                activeLifetimeMsec);
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mockService(mContext, TelephonyManager.class, Context.TELEPHONY_SERVICE, mTelephonyManager);
        mockService(
                mContext,
                CarrierConfigManager.class,
                Context.CARRIER_CONFIG_SERVICE,
                mCarrierConfigManager);

        doReturn(TEST_CARRIER_ID).when(mTelephonyManager).getSimCarrierId();

        mHandler = new Handler(Looper.getMainLooper());

        setUptimeMillis(0);
        mKeepaliveStatsTracker = new KeepaliveStatsTracker(mContext, mHandler, mDependencies);

        verify(mCarrierConfigManager)
                .registerCarrierConfigChangeListener(any(), mCarrierConfigListenerCaptor.capture());
        mCarrierConfigChangeListener = mCarrierConfigListenerCaptor.getValue();
    }

    private void setUptimeMillis(long time) {
        doReturn(time).when(mDependencies).getUptimeMillis();
    }

    /**
     * Asserts that a DurationPerNumOfKeepalive contains expected values
     *
     * @param expectRegisteredDurations integer array where the index is the number of concurrent
     *     keepalives and the value is the expected duration of time that the tracker is in a state
     *     with the given number of keepalives registered.
     * @param expectActiveDurations integer array where the index is the number of concurrent
     *     keepalives and the value is the expected duration of time that the tracker is in a state
     *     with the given number of keepalives active.
     * @param resultDurationsPerNumOfKeepalive the DurationPerNumOfKeepalive message to assert.
     */
    private void assertDurationMetrics(
            int[] expectRegisteredDurations,
            int[] expectActiveDurations,
            DurationPerNumOfKeepalive resultDurationsPerNumOfKeepalive) {
        final int maxNumOfKeepalive = expectRegisteredDurations.length;
        assertEquals(maxNumOfKeepalive, expectActiveDurations.length);
        assertEquals(
                maxNumOfKeepalive,
                resultDurationsPerNumOfKeepalive.getDurationForNumOfKeepaliveCount());
        for (int numOfKeepalive = 0; numOfKeepalive < maxNumOfKeepalive; numOfKeepalive++) {
            final DurationForNumOfKeepalive resultDurations =
                    resultDurationsPerNumOfKeepalive.getDurationForNumOfKeepalive(numOfKeepalive);

            assertEquals(numOfKeepalive, resultDurations.getNumOfKeepalive());
            assertEquals(
                    expectRegisteredDurations[numOfKeepalive],
                    resultDurations.getKeepaliveRegisteredDurationsMsec());
            assertEquals(
                    expectActiveDurations[numOfKeepalive],
                    resultDurations.getKeepaliveActiveDurationsMsec());
        }
    }

    /**
     * Asserts that a KeepaliveLifetimePerCarrier contains an expected KeepaliveCarrierStats. Finds
     * using (carrierId, transportTypes, intervalsMsec) as the key and asserts the lifetime metrics.
     *
     * @param expectKeepaliveCarrierStats a keepalive lifetime metric that is expected to be in the
     *     proto.
     * @param resultKeepaliveLifetimePerCarrier the KeepaliveLifetimePerCarrier message to assert.
     */
    private void findAndAssertCarrierLifetimeMetrics(
            KeepaliveCarrierStats expectKeepaliveCarrierStats,
            KeepaliveLifetimePerCarrier resultKeepaliveLifetimePerCarrier) {
        for (KeepaliveLifetimeForCarrier keepaliveLifetimeForCarrier :
                resultKeepaliveLifetimePerCarrier.getKeepaliveLifetimeForCarrierList()) {
            if (expectKeepaliveCarrierStats.carrierId == keepaliveLifetimeForCarrier.getCarrierId()
                    && expectKeepaliveCarrierStats.transportTypes
                            == keepaliveLifetimeForCarrier.getTransportTypes()
                    && expectKeepaliveCarrierStats.intervalsMsec
                            == keepaliveLifetimeForCarrier.getIntervalsMsec()) {
                assertEquals(
                        expectKeepaliveCarrierStats.lifetimeMsec,
                        keepaliveLifetimeForCarrier.getLifetimeMsec());
                assertEquals(
                        expectKeepaliveCarrierStats.activeLifetimeMsec,
                        keepaliveLifetimeForCarrier.getActiveLifetimeMsec());
                return;
            }
        }
        fail("KeepaliveLifetimeForCarrier not found for a given expected KeepaliveCarrierStats");
    }

    /**
     * Asserts that a KeepaliveLifetimePerCarrier contains all the expected KeepaliveCarrierStats.
     *
     * @param expectKeepaliveCarrierStatsArray an array of keepalive lifetime metrics that is
     *     expected to be in the KeepaliveLifetimePerCarrier.
     * @param resultKeepaliveLifetimePerCarrier the KeepaliveLifetimePerCarrier message to assert.
     */
    private void assertCarrierLifetimeMetrics(
            KeepaliveCarrierStats[] expectKeepaliveCarrierStatsArray,
            KeepaliveLifetimePerCarrier resultKeepaliveLifetimePerCarrier) {
        assertEquals(
                expectKeepaliveCarrierStatsArray.length,
                resultKeepaliveLifetimePerCarrier.getKeepaliveLifetimeForCarrierCount());
        for (KeepaliveCarrierStats keepaliveCarrierStats : expectKeepaliveCarrierStatsArray) {
            findAndAssertCarrierLifetimeMetrics(
                    keepaliveCarrierStats, resultKeepaliveLifetimePerCarrier);
        }
    }

    private void assertDailyKeepaliveInfoReported(
            DailykeepaliveInfoReported dailyKeepaliveInfoReported,
            int[] expectRegisteredDurations,
            int[] expectActiveDurations,
            KeepaliveCarrierStats[] expectKeepaliveCarrierStatsArray) {
        // TODO(b/273451360) Assert these values when they are filled.
        assertFalse(dailyKeepaliveInfoReported.hasKeepaliveRequests());
        assertFalse(dailyKeepaliveInfoReported.hasAutomaticKeepaliveRequests());
        assertFalse(dailyKeepaliveInfoReported.hasDistinctUserCount());
        assertTrue(dailyKeepaliveInfoReported.getUidList().isEmpty());

        final DurationPerNumOfKeepalive resultDurations =
                dailyKeepaliveInfoReported.getDurationPerNumOfKeepalive();
        assertDurationMetrics(expectRegisteredDurations, expectActiveDurations, resultDurations);

        final KeepaliveLifetimePerCarrier resultCarrierLifetime =
                dailyKeepaliveInfoReported.getKeepaliveLifetimePerCarrier();

        assertCarrierLifetimeMetrics(expectKeepaliveCarrierStatsArray, resultCarrierLifetime);
    }

    @Test
    public void testNoKeepalive() {
        final int writeTime = 5000;

        setUptimeMillis(writeTime);
        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                mKeepaliveStatsTracker.buildKeepaliveMetrics();

        // Expect that the durations are all in numOfKeepalive = 0.
        final int[] expectRegisteredDurations = new int[] {writeTime};
        final int[] expectActiveDurations = new int[] {writeTime};

        assertDailyKeepaliveInfoReported(
                dailyKeepaliveInfoReported,
                expectRegisteredDurations,
                expectActiveDurations,
                new KeepaliveCarrierStats[0]);
    }

    /*
     * Diagram of test (not to scale):
     * Key: S - Start/Stop, P - Pause, R - Resume, W - Write
     *
     * Keepalive     S                          W
     * Timeline  |------------------------------|
     */
    @Test
    public void testOneKeepalive_startOnly() {
        final int startTime = 1000;
        final int writeTime = 5000;

        setUptimeMillis(startTime);
        mKeepaliveStatsTracker.onStartKeepalive(
                TEST_NETWORK,
                /* slot= */ 1,
                TEST_NETWORK_CAPABILITIES,
                TEST_KEEPALIVE_INTERVAL_SEC);

        setUptimeMillis(writeTime);
        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                mKeepaliveStatsTracker.buildKeepaliveMetrics();

        // The keepalive is never stopped, expect the duration for numberOfKeepalive of 1 to range
        // from startTime to writeTime.
        final int[] expectRegisteredDurations = new int[] {startTime, writeTime - startTime};
        final int[] expectActiveDurations = new int[] {startTime, writeTime - startTime};
        assertDailyKeepaliveInfoReported(
                dailyKeepaliveInfoReported,
                expectRegisteredDurations,
                expectActiveDurations,
                new KeepaliveCarrierStats[] {
                    getDefaultCarrierStats(expectRegisteredDurations[1], expectActiveDurations[1])
                });
    }

    /*
     * Diagram of test (not to scale):
     * Key: S - Start/Stop, P - Pause, R - Resume, W - Write
     *
     * Keepalive     S       P                  W
     * Timeline  |------------------------------|
     */
    @Test
    public void testOneKeepalive_paused() {
        final int startTime = 1000;
        final int pauseTime = 2030;
        final int writeTime = 5000;

        setUptimeMillis(startTime);
        mKeepaliveStatsTracker.onStartKeepalive(
                TEST_NETWORK,
                /* slot= */ 1,
                TEST_NETWORK_CAPABILITIES,
                TEST_KEEPALIVE_INTERVAL_SEC);

        setUptimeMillis(pauseTime);
        mKeepaliveStatsTracker.onPauseKeepalive(TEST_NETWORK, /* slot= */ 1);

        setUptimeMillis(writeTime);
        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                mKeepaliveStatsTracker.buildKeepaliveMetrics();

        // The keepalive is paused but not stopped, expect the registered duration for
        // numberOfKeepalive of 1 to still range from startTime to writeTime while the active
        // duration stops at pauseTime.
        final int[] expectRegisteredDurations = new int[] {startTime, writeTime - startTime};
        final int[] expectActiveDurations =
                new int[] {startTime + (writeTime - pauseTime), pauseTime - startTime};
        assertDailyKeepaliveInfoReported(
                dailyKeepaliveInfoReported,
                expectRegisteredDurations,
                expectActiveDurations,
                new KeepaliveCarrierStats[] {
                    getDefaultCarrierStats(expectRegisteredDurations[1], expectActiveDurations[1])
                });
    }

    /*
     * Diagram of test (not to scale):
     * Key: S - Start/Stop, P - Pause, R - Resume, W - Write
     *
     * Keepalive     S       P        R         W
     * Timeline  |------------------------------|
     */
    @Test
    public void testOneKeepalive_resumed() {
        final int startTime = 1000;
        final int pauseTime = 2030;
        final int resumeTime = 3450;
        final int writeTime = 5000;

        setUptimeMillis(startTime);
        mKeepaliveStatsTracker.onStartKeepalive(
                TEST_NETWORK,
                /* slot= */ 1,
                TEST_NETWORK_CAPABILITIES,
                TEST_KEEPALIVE_INTERVAL_SEC);

        setUptimeMillis(pauseTime);
        mKeepaliveStatsTracker.onPauseKeepalive(TEST_NETWORK, /* slot= */ 1);

        setUptimeMillis(resumeTime);
        mKeepaliveStatsTracker.onResumeKeepalive(TEST_NETWORK, /* slot= */ 1);

        setUptimeMillis(writeTime);
        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                mKeepaliveStatsTracker.buildKeepaliveMetrics();

        // The keepalive is paused and resumed but not stopped, expect the registered duration for
        // numberOfKeepalive of 1 to still range from startTime to writeTime while the active
        // duration stops at pauseTime but resumes at resumeTime and stops at writeTime.
        final int[] expectRegisteredDurations = new int[] {startTime, writeTime - startTime};
        final int[] expectActiveDurations =
                new int[] {
                    startTime + (resumeTime - pauseTime),
                    (pauseTime - startTime) + (writeTime - resumeTime)
                };
        assertDailyKeepaliveInfoReported(
                dailyKeepaliveInfoReported,
                expectRegisteredDurations,
                expectActiveDurations,
                new KeepaliveCarrierStats[] {
                    getDefaultCarrierStats(expectRegisteredDurations[1], expectActiveDurations[1])
                });
    }

    /*
     * Diagram of test (not to scale):
     * Key: S - Start/Stop, P - Pause, R - Resume, W - Write
     *
     * Keepalive     S       P      R     S     W
     * Timeline  |------------------------------|
     */
    @Test
    public void testOneKeepalive_stopped() {
        final int startTime = 1000;
        final int pauseTime = 2930;
        final int resumeTime = 3452;
        final int stopTime = 4157;
        final int writeTime = 5000;

        setUptimeMillis(startTime);
        mKeepaliveStatsTracker.onStartKeepalive(
                TEST_NETWORK,
                /* slot= */ 1,
                TEST_NETWORK_CAPABILITIES,
                TEST_KEEPALIVE_INTERVAL_SEC);

        setUptimeMillis(pauseTime);
        mKeepaliveStatsTracker.onPauseKeepalive(TEST_NETWORK, /* slot= */ 1);

        setUptimeMillis(resumeTime);
        mKeepaliveStatsTracker.onResumeKeepalive(TEST_NETWORK, /* slot= */ 1);

        setUptimeMillis(stopTime);
        mKeepaliveStatsTracker.onStopKeepalive(TEST_NETWORK, /* slot= */ 1);

        setUptimeMillis(writeTime);
        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                mKeepaliveStatsTracker.buildKeepaliveMetrics();

        // The keepalive is now stopped, expect the registered duration for numberOfKeepalive of 1
        // to now range from startTime to stopTime while the active duration stops at pauseTime but
        // resumes at resumeTime and stops again at stopTime.
        final int[] expectRegisteredDurations =
                new int[] {startTime + (writeTime - stopTime), stopTime - startTime};
        final int[] expectActiveDurations =
                new int[] {
                    startTime + (resumeTime - pauseTime) + (writeTime - stopTime),
                    (pauseTime - startTime) + (stopTime - resumeTime)
                };
        assertDailyKeepaliveInfoReported(
                dailyKeepaliveInfoReported,
                expectRegisteredDurations,
                expectActiveDurations,
                new KeepaliveCarrierStats[] {
                    getDefaultCarrierStats(expectRegisteredDurations[1], expectActiveDurations[1])
                });
    }

    /*
     * Diagram of test (not to scale):
     * Key: S - Start/Stop, P - Pause, R - Resume, W - Write
     *
     * Keepalive     S       P            S     W
     * Timeline  |------------------------------|
     */
    @Test
    public void testOneKeepalive_pausedStopped() {
        final int startTime = 1000;
        final int pauseTime = 2930;
        final int stopTime = 4157;
        final int writeTime = 5000;

        setUptimeMillis(startTime);
        mKeepaliveStatsTracker.onStartKeepalive(
                TEST_NETWORK,
                /* slot= */ 1,
                TEST_NETWORK_CAPABILITIES,
                TEST_KEEPALIVE_INTERVAL_SEC);

        setUptimeMillis(pauseTime);
        mKeepaliveStatsTracker.onPauseKeepalive(TEST_NETWORK, /* slot= */ 1);

        setUptimeMillis(stopTime);
        mKeepaliveStatsTracker.onStopKeepalive(TEST_NETWORK, /* slot= */ 1);

        setUptimeMillis(writeTime);
        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                mKeepaliveStatsTracker.buildKeepaliveMetrics();

        // The keepalive is stopped while paused, expect the registered duration for
        // numberOfKeepalive of 1 to range from startTime to stopTime while the active duration
        // simply stops at pauseTime.
        final int[] expectRegisteredDurations =
                new int[] {startTime + (writeTime - stopTime), stopTime - startTime};
        final int[] expectActiveDurations =
                new int[] {startTime + (writeTime - pauseTime), (pauseTime - startTime)};
        assertDailyKeepaliveInfoReported(
                dailyKeepaliveInfoReported,
                expectRegisteredDurations,
                expectActiveDurations,
                new KeepaliveCarrierStats[] {
                    getDefaultCarrierStats(expectRegisteredDurations[1], expectActiveDurations[1])
                });
    }

    /*
     * Diagram of test (not to scale):
     * Key: S - Start/Stop, P - Pause, R - Resume, W - Write
     *
     * Keepalive     S  P R P R P R       S     W
     * Timeline  |------------------------------|
     */
    @Test
    public void testOneKeepalive_multiplePauses() {
        final int startTime = 1000;
        // Alternating timestamps of pause and resume
        final int[] pauseResumeTimes = new int[] {1200, 1400, 1700, 2000, 2400, 2800};
        final int stopTime = 4000;
        final int writeTime = 5000;

        setUptimeMillis(startTime);
        mKeepaliveStatsTracker.onStartKeepalive(
                TEST_NETWORK,
                /* slot= */ 1,
                TEST_NETWORK_CAPABILITIES,
                TEST_KEEPALIVE_INTERVAL_SEC);

        for (int i = 0; i < pauseResumeTimes.length; i++) {
            setUptimeMillis(pauseResumeTimes[i]);
            if (i % 2 == 0) {
                mKeepaliveStatsTracker.onPauseKeepalive(TEST_NETWORK, /* slot= */ 1);

            } else {
                mKeepaliveStatsTracker.onResumeKeepalive(TEST_NETWORK, /* slot= */ 1);
            }
        }

        setUptimeMillis(stopTime);
        mKeepaliveStatsTracker.onStopKeepalive(TEST_NETWORK, /* slot= */ 1);

        setUptimeMillis(writeTime);
        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                mKeepaliveStatsTracker.buildKeepaliveMetrics();

        final int[] expectRegisteredDurations =
                new int[] {startTime + (writeTime - stopTime), stopTime - startTime};
        final int[] expectActiveDurations =
                new int[] {
                    startTime + /* sum of (Resume - Pause) */ (900) + (writeTime - stopTime),
                    (pauseResumeTimes[0] - startTime)
                            + /* sum of (Pause - Resume) */ (700)
                            + (stopTime - pauseResumeTimes[5])
                };
        assertDailyKeepaliveInfoReported(
                dailyKeepaliveInfoReported,
                expectRegisteredDurations,
                expectActiveDurations,
                new KeepaliveCarrierStats[] {
                    getDefaultCarrierStats(expectRegisteredDurations[1], expectActiveDurations[1])
                });
    }

    /*
     * Diagram of test (not to scale):
     * Key: S - Start/Stop, P - Pause, R - Resume, W - Write
     *
     * Keepalive1    S1  P1     R1         S1    W
     * Keepalive2           S2     P2   R2       W
     * Timeline   |------------------------------|
     */
    @Test
    public void testTwoKeepalives() {
        // The suffix 1/2 indicates which keepalive it is referring to.
        final int startTime1 = 1000;
        final int pauseTime1 = 1500;
        final int startTime2 = 2000;
        final int resumeTime1 = 2500;
        final int pauseTime2 = 3000;
        final int resumeTime2 = 3500;
        final int stopTime1 = 4157;
        final int writeTime = 5000;

        setUptimeMillis(startTime1);
        mKeepaliveStatsTracker.onStartKeepalive(
                TEST_NETWORK,
                /* slot= */ 1,
                TEST_NETWORK_CAPABILITIES,
                TEST_KEEPALIVE_INTERVAL_SEC);

        setUptimeMillis(pauseTime1);
        mKeepaliveStatsTracker.onPauseKeepalive(TEST_NETWORK, /* slot= */ 1);

        setUptimeMillis(startTime2);
        mKeepaliveStatsTracker.onStartKeepalive(
                TEST_NETWORK,
                /* slot= */ 2,
                TEST_NETWORK_CAPABILITIES,
                TEST_KEEPALIVE_INTERVAL_SEC);

        setUptimeMillis(resumeTime1);
        mKeepaliveStatsTracker.onResumeKeepalive(TEST_NETWORK, /* slot= */ 1);

        setUptimeMillis(pauseTime2);
        mKeepaliveStatsTracker.onPauseKeepalive(TEST_NETWORK, /* slot= */ 2);

        setUptimeMillis(resumeTime2);
        mKeepaliveStatsTracker.onResumeKeepalive(TEST_NETWORK, /* slot= */ 2);

        setUptimeMillis(stopTime1);
        mKeepaliveStatsTracker.onStopKeepalive(TEST_NETWORK, /* slot= */ 1);

        setUptimeMillis(writeTime);
        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                mKeepaliveStatsTracker.buildKeepaliveMetrics();

        // With two keepalives, the number of concurrent keepalives can vary from 0-2 depending on
        // both keepalive states.
        final int[] expectRegisteredDurations =
                new int[] {
                    startTime1,
                    // 1 registered keepalive before keepalive2 starts and after keepalive1 stops.
                    (startTime2 - startTime1) + (writeTime - stopTime1),
                    // 2 registered keepalives between keepalive2 start and keepalive1 stop.
                    stopTime1 - startTime2
                };

        final int[] expectActiveDurations =
                new int[] {
                    // 0 active keepalives when keepalive1 is paused before keepalive2 starts.
                    startTime1 + (startTime2 - pauseTime1),
                    // 1 active keepalive before keepalive1 is paused.
                    (pauseTime1 - startTime1)
                            // before keepalive1 is resumed and after keepalive2 starts.
                            + (resumeTime1 - startTime2)
                            // during keepalive2 is paused since keepalive1 has been resumed.
                            + (resumeTime2 - pauseTime2)
                            // after keepalive1 stops since keepalive2 has been resumed.
                            + (writeTime - stopTime1),
                    // 2 active keepalives before keepalive2 is paused and before keepalive1 stops.
                    (pauseTime2 - resumeTime1) + (stopTime1 - resumeTime2)
                };

        assertDailyKeepaliveInfoReported(
                dailyKeepaliveInfoReported,
                expectRegisteredDurations,
                expectActiveDurations,
                // The carrier stats are aggregated here since the keepalives have the same
                // (carrierId, transportTypes, intervalMsec).
                new KeepaliveCarrierStats[] {
                    getDefaultCarrierStats(
                            expectRegisteredDurations[1] + 2 * expectRegisteredDurations[2],
                            expectActiveDurations[1] + 2 * expectActiveDurations[2])
                });
    }

    /*
     * Diagram of test (not to scale):
     * Key: S - Start/Stop, P - Pause, R - Resume, W - Write
     *
     * Keepalive     S   W(reset+W)         S    W
     * Timeline   |------------------------------|
     */
    @Test
    public void testResetMetrics() {
        final int startTime = 1000;
        final int writeTime = 5000;
        final int stopTime = 7000;
        final int writeTime2 = 10000;

        setUptimeMillis(startTime);
        mKeepaliveStatsTracker.onStartKeepalive(
                TEST_NETWORK,
                /* slot= */ 1,
                TEST_NETWORK_CAPABILITIES,
                TEST_KEEPALIVE_INTERVAL_SEC);

        setUptimeMillis(writeTime);
        // Also reset metrics
        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                mKeepaliveStatsTracker.buildAndResetMetrics();

        // Same expect as testOneKeepalive_startOnly
        final int[] expectRegisteredDurations = new int[] {startTime, writeTime - startTime};
        final int[] expectActiveDurations = new int[] {startTime, writeTime - startTime};
        assertDailyKeepaliveInfoReported(
                dailyKeepaliveInfoReported,
                expectRegisteredDurations,
                expectActiveDurations,
                new KeepaliveCarrierStats[] {
                    getDefaultCarrierStats(expectRegisteredDurations[1], expectActiveDurations[1])
                });

        // Check metrics was reset from above.
        final DailykeepaliveInfoReported dailyKeepaliveInfoReported2 =
                mKeepaliveStatsTracker.buildKeepaliveMetrics();
        // Expect the stored durations to be 0 but still contain the number of keepalive = 1.
        assertDailyKeepaliveInfoReported(
                dailyKeepaliveInfoReported2,
                /* expectRegisteredDurations= */ new int[] {0, 0},
                /* expectActiveDurations= */ new int[] {0, 0},
                new KeepaliveCarrierStats[] {getDefaultCarrierStats(0, 0)});

        // Expect that the keepalive is still registered after resetting so it can be stopped.
        setUptimeMillis(stopTime);
        mKeepaliveStatsTracker.onStopKeepalive(TEST_NETWORK, /* slot= */ 1);

        setUptimeMillis(writeTime2);
        final DailykeepaliveInfoReported dailyKeepaliveInfoReported3 =
                mKeepaliveStatsTracker.buildKeepaliveMetrics();

        final int[] expectRegisteredDurations2 =
                new int[] {writeTime2 - stopTime, stopTime - writeTime};
        final int[] expectActiveDurations2 =
                new int[] {writeTime2 - stopTime, stopTime - writeTime};
        assertDailyKeepaliveInfoReported(
                dailyKeepaliveInfoReported3,
                expectRegisteredDurations2,
                expectActiveDurations2,
                new KeepaliveCarrierStats[] {
                    getDefaultCarrierStats(expectRegisteredDurations2[1], expectActiveDurations2[1])
                });
    }

    /*
     * Diagram of test (not to scale):
     * Key: S - Start/Stop, P - Pause, R - Resume, W - Write
     *
     * Keepalive1     S1      S1  W+reset         W
     * Keepalive2         S2      W+reset         W
     * Timeline    |------------------------------|
     */
    @Test
    public void testResetMetrics_twoKeepalives() {
        final int startTime1 = 1000;
        final int startTime2 = 2000;
        final int stopTime1 = 4157;
        final int writeTime = 5000;
        final int writeTime2 = 10000;

        setUptimeMillis(startTime1);
        mKeepaliveStatsTracker.onStartKeepalive(
                TEST_NETWORK,
                /* slot= */ 1,
                TEST_NETWORK_CAPABILITIES,
                TEST_KEEPALIVE_INTERVAL_SEC);

        final int intervalSec2 = 2;
        setUptimeMillis(startTime2);
        mKeepaliveStatsTracker.onStartKeepalive(
                TEST_NETWORK, /* slot= */ 2, TEST_NETWORK_CAPABILITIES, intervalSec2);

        setUptimeMillis(stopTime1);
        mKeepaliveStatsTracker.onStopKeepalive(TEST_NETWORK, /* slot= */ 1);

        setUptimeMillis(writeTime);
        // Also reset metrics
        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                mKeepaliveStatsTracker.buildAndResetMetrics();

        final int[] expectRegisteredDurations =
                new int[] {
                    startTime1,
                    // 1 keepalive before keepalive2 starts and after keepalive1 stops.
                    (startTime2 - startTime1) + (writeTime - stopTime1),
                    stopTime1 - startTime2
                };
        // Since there is no pause, expect the same as registered durations.
        final int[] expectActiveDurations =
                new int[] {
                    startTime1,
                    (startTime2 - startTime1) + (writeTime - stopTime1),
                    stopTime1 - startTime2
                };

        // Lifetime carrier stats are independent of each other since they have different intervals.
        final KeepaliveCarrierStats expectKeepaliveCarrierStats1 =
                getDefaultCarrierStats(stopTime1 - startTime1, stopTime1 - startTime1);
        final KeepaliveCarrierStats expectKeepaliveCarrierStats2 =
                new KeepaliveCarrierStats(
                        TEST_CARRIER_ID,
                        /* (cellular) transportTypes= */ 1,
                        intervalSec2 * 1000,
                        writeTime - startTime2,
                        writeTime - startTime2);

        assertDailyKeepaliveInfoReported(
                dailyKeepaliveInfoReported,
                expectRegisteredDurations,
                expectActiveDurations,
                new KeepaliveCarrierStats[] {
                    expectKeepaliveCarrierStats1, expectKeepaliveCarrierStats2
                });

        setUptimeMillis(writeTime2);
        final DailykeepaliveInfoReported dailyKeepaliveInfoReported2 =
                mKeepaliveStatsTracker.buildKeepaliveMetrics();

        // Only 1 keepalive is registered and active since the reset until the writeTime2.
        final int[] expectRegisteredDurations2 = new int[] {0, writeTime2 - writeTime};
        final int[] expectActiveDurations2 = new int[] {0, writeTime2 - writeTime};

        // Only the keepalive with interval of intervalSec2 is present.
        final KeepaliveCarrierStats expectKeepaliveCarrierStats3 =
                new KeepaliveCarrierStats(
                        TEST_CARRIER_ID,
                        /* (cellular) transportTypes= */ 1,
                        intervalSec2 * 1000,
                        writeTime2 - writeTime,
                        writeTime2 - writeTime);

        assertDailyKeepaliveInfoReported(
                dailyKeepaliveInfoReported2,
                expectRegisteredDurations2,
                expectActiveDurations2,
                new KeepaliveCarrierStats[] {expectKeepaliveCarrierStats3});
    }

    @Test
    public void testReusableSlot_keepaliveNotStopped() {
        final int startTime1 = 1000;
        final int startTime2 = 2000;
        final int writeTime = 5000;

        setUptimeMillis(startTime1);
        mKeepaliveStatsTracker.onStartKeepalive(
                TEST_NETWORK,
                /* slot= */ 1,
                TEST_NETWORK_CAPABILITIES,
                TEST_KEEPALIVE_INTERVAL_SEC);

        // Attempt to use the same (network, slot)
        setUptimeMillis(startTime2);
        mKeepaliveStatsTracker.onStartKeepalive(
                TEST_NETWORK,
                /* slot= */ 1,
                TEST_NETWORK_CAPABILITIES,
                TEST_KEEPALIVE_INTERVAL_SEC);

        setUptimeMillis(writeTime);
        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                mKeepaliveStatsTracker.buildKeepaliveMetrics();

        // Expect the duration to be from startTime1 and not startTime2, it should not start again.
        final int[] expectRegisteredDurations = new int[] {startTime1, writeTime - startTime1};
        final int[] expectActiveDurations = new int[] {startTime1, writeTime - startTime1};

        assertDailyKeepaliveInfoReported(
                dailyKeepaliveInfoReported,
                expectRegisteredDurations,
                expectActiveDurations,
                new KeepaliveCarrierStats[] {
                    getDefaultCarrierStats(expectRegisteredDurations[1], expectActiveDurations[1])
                });
    }

    @Test
    public void testReusableSlot_keepaliveStopped() {
        final int startTime1 = 1000;
        final int stopTime = 2000;
        final int startTime2 = 3000;
        final int writeTime = 5000;

        setUptimeMillis(startTime1);
        mKeepaliveStatsTracker.onStartKeepalive(
                TEST_NETWORK,
                /* slot= */ 1,
                TEST_NETWORK_CAPABILITIES,
                TEST_KEEPALIVE_INTERVAL_SEC);

        setUptimeMillis(stopTime);
        mKeepaliveStatsTracker.onStopKeepalive(TEST_NETWORK, /* slot= */ 1);

        // Attempt to use the same (network, slot)
        setUptimeMillis(startTime2);
        mKeepaliveStatsTracker.onStartKeepalive(
                TEST_NETWORK,
                /* slot= */ 1,
                TEST_NETWORK_CAPABILITIES,
                TEST_KEEPALIVE_INTERVAL_SEC);

        setUptimeMillis(writeTime);
        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                mKeepaliveStatsTracker.buildKeepaliveMetrics();

        // Expect the durations to be an aggregate of both periods.
        // i.e. onStartKeepalive works on the same (network, slot) if it has been stopped.
        final int[] expectRegisteredDurations =
                new int[] {
                    startTime1 + (startTime2 - stopTime),
                    (stopTime - startTime1) + (writeTime - startTime2)
                };
        final int[] expectActiveDurations =
                new int[] {
                    startTime1 + (startTime2 - stopTime),
                    (stopTime - startTime1) + (writeTime - startTime2)
                };

        assertDailyKeepaliveInfoReported(
                dailyKeepaliveInfoReported,
                expectRegisteredDurations,
                expectActiveDurations,
                new KeepaliveCarrierStats[] {
                    getDefaultCarrierStats(expectRegisteredDurations[1], expectActiveDurations[1])
                });
    }
}
