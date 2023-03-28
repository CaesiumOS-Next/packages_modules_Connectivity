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

import static junit.framework.Assert.assertEquals;

import static org.mockito.Mockito.doReturn;

import android.os.Build;

import androidx.test.filters.SmallTest;

import com.android.metrics.DurationForNumOfKeepalive;
import com.android.metrics.DurationPerNumOfKeepalive;
import com.android.server.connectivity.KeepaliveStatsTracker;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(DevSdkIgnoreRunner.class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
public class KeepaliveStatsTrackerTest {
    private static final int TEST_WRITE_TIME = 5000;

    private KeepaliveStatsTracker mKeepaliveStatsTracker;
    @Mock KeepaliveStatsTracker.Dependencies mDependencies;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        setCurrentTimeMillis(0);
        mKeepaliveStatsTracker = new KeepaliveStatsTracker(mDependencies);
    }

    private void setCurrentTimeMillis(long time) {
        doReturn(time).when(mDependencies).getCurrentTimeMillis();
    }

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

    @Test
    public void testNoKeepalive() {
        setCurrentTimeMillis(TEST_WRITE_TIME);
        final DurationPerNumOfKeepalive resultDurations =
                mKeepaliveStatsTracker.writeKeepaliveMetrics();

        // Expect that the durations are all in numOfKeepalive = 0.
        final int[] expectRegisteredDurations = new int[] {TEST_WRITE_TIME};
        final int[] expectActiveDurations = new int[] {TEST_WRITE_TIME};
        assertDurationMetrics(expectRegisteredDurations, expectActiveDurations, resultDurations);
    }

    /* Diagram of test (not to scale):
     * Key: S - Start/Stop, P - Pause, R - Resume, W - Write
     *
     * Keepalive     S                          W
     * Timeline  |------------------------------|
     */
    @Test
    public void testOneKeepalive_startOnly() {
        final int startTime = 1000;

        setCurrentTimeMillis(startTime);
        mKeepaliveStatsTracker.onStartKeepalive();

        setCurrentTimeMillis(TEST_WRITE_TIME);
        final DurationPerNumOfKeepalive resultDurations =
                mKeepaliveStatsTracker.writeKeepaliveMetrics();

        // The keepalive is never stopped, expect the duration for numberOfKeepalive of 1 to range
        // from startTime to TEST_WRITE_TIME.
        final int[] expectRegisteredDurations = new int[] {startTime, TEST_WRITE_TIME - startTime};
        final int[] expectActiveDurations = new int[] {startTime, TEST_WRITE_TIME - startTime};
        assertDurationMetrics(expectRegisteredDurations, expectActiveDurations, resultDurations);
    }

    /* Diagram of test (not to scale):
     * Key: S - Start/Stop, P - Pause, R - Resume, W - Write
     *
     * Keepalive     S       P                  W
     * Timeline  |------------------------------|
     */
    @Test
    public void testOneKeepalive_paused() {
        final int startTime = 1000;
        final int pauseTime = 2030;

        setCurrentTimeMillis(startTime);
        mKeepaliveStatsTracker.onStartKeepalive();

        setCurrentTimeMillis(pauseTime);
        mKeepaliveStatsTracker.onPauseKeepalive();

        setCurrentTimeMillis(TEST_WRITE_TIME);
        final DurationPerNumOfKeepalive resultDurations =
                mKeepaliveStatsTracker.writeKeepaliveMetrics();

        // The keepalive is paused but not stopped, expect the registered duration for
        // numberOfKeepalive of 1 to still range from startTime to TEST_WRITE_TIME while the active
        // duration stops at pauseTime.
        final int[] expectRegisteredDurations = new int[] {startTime, TEST_WRITE_TIME - startTime};
        final int[] expectActiveDurations =
                new int[] {startTime + (TEST_WRITE_TIME - pauseTime), pauseTime - startTime};
        assertDurationMetrics(expectRegisteredDurations, expectActiveDurations, resultDurations);
    }

    /* Diagram of test (not to scale):
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

        setCurrentTimeMillis(startTime);
        mKeepaliveStatsTracker.onStartKeepalive();

        setCurrentTimeMillis(pauseTime);
        mKeepaliveStatsTracker.onPauseKeepalive();

        setCurrentTimeMillis(resumeTime);
        mKeepaliveStatsTracker.onResumeKeepalive();

        setCurrentTimeMillis(TEST_WRITE_TIME);
        final DurationPerNumOfKeepalive resultDurations =
                mKeepaliveStatsTracker.writeKeepaliveMetrics();

        // The keepalive is paused and resumed but not stopped, expect the registered duration for
        // numberOfKeepalive of 1 to still range from startTime to TEST_WRITE_TIME while the active
        // duration stops at pauseTime but resumes at resumeTime and stops at TEST_WRITE_TIME.
        final int[] expectRegisteredDurations = new int[] {startTime, TEST_WRITE_TIME - startTime};
        final int[] expectActiveDurations =
                new int[] {
                    startTime + (resumeTime - pauseTime),
                    (pauseTime - startTime) + (TEST_WRITE_TIME - resumeTime)
                };
        assertDurationMetrics(expectRegisteredDurations, expectActiveDurations, resultDurations);
    }

    /* Diagram of test (not to scale):
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

        setCurrentTimeMillis(startTime);
        mKeepaliveStatsTracker.onStartKeepalive();

        setCurrentTimeMillis(pauseTime);
        mKeepaliveStatsTracker.onPauseKeepalive();

        setCurrentTimeMillis(resumeTime);
        mKeepaliveStatsTracker.onResumeKeepalive();

        setCurrentTimeMillis(stopTime);
        mKeepaliveStatsTracker.onStopKeepalive(/* wasActive= */ true);

        setCurrentTimeMillis(TEST_WRITE_TIME);
        final DurationPerNumOfKeepalive resultDurations =
                mKeepaliveStatsTracker.writeKeepaliveMetrics();

        // The keepalive is now stopped, expect the registered duration for numberOfKeepalive of 1
        // to now range from startTime to stopTime while the active duration stops at pauseTime but
        // resumes at resumeTime and stops again at stopTime.
        final int[] expectRegisteredDurations =
                new int[] {startTime + (TEST_WRITE_TIME - stopTime), stopTime - startTime};
        final int[] expectActiveDurations =
                new int[] {
                    startTime + (resumeTime - pauseTime) + (TEST_WRITE_TIME - stopTime),
                    (pauseTime - startTime) + (stopTime - resumeTime)
                };
        assertDurationMetrics(expectRegisteredDurations, expectActiveDurations, resultDurations);
    }

    /* Diagram of test (not to scale):
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

        setCurrentTimeMillis(startTime);
        mKeepaliveStatsTracker.onStartKeepalive();

        setCurrentTimeMillis(pauseTime);
        mKeepaliveStatsTracker.onPauseKeepalive();

        setCurrentTimeMillis(stopTime);
        mKeepaliveStatsTracker.onStopKeepalive(/* wasActive= */ false);

        setCurrentTimeMillis(TEST_WRITE_TIME);
        final DurationPerNumOfKeepalive resultDurations =
                mKeepaliveStatsTracker.writeKeepaliveMetrics();

        // The keepalive is stopped while paused, expect the registered duration for
        // numberOfKeepalive of 1 to range from startTime to stopTime while the active duration
        // simply stops at pauseTime.
        final int[] expectRegisteredDurations =
                new int[] {startTime + (TEST_WRITE_TIME - stopTime), stopTime - startTime};
        final int[] expectActiveDurations =
                new int[] {
                    startTime + (stopTime - pauseTime) + (TEST_WRITE_TIME - stopTime),
                    (pauseTime - startTime)
                };
        assertDurationMetrics(expectRegisteredDurations, expectActiveDurations, resultDurations);
    }

    /* Diagram of test (not to scale):
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

        setCurrentTimeMillis(startTime);
        mKeepaliveStatsTracker.onStartKeepalive();

        for (int i = 0; i < pauseResumeTimes.length; i++) {
            if (i % 2 == 0) {
                setCurrentTimeMillis(pauseResumeTimes[i]);
                mKeepaliveStatsTracker.onPauseKeepalive();

            } else {
                setCurrentTimeMillis(pauseResumeTimes[i]);
                mKeepaliveStatsTracker.onResumeKeepalive();
            }
        }

        setCurrentTimeMillis(stopTime);
        mKeepaliveStatsTracker.onStopKeepalive(/* wasActive= */ true);

        setCurrentTimeMillis(TEST_WRITE_TIME);
        final DurationPerNumOfKeepalive resultDurations =
                mKeepaliveStatsTracker.writeKeepaliveMetrics();

        final int[] expectRegisteredDurations =
                new int[] {startTime + (TEST_WRITE_TIME - stopTime), stopTime - startTime};
        final int[] expectActiveDurations =
                new int[] {
                    startTime + /* sum of (Resume - Pause) */ (900) + (TEST_WRITE_TIME - stopTime),
                    (pauseResumeTimes[0] - startTime)
                            + /* sum of (Pause - Resume) */ (700)
                            + (stopTime - pauseResumeTimes[5])
                };
        assertDurationMetrics(expectRegisteredDurations, expectActiveDurations, resultDurations);
    }

    /* Diagram of test (not to scale):
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

        setCurrentTimeMillis(startTime1);
        mKeepaliveStatsTracker.onStartKeepalive();

        setCurrentTimeMillis(pauseTime1);
        mKeepaliveStatsTracker.onPauseKeepalive();

        setCurrentTimeMillis(startTime2);
        mKeepaliveStatsTracker.onStartKeepalive();

        setCurrentTimeMillis(resumeTime1);
        mKeepaliveStatsTracker.onResumeKeepalive();

        setCurrentTimeMillis(pauseTime2);
        mKeepaliveStatsTracker.onPauseKeepalive();

        setCurrentTimeMillis(resumeTime2);
        mKeepaliveStatsTracker.onResumeKeepalive();

        setCurrentTimeMillis(stopTime1);
        mKeepaliveStatsTracker.onStopKeepalive(/* wasActive= */ true);

        setCurrentTimeMillis(TEST_WRITE_TIME);
        final DurationPerNumOfKeepalive resultDurations =
                mKeepaliveStatsTracker.writeKeepaliveMetrics();

        //
        final int[] expectRegisteredDurations =
                new int[] {
                    startTime1,
                    (startTime2 - startTime1) + (TEST_WRITE_TIME - stopTime1),
                    stopTime1 - startTime2
                };
        final int[] expectActiveDurations =
                new int[] {
                    startTime1 + (startTime2 - pauseTime1),
                    (pauseTime1 - startTime1)
                            + (resumeTime1 - startTime2)
                            + (resumeTime2 - pauseTime2)
                            + (TEST_WRITE_TIME - stopTime1),
                    (pauseTime2 - resumeTime1) + (stopTime1 - resumeTime2)
                };
        assertDurationMetrics(expectRegisteredDurations, expectActiveDurations, resultDurations);
    }

    /* Diagram of test (not to scale):
     * Key: S - Start/Stop, P - Pause, R - Resume, W - Write
     *
     * Keepalive     S   W(reset+W)         S    W
     * Timeline   |------------------------------|
     */
    @Test
    public void testResetMetrics() {
        final int startTime = 1000;
        final int stopTime = 7000;

        setCurrentTimeMillis(startTime);
        mKeepaliveStatsTracker.onStartKeepalive();

        setCurrentTimeMillis(TEST_WRITE_TIME);
        final DurationPerNumOfKeepalive resultDurations =
                mKeepaliveStatsTracker.writeKeepaliveMetrics();

        // Same expect as testOneKeepalive_startOnly
        final int[] expectRegisteredDurations = new int[] {startTime, TEST_WRITE_TIME - startTime};
        final int[] expectActiveDurations = new int[] {startTime, TEST_WRITE_TIME - startTime};
        assertDurationMetrics(expectRegisteredDurations, expectActiveDurations, resultDurations);

        final DurationPerNumOfKeepalive resultDurations2 =
                mKeepaliveStatsTracker.writeKeepaliveMetrics();
        // Expect the stored durations to be 0 but still contain the number of keepalive = 1.
        assertDurationMetrics(new int[] {0, 0}, new int[] {0, 0}, resultDurations2);

        // Expect that the keepalive is still registered after resetting so it can be stopped.
        setCurrentTimeMillis(stopTime);
        mKeepaliveStatsTracker.onStopKeepalive(/* wasActive= */ true);

        setCurrentTimeMillis(TEST_WRITE_TIME * 2);
        final DurationPerNumOfKeepalive resultDurations3 =
                mKeepaliveStatsTracker.writeKeepaliveMetrics();

        final int[] expectRegisteredDurations2 =
                new int[] {TEST_WRITE_TIME * 2 - stopTime, stopTime - TEST_WRITE_TIME};
        final int[] expectActiveDurations2 =
                new int[] {TEST_WRITE_TIME * 2 - stopTime, stopTime - TEST_WRITE_TIME};
        assertDurationMetrics(expectRegisteredDurations2, expectActiveDurations2, resultDurations3);
    }
}
