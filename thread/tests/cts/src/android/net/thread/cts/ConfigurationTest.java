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

import android.net.thread.Configuration;
import android.net.thread.utils.ThreadFeatureCheckerRule;
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresThreadFeature;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link Configuration}. */
@SmallTest
@RequiresThreadFeature
@RunWith(AndroidJUnit4.class)
public final class ConfigurationTest {
    @Rule public final ThreadFeatureCheckerRule mThreadRule = new ThreadFeatureCheckerRule();

    @Test
    public void parcelable_parcelingIsLossLess() throws Exception {
        testForCombinations(
                (isNat64Enabled, isDhcp6PdEnabled, isTrelEnabled) -> {
                    Configuration config =
                            new Configuration.Builder()
                                    .setNat64Enabled(isNat64Enabled)
                                    .setDhcp6PdEnabled(isDhcp6PdEnabled)
                                    .setTrelEnabled(isTrelEnabled)
                                    .build();
                    assertParcelingIsLossless(config);
                });
    }

    @Test
    public void equalityTests() throws Exception {
        testForCombinations(
                (isNat64Enabled, isDhcp6PdEnabled, isTrelEnabled) -> {
                    Configuration config1 =
                            new Configuration.Builder()
                                    .setNat64Enabled(isNat64Enabled)
                                    .setDhcp6PdEnabled(isDhcp6PdEnabled)
                                    .setTrelEnabled(isTrelEnabled)
                                    .build();
                    Configuration config2 =
                            new Configuration.Builder()
                                    .setNat64Enabled(isNat64Enabled)
                                    .setDhcp6PdEnabled(isDhcp6PdEnabled)
                                    .setTrelEnabled(isTrelEnabled)
                                    .build();
                    assertThat(config1).isEqualTo(config2);
                });
    }

    @Test
    public void builder_correctValuesAreSet() throws Exception {
        testForCombinations(
                (isNat64Enabled, isDhcp6PdEnabled, isTrelEnabled) -> {
                    Configuration config =
                            new Configuration.Builder()
                                    .setNat64Enabled(isNat64Enabled)
                                    .setDhcp6PdEnabled(isDhcp6PdEnabled)
                                    .setTrelEnabled(isTrelEnabled)
                                    .build();
                    assertThat(config.isNat64Enabled()).isEqualTo(isNat64Enabled);
                    assertThat(config.isDhcp6PdEnabled()).isEqualTo(isDhcp6PdEnabled);
                    assertThat(config.isTrelEnabled()).isEqualTo(isTrelEnabled);
                });
    }

    @Test
    public void builderConstructor_configsAreEqual() throws Exception {
        testForCombinations(
                (isNat64Enabled, isDhcp6PdEnabled, isTrelEnabled) -> {
                    Configuration config1 =
                            new Configuration.Builder()
                                    .setNat64Enabled(isNat64Enabled)
                                    .setDhcp6PdEnabled(isDhcp6PdEnabled)
                                    .setTrelEnabled(isTrelEnabled)
                                    .build();
                    Configuration config2 = new Configuration.Builder(config1).build();
                    assertThat(config1).isEqualTo(config2);
                });
    }

    private void testForCombinations(TriConsumer<Boolean, Boolean, Boolean> consumer) {
        consumer.accept(false, false, false);
    }

    private interface TriConsumer<A, B, C> {
        void accept(A a, B b, C c);
    }
}
