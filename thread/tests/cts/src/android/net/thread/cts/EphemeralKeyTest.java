/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static com.android.testutils.ParcelUtils.assertParcelingIsLossless;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.net.thread.EphemeralKey;
import android.net.thread.utils.ThreadFeatureCheckerRule;
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresThreadFeature;

import androidx.test.filters.SmallTest;

import com.google.common.testing.EqualsTester;

import org.junit.Rule;
import org.junit.Test;

import java.time.Duration;

@SmallTest
@RequiresThreadFeature
public final class EphemeralKeyTest {
    @Rule public final ThreadFeatureCheckerRule mThreadRule = new ThreadFeatureCheckerRule();

    @Test
    public void parcelable_parcelingIsLossless() {
        EphemeralKey ephemeralKey = new EphemeralKey.Builder("123456789", 1000).build();

        assertParcelingIsLossless(ephemeralKey);
    }

    @Test
    public void constructor_correctValuesAreSet() {
        EphemeralKey ephemeralKey = new EphemeralKey.Builder("123456789", 1000).build();

        assertThat(ephemeralKey.getPasscode()).isEqualTo("123456789");
        assertThat(ephemeralKey.getLifetime()).isEqualTo(Duration.ofMillis(1000));
    }

    @Test
    public void constructor_nullPasscode() {
        assertThrows(
                NullPointerException.class,
                () -> {
                    new EphemeralKey.Builder(null, 1000).build();
                });
    }

    @Test
    public void constructor_nullLifetime() {
        assertThrows(
                NullPointerException.class,
                () -> {
                    new EphemeralKey.Builder("123456789", null).build();
                });
    }

    @Test
    public void equalityTests() throws Exception {
        new EqualsTester()
                .addEqualityGroup(
                        new EphemeralKey.Builder("123456789", 1000).build(),
                        new EphemeralKey.Builder("123456789", 1000).build())
                .addEqualityGroup(
                        new EphemeralKey.Builder("123456789", 2000).build(),
                        new EphemeralKey.Builder("123456789", Duration.ofMillis(2000)).build())
                .addEqualityGroup(
                        new EphemeralKey.Builder("123456789", Duration.ofMillis(3000)).build(),
                        new EphemeralKey.Builder("123456789", Duration.ofMillis(3000)).build())
                .addEqualityGroup(
                        new EphemeralKey.Builder("987654321", Duration.ofMillis(3000)).build(),
                        new EphemeralKey.Builder("987654321", Duration.ofMillis(3000)).build())
                .testEquals();
    }
}
