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

import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;

import static com.android.testutils.ContextUtils.mockService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TelephonyNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener;
import android.telephony.TelephonyManager;

import androidx.test.filters.SmallTest;

import com.android.metrics.DailykeepaliveInfoReported;
import com.android.metrics.DurationForNumOfKeepalive;
import com.android.metrics.DurationPerNumOfKeepalive;
import com.android.metrics.KeepaliveLifetimeForCarrier;
import com.android.metrics.KeepaliveLifetimePerCarrier;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;
import com.android.testutils.HandlerUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

@RunWith(DevSdkIgnoreRunner.class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
public class KeepaliveStatsTrackerTest {
    private static final int TIMEOUT_MS = 30_000;
    private static final int TEST_KEEPALIVE_INTERVAL_SEC = 10;
    private static final int TEST_KEEPALIVE_INTERVAL_SEC_2 = 20;
    private static final int TEST_SUB_ID_1 = 1;
    private static final int TEST_SUB_ID_2 = 2;
    private static final int TEST_CARRIER_ID_1 = 135;
    private static final int TEST_CARRIER_ID_2 = 246;
    private static final Network TEST_NETWORK = new Network(123);
    private static final NetworkCapabilities TEST_NETWORK_CAPABILITIES =
            buildNetworkCapabilities(TEST_SUB_ID_1);
    private static final NetworkCapabilities TEST_NETWORK_CAPABILITIES_2 =
            buildNetworkCapabilities(TEST_SUB_ID_2);

    private static NetworkCapabilities buildNetworkCapabilities(int subId) {
        final TelephonyNetworkSpecifier telephonyNetworkSpecifier =
                new TelephonyNetworkSpecifier.Builder().setSubscriptionId(subId).build();
        return new NetworkCapabilities.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .setNetworkSpecifier(telephonyNetworkSpecifier)
                .build();
    }

    private HandlerThread mHandlerThread;
    private Handler mTestHandler;
    private DailykeepaliveInfoReported mDailyKeepaliveInfoReported;
    private Exception mThrownException = null;

    private KeepaliveStatsTracker mKeepaliveStatsTracker;

    @Mock private Context mContext;
    @Mock private KeepaliveStatsTracker.Dependencies mDependencies;
    @Mock private SubscriptionManager mSubscriptionManager;
    @Mock private SubscriptionInfo mSubInfo1;
    @Mock private SubscriptionInfo mSubInfo2;

    @Captor private ArgumentCaptor<OnSubscriptionsChangedListener> mListenerCaptor;

    private OnSubscriptionsChangedListener getOnSubscriptionsChangedListener() {
        verify(mSubscriptionManager)
                .addOnSubscriptionsChangedListener(any(), mListenerCaptor.capture());
        return mListenerCaptor.getValue();
    }

    private static final class KeepaliveCarrierStats {
        public final int carrierId;
        public final int transportTypes;
        public final int intervalsMs;
        public final int lifetimeMs;
        public final int activeLifetimeMs;

        KeepaliveCarrierStats(
                int carrierId,
                int transportTypes,
                int intervalsMs,
                int lifetimeMs,
                int activeLifetimeMs) {
            this.carrierId = carrierId;
            this.transportTypes = transportTypes;
            this.intervalsMs = intervalsMs;
            this.lifetimeMs = lifetimeMs;
            this.activeLifetimeMs = activeLifetimeMs;
        }

        // Equals method on only the key, (carrierId, tranportTypes, intervalsMs)
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            KeepaliveCarrierStats that = (KeepaliveCarrierStats) o;

            if (carrierId != that.carrierId) return false;
            if (transportTypes != that.transportTypes) return false;
            return intervalsMs == that.intervalsMs;
        }
    }

    // Use the default test carrier id, transportType and keepalive interval.
    private KeepaliveCarrierStats getDefaultCarrierStats(int lifetimeMs, int activeLifetimeMs) {
        return new KeepaliveCarrierStats(
                TEST_CARRIER_ID_1,
                /* transportTypes= */ (1 << TRANSPORT_CELLULAR),
                TEST_KEEPALIVE_INTERVAL_SEC * 1000,
                lifetimeMs,
                activeLifetimeMs);
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mockService(
                mContext,
                SubscriptionManager.class,
                Context.TELEPHONY_SUBSCRIPTION_SERVICE,
                mSubscriptionManager);

        doReturn(TEST_SUB_ID_1).when(mSubInfo1).getSubscriptionId();
        doReturn(TEST_CARRIER_ID_1).when(mSubInfo1).getCarrierId();

        doReturn(TEST_SUB_ID_2).when(mSubInfo2).getSubscriptionId();
        doReturn(TEST_CARRIER_ID_2).when(mSubInfo2).getCarrierId();

        doReturn(List.of(mSubInfo1, mSubInfo2))
                .when(mSubscriptionManager)
                .getActiveSubscriptionInfoList();

        mHandlerThread = new HandlerThread("KeepaliveStatsTrackerTest");
        mHandlerThread.start();
        mTestHandler = new Handler(mHandlerThread.getLooper());

        setUptimeMillis(0);
        mKeepaliveStatsTracker = new KeepaliveStatsTracker(mContext, mTestHandler, mDependencies);

        // Initial onSubscriptionsChanged.
        getOnSubscriptionsChangedListener().onSubscriptionsChanged();
        HandlerUtils.waitForIdle(mTestHandler, TIMEOUT_MS);
    }

    private void setUptimeMillis(long time) {
        doReturn(time).when(mDependencies).getUptimeMillis();
    }

    private void runOnHandlerThread(Runnable r) {
        mThrownException = null;
        mTestHandler.post(() -> {
            try {
                r.run();
            } catch (Exception e) {
                mThrownException = e;
            }
        });
        HandlerUtils.waitForIdle(mTestHandler, TIMEOUT_MS);

        assertNull(mThrownException);
    }

    private DailykeepaliveInfoReported buildKeepaliveMetrics(long time) {
        mDailyKeepaliveInfoReported = null;
        setUptimeMillis(time);
        runOnHandlerThread(() -> {
            mDailyKeepaliveInfoReported = mKeepaliveStatsTracker.buildKeepaliveMetrics();
        });

        return mDailyKeepaliveInfoReported;
    }

    private DailykeepaliveInfoReported buildAndResetMetrics(long time) {
        mDailyKeepaliveInfoReported = null;
        setUptimeMillis(time);
        runOnHandlerThread(() -> {
            mDailyKeepaliveInfoReported = mKeepaliveStatsTracker.buildAndResetMetrics();
        });

        return mDailyKeepaliveInfoReported;
    }

    private void onStartKeepalive(long time, int slot) {
        onStartKeepalive(time, slot, TEST_KEEPALIVE_INTERVAL_SEC);
    }

    private void onStartKeepalive(long time, int slot, int intervalSeconds) {
        onStartKeepalive(time, slot, TEST_NETWORK_CAPABILITIES, intervalSeconds);
    }

    private void onStartKeepalive(long time, int slot, NetworkCapabilities nc) {
        onStartKeepalive(time, slot, nc, TEST_KEEPALIVE_INTERVAL_SEC);
    }

    private void onStartKeepalive(
            long time, int slot, NetworkCapabilities nc, int intervalSeconds) {
        setUptimeMillis(time);
        runOnHandlerThread(() ->
                mKeepaliveStatsTracker.onStartKeepalive(TEST_NETWORK, slot, nc, intervalSeconds));
    }

    private void onPauseKeepalive(long time, int slot) {
        setUptimeMillis(time);
        runOnHandlerThread(() -> mKeepaliveStatsTracker.onPauseKeepalive(TEST_NETWORK, slot));
    }

    private void onResumeKeepalive(long time, int slot) {
        setUptimeMillis(time);
        runOnHandlerThread(() -> mKeepaliveStatsTracker.onResumeKeepalive(TEST_NETWORK, slot));
    }

    private void onStopKeepalive(long time, int slot) {
        setUptimeMillis(time);
        runOnHandlerThread(() -> mKeepaliveStatsTracker.onStopKeepalive(TEST_NETWORK, slot));
    }

    @Test
    public void testEnsureRunningOnHandlerThread() {
        // Not running on handler thread
        assertThrows(
                IllegalStateException.class,
                () ->
                        mKeepaliveStatsTracker.onStartKeepalive(
                                TEST_NETWORK,
                                1,
                                TEST_NETWORK_CAPABILITIES,
                                TEST_KEEPALIVE_INTERVAL_SEC));
        assertThrows(
                IllegalStateException.class,
                () -> mKeepaliveStatsTracker.onPauseKeepalive(TEST_NETWORK, 1));
        assertThrows(
                IllegalStateException.class,
                () -> mKeepaliveStatsTracker.onResumeKeepalive(TEST_NETWORK, 1));
        assertThrows(
                IllegalStateException.class,
                () -> mKeepaliveStatsTracker.onStopKeepalive(TEST_NETWORK, 1));
        assertThrows(
                IllegalStateException.class, () -> mKeepaliveStatsTracker.buildKeepaliveMetrics());
        assertThrows(
                IllegalStateException.class, () -> mKeepaliveStatsTracker.buildAndResetMetrics());
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
     * @param actualDurationsPerNumOfKeepalive the DurationPerNumOfKeepalive message to assert.
     */
    private void assertDurationMetrics(
            int[] expectRegisteredDurations,
            int[] expectActiveDurations,
            DurationPerNumOfKeepalive actualDurationsPerNumOfKeepalive) {
        final int maxNumOfKeepalive = expectRegisteredDurations.length;
        assertEquals(maxNumOfKeepalive, expectActiveDurations.length);
        assertEquals(
                maxNumOfKeepalive,
                actualDurationsPerNumOfKeepalive.getDurationForNumOfKeepaliveCount());
        for (int numOfKeepalive = 0; numOfKeepalive < maxNumOfKeepalive; numOfKeepalive++) {
            final DurationForNumOfKeepalive actualDurations =
                    actualDurationsPerNumOfKeepalive.getDurationForNumOfKeepalive(numOfKeepalive);

            assertEquals(numOfKeepalive, actualDurations.getNumOfKeepalive());
            assertEquals(
                    expectRegisteredDurations[numOfKeepalive],
                    actualDurations.getKeepaliveRegisteredDurationsMsec());
            assertEquals(
                    expectActiveDurations[numOfKeepalive],
                    actualDurations.getKeepaliveActiveDurationsMsec());
        }
    }

    /**
     * Asserts the actual KeepaliveLifetimePerCarrier contains an expected KeepaliveCarrierStats.
     * This finds and checks only for the (carrierId, transportTypes, intervalsMs) of the given
     * expectKeepaliveCarrierStats and asserts the lifetime metrics.
     *
     * @param expectKeepaliveCarrierStats a keepalive lifetime metric that is expected to be in the
     *     proto.
     * @param actualKeepaliveLifetimePerCarrier the KeepaliveLifetimePerCarrier message to assert.
     */
    private void findAndAssertCarrierLifetimeMetrics(
            KeepaliveCarrierStats expectKeepaliveCarrierStats,
            KeepaliveLifetimePerCarrier actualKeepaliveLifetimePerCarrier) {
        for (KeepaliveLifetimeForCarrier keepaliveLifetimeForCarrier :
                actualKeepaliveLifetimePerCarrier.getKeepaliveLifetimeForCarrierList()) {
            if (expectKeepaliveCarrierStats.carrierId == keepaliveLifetimeForCarrier.getCarrierId()
                    && expectKeepaliveCarrierStats.transportTypes
                            == keepaliveLifetimeForCarrier.getTransportTypes()
                    && expectKeepaliveCarrierStats.intervalsMs
                            == keepaliveLifetimeForCarrier.getIntervalsMsec()) {
                assertEquals(
                        expectKeepaliveCarrierStats.lifetimeMs,
                        keepaliveLifetimeForCarrier.getLifetimeMsec());
                assertEquals(
                        expectKeepaliveCarrierStats.activeLifetimeMs,
                        keepaliveLifetimeForCarrier.getActiveLifetimeMsec());
                return;
            }
        }
        fail("KeepaliveLifetimeForCarrier not found for a given expected KeepaliveCarrierStats");
    }

    private void assertUniqueArray(Object[] arr) {
        for (int i = 0; i < arr.length; i++) {
            for (int j = i + 1; j < arr.length; j++) {
                assertFalse(arr[i].equals(arr[j]));
            }
        }
    }

    /**
     * Asserts that a KeepaliveLifetimePerCarrier contains all the expected KeepaliveCarrierStats.
     *
     * @param expectKeepaliveCarrierStatsArray an array of keepalive lifetime metrics that is
     *     expected to be in the KeepaliveLifetimePerCarrier.
     * @param actualKeepaliveLifetimePerCarrier the KeepaliveLifetimePerCarrier message to assert.
     */
    private void assertCarrierLifetimeMetrics(
            KeepaliveCarrierStats[] expectKeepaliveCarrierStatsArray,
            KeepaliveLifetimePerCarrier actualKeepaliveLifetimePerCarrier) {
        // Ensure expected array does not have duplicates.
        assertUniqueArray(expectKeepaliveCarrierStatsArray);
        assertEquals(
                expectKeepaliveCarrierStatsArray.length,
                actualKeepaliveLifetimePerCarrier.getKeepaliveLifetimeForCarrierCount());
        for (KeepaliveCarrierStats keepaliveCarrierStats : expectKeepaliveCarrierStatsArray) {
            findAndAssertCarrierLifetimeMetrics(
                    keepaliveCarrierStats, actualKeepaliveLifetimePerCarrier);
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

        final DurationPerNumOfKeepalive actualDurations =
                dailyKeepaliveInfoReported.getDurationPerNumOfKeepalive();
        assertDurationMetrics(expectRegisteredDurations, expectActiveDurations, actualDurations);

        final KeepaliveLifetimePerCarrier actualCarrierLifetime =
                dailyKeepaliveInfoReported.getKeepaliveLifetimePerCarrier();

        assertCarrierLifetimeMetrics(expectKeepaliveCarrierStatsArray, actualCarrierLifetime);
    }

    @Test
    public void testNoKeepalive() {
        final int writeTime = 5000;

        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                buildKeepaliveMetrics(writeTime);

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

        onStartKeepalive(startTime, /* slot= */ 1);

        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                buildKeepaliveMetrics(writeTime);

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

        onStartKeepalive(startTime, /* slot= */ 1);

        onPauseKeepalive(pauseTime, /* slot= */ 1);

        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                buildKeepaliveMetrics(writeTime);

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

        onStartKeepalive(startTime, /* slot= */ 1);

        onPauseKeepalive(pauseTime, /* slot= */ 1);

        onResumeKeepalive(resumeTime, /* slot= */ 1);

        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                buildKeepaliveMetrics(writeTime);

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

        onStartKeepalive(startTime, /* slot= */ 1);

        onPauseKeepalive(pauseTime, /* slot= */ 1);

        onResumeKeepalive(resumeTime, /* slot= */ 1);

        onStopKeepalive(stopTime, /* slot= */ 1);

        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                buildKeepaliveMetrics(writeTime);

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

        onStartKeepalive(startTime, /* slot= */ 1);

        onPauseKeepalive(pauseTime, /* slot= */ 1);

        onStopKeepalive(stopTime, /* slot= */ 1);

        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                buildKeepaliveMetrics(writeTime);

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

        onStartKeepalive(startTime, /* slot= */ 1);

        for (int i = 0; i < pauseResumeTimes.length; i++) {
            if (i % 2 == 0) {
                onPauseKeepalive(pauseResumeTimes[i], /* slot= */ 1);
            } else {
                onResumeKeepalive(pauseResumeTimes[i], /* slot= */ 1);
            }
        }

        onStopKeepalive(stopTime, /* slot= */ 1);

        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                buildKeepaliveMetrics(writeTime);

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

        onStartKeepalive(startTime1, /* slot= */ 1);

        onPauseKeepalive(pauseTime1, /* slot= */ 1);

        onStartKeepalive(startTime2, /* slot= */ 2);

        onResumeKeepalive(resumeTime1, /* slot= */ 1);

        onPauseKeepalive(pauseTime2, /* slot= */ 2);

        onResumeKeepalive(resumeTime2, /* slot= */ 2);

        onStopKeepalive(stopTime1, /* slot= */ 1);

        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                buildKeepaliveMetrics(writeTime);

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
                // (carrierId, transportTypes, intervalMs).
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

        onStartKeepalive(startTime, /* slot= */ 1);

        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                buildAndResetMetrics(writeTime);

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
                buildKeepaliveMetrics(writeTime);

        // Expect the stored durations to be 0 but still contain the number of keepalive = 1.
        assertDailyKeepaliveInfoReported(
                dailyKeepaliveInfoReported2,
                /* expectRegisteredDurations= */ new int[] {0, 0},
                /* expectActiveDurations= */ new int[] {0, 0},
                new KeepaliveCarrierStats[] {getDefaultCarrierStats(0, 0)});

        // Expect that the keepalive is still registered after resetting so it can be stopped.
        onStopKeepalive(stopTime, /* slot= */ 1);

        final DailykeepaliveInfoReported dailyKeepaliveInfoReported3 =
                buildKeepaliveMetrics(writeTime2);

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

        onStartKeepalive(startTime1, /* slot= */ 1);

        onStartKeepalive(startTime2, /* slot= */ 2, TEST_NETWORK_CAPABILITIES_2,
                TEST_KEEPALIVE_INTERVAL_SEC_2);

        onStopKeepalive(stopTime1, /* slot= */ 1);

        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                buildAndResetMetrics(writeTime);

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
                        TEST_CARRIER_ID_2,
                        /* transportTypes= */ (1 << TRANSPORT_CELLULAR),
                        TEST_KEEPALIVE_INTERVAL_SEC_2 * 1000,
                        writeTime - startTime2,
                        writeTime - startTime2);

        assertDailyKeepaliveInfoReported(
                dailyKeepaliveInfoReported,
                expectRegisteredDurations,
                expectActiveDurations,
                new KeepaliveCarrierStats[] {
                    expectKeepaliveCarrierStats1, expectKeepaliveCarrierStats2
                });

        final DailykeepaliveInfoReported dailyKeepaliveInfoReported2 =
                buildKeepaliveMetrics(writeTime2);

        // Only 1 keepalive is registered and active since the reset until the writeTime2.
        final int[] expectRegisteredDurations2 = new int[] {0, writeTime2 - writeTime};
        final int[] expectActiveDurations2 = new int[] {0, writeTime2 - writeTime};

        // Only the keepalive with interval of intervalSec2 is present.
        final KeepaliveCarrierStats expectKeepaliveCarrierStats3 =
                new KeepaliveCarrierStats(
                        TEST_CARRIER_ID_2,
                        /* transportTypes= */ (1 << TRANSPORT_CELLULAR),
                        TEST_KEEPALIVE_INTERVAL_SEC_2 * 1000,
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

        onStartKeepalive(startTime1, /* slot= */ 1);

        // Attempt to use the same (network, slot)
        // AssertionError from runOnHandlerThread.
        assertThrows(
                AssertionError.class,
                () -> onStartKeepalive(startTime2, /* slot= */ 1));
        // The actual thrown exception is stored in mThrownException
        assertEquals(IllegalArgumentException.class, mThrownException.getClass());

        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                buildKeepaliveMetrics(writeTime);

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

        onStartKeepalive(startTime1, /* slot= */ 1);

        onStopKeepalive(stopTime, /* slot= */ 1);

        // Attempt to use the same (network, slot)
        onStartKeepalive(startTime2, /* slot= */ 1);

        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                buildKeepaliveMetrics(writeTime);

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

    @Test
    public void testCarrierIdChange_changeBeforeStart() {
        // Update the list to only have sub_id_2 with carrier_id_1.
        doReturn(TEST_CARRIER_ID_1).when(mSubInfo2).getCarrierId();

        doReturn(List.of(mSubInfo2)).when(mSubscriptionManager).getActiveSubscriptionInfoList();
        getOnSubscriptionsChangedListener().onSubscriptionsChanged();
        HandlerUtils.waitForIdle(mTestHandler, TIMEOUT_MS);

        // Ignore durations for this test.
        final int startTime = 5000;
        final int writeTime = 5000;

        onStartKeepalive(startTime, /* slot= */ 1);
        onStartKeepalive(startTime, /* slot= */ 2, TEST_NETWORK_CAPABILITIES_2);

        final DailykeepaliveInfoReported dailyKeepaliveInfoReported =
                buildKeepaliveMetrics(writeTime);

        // The network with sub_id_1 has an unknown carrier id.
        final KeepaliveCarrierStats expectKeepaliveCarrierStats1 =
                new KeepaliveCarrierStats(
                        TelephonyManager.UNKNOWN_CARRIER_ID,
                        /* transportTypes= */ 1,
                        TEST_KEEPALIVE_INTERVAL_SEC * 1000,
                        /* lifetimeMsec= */ 0,
                        /* activeLifetimeMsec= */ 0);

        // The network with sub_id_2 has carrier_id_1.
        final KeepaliveCarrierStats expectKeepaliveCarrierStats2 =
                new KeepaliveCarrierStats(
                        TEST_CARRIER_ID_1,
                        /* transportTypes= */ 1,
                        TEST_KEEPALIVE_INTERVAL_SEC * 1000,
                        /* lifetimeMsec= */ 0,
                        /* activeLifetimeMsec= */ 0);
        assertDailyKeepaliveInfoReported(
                dailyKeepaliveInfoReported,
                /* expectRegisteredDurations= */ new int[] {startTime, 0, 0},
                /* expectActiveDurations= */ new int[] {startTime, 0, 0},
                new KeepaliveCarrierStats[] {
                    expectKeepaliveCarrierStats1, expectKeepaliveCarrierStats2
                });
    }
}
