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

package android.net.thread.cts;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.net.thread.OperationalDatasetTimestamp;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.google.common.testing.EqualsTester;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Instant;

/** Tests for {@link OperationalDatasetTimestamp}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public final class OperationalDatasetTimestampTest {
    @Test
    public void fromInstant_tooLargeInstant_throwsIllegalArgument() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        OperationalDatasetTimestamp.fromInstant(
                                Instant.ofEpochSecond(0xffffffffffffL + 1L)));
    }

    @Test
    public void fromInstant_goodValue_success() {
        OperationalDatasetTimestamp timestamp =
                OperationalDatasetTimestamp.fromInstant(Instant.ofEpochSecond(100L));

        assertThat(timestamp.getSeconds()).isEqualTo(100L);
        assertThat(timestamp.getTicks()).isEqualTo(0);
        assertThat(timestamp.isAuthoritativeSource()).isTrue();
    }

    @Test
    public void toInstant_ticksAndAuthoritativeSourceDiscarded() {
        OperationalDatasetTimestamp timestamp1 = new OperationalDatasetTimestamp(100L, 10, false);

        OperationalDatasetTimestamp timestamp2 =
                OperationalDatasetTimestamp.fromInstant(timestamp1.toInstant());

        assertThat(timestamp2.getSeconds()).isEqualTo(100L);
        assertThat(timestamp2.getTicks()).isEqualTo(0);
        assertThat(timestamp2.isAuthoritativeSource()).isTrue();
    }

    @Test
    public void constructor_tooLargeSeconds_throwsIllegalArguments() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new OperationalDatasetTimestamp(
                                /* seconds= */ 0x0001112233445566L,
                                /* ticks= */ 0,
                                /* isAuthoritativeSource= */ true));
    }

    @Test
    public void constructor_tooLargeTicks_throwsIllegalArguments() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new OperationalDatasetTimestamp(
                                /* seconds= */ 0x01L,
                                /* ticks= */ 0xffff,
                                /* isAuthoritativeSource= */ true));
    }

    @Test
    public void equalityTests() {
        new EqualsTester()
                .addEqualityGroup(
                        new OperationalDatasetTimestamp(100, 100, false),
                        new OperationalDatasetTimestamp(100, 100, false))
                .addEqualityGroup(
                        new OperationalDatasetTimestamp(0, 0, false),
                        new OperationalDatasetTimestamp(0, 0, false))
                .addEqualityGroup(
                        new OperationalDatasetTimestamp(0xffffffffffffL, 0x7fff, true),
                        new OperationalDatasetTimestamp(0xffffffffffffL, 0x7fff, true))
                .testEquals();
    }
}
