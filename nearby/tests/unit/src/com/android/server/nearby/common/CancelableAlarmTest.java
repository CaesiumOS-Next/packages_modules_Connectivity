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

package com.android.server.nearby.common;

import static com.google.common.truth.Truth.assertThat;

import android.os.SystemClock;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CancelableAlarmTest {

    private static final long DELAY_MILLIS = 1000;

    private final ScheduledExecutorService mExecutor =
            Executors.newScheduledThreadPool(1);

    @Test
    public void alarmRuns_singleExecution() throws InterruptedException {
        TestCountDownLatch latch = new TestCountDownLatch(1);
        CancelableAlarm.createSingleAlarm(
                "alarmRuns", new CountDownRunnable(latch), DELAY_MILLIS, mExecutor);
        latch.awaitAndExpectDelay(DELAY_MILLIS);
    }

    @Test
    public void alarmRuns_periodicExecution() throws InterruptedException {
        TestCountDownLatch latch = new TestCountDownLatch(2);
        CancelableAlarm.createRecurringAlarm(
                "alarmRunsPeriodically", new CountDownRunnable(latch), DELAY_MILLIS, mExecutor);
        latch.awaitAndExpectDelay(DELAY_MILLIS * 2);
    }

    @Test
    public void canceledAlarmDoesNotRun_singleExecution() throws InterruptedException {
        TestCountDownLatch latch = new TestCountDownLatch(1);
        CancelableAlarm alarm =
                CancelableAlarm.createSingleAlarm(
                        "canceledAlarmDoesNotRun",
                        new CountDownRunnable(latch),
                        DELAY_MILLIS,
                        mExecutor);
        assertThat(alarm.cancel()).isTrue();
        latch.awaitAndExpectTimeout(DELAY_MILLIS);
    }

    @Test
    public void canceledAlarmDoesNotRun_periodicExecution() throws InterruptedException {
        TestCountDownLatch latch = new TestCountDownLatch(2);
        CancelableAlarm alarm =
                CancelableAlarm.createRecurringAlarm(
                        "canceledAlarmDoesNotRunPeriodically",
                        new CountDownRunnable(latch),
                        DELAY_MILLIS,
                        mExecutor);
        latch.awaitAndExpectTimeout(DELAY_MILLIS);
        assertThat(alarm.cancel()).isTrue();
        latch.awaitAndExpectTimeout(DELAY_MILLIS);
    }

    @Test
    public void cancelOfRunAlarmReturnsFalse() throws InterruptedException {
        TestCountDownLatch latch = new TestCountDownLatch(1);
        long delayMillis = 500;
        CancelableAlarm alarm =
                CancelableAlarm.createSingleAlarm(
                        "cancelOfRunAlarmReturnsFalse",
                        new CountDownRunnable(latch),
                        delayMillis,
                        mExecutor);
        latch.awaitAndExpectDelay(delayMillis - 1);

        assertThat(alarm.cancel()).isFalse();
    }

    private static class CountDownRunnable implements Runnable {
        private final CountDownLatch mLatch;

        CountDownRunnable(CountDownLatch latch) {
            this.mLatch = latch;
        }

        @Override
        public void run() {
            mLatch.countDown();
        }
    }

    /** A CountDownLatch for test with extra test features like throw exception on await(). */
    private static class TestCountDownLatch extends CountDownLatch {

        TestCountDownLatch(int count) {
            super(count);
        }

        /**
         * Asserts that the latch does not go off until delayMillis has passed and that it does in
         * fact go off after delayMillis has passed.
         */
        public void awaitAndExpectDelay(long delayMillis) throws InterruptedException {
            long startTime = SystemClock.elapsedRealtime();
            // Check at half duration that it hasn't fired prematurely
            SystemClock.sleep(delayMillis / 2);
            assertWithMessage("Latch went off before expected delay.")
                    .that(await(0, TimeUnit.MILLISECONDS))
                    .isFalse();

            // Wait up to 5s for the alarm to trigger
            boolean completed = await(5, TimeUnit.SECONDS);
            long elapsedTime = SystemClock.elapsedRealtime() - startTime;
            assertWithMessage("Alarm did not go off after expected delay.")
                    .that(completed)
                    .isTrue();
            assertWithMessage("Elapsed time too short")
                .that(elapsedTime)
                .isAtLeast(delayMillis - 50);
        }

        /** Asserts that the latch does not go off within delayMillis. */
        public void awaitAndExpectTimeout(long delayMillis) throws InterruptedException {
            SystemClock.sleep(delayMillis + 1);
            assertThat(await(0, TimeUnit.MILLISECONDS)).isFalse();
        }
    }
}
