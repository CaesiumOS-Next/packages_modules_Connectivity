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

import android.net.thread.ThreadConfiguration;
import android.net.thread.utils.ThreadFeatureCheckerRule;
import android.net.thread.utils.ThreadFeatureCheckerRule.RequiresThreadFeature;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/** Tests for {@link ThreadConfiguration}. */
@SmallTest
@RequiresThreadFeature
@RunWith(AndroidJUnit4.class)
public final class ThreadConfigurationTest {
    @Rule public final ThreadFeatureCheckerRule mThreadRule = new ThreadFeatureCheckerRule();

    @Test
    public void parcelable_parcelingIsLossLess() throws Exception {
        forAllCombinations(
                (isNat64Enabled, isPrefixDelegationEnabled, isTrelEnabled) -> {
                    ThreadConfiguration config =
                            new ThreadConfiguration.Builder()
                                    .setNat64Enabled(isNat64Enabled)
                                    .setPrefixDelegationEnabled(isPrefixDelegationEnabled)
                                    .setTrelEnabled(isTrelEnabled)
                                    .build();
                    assertParcelingIsLossless(config);
                });
    }

    @Test
    public void equalityTests() throws Exception {
        forAllCombinations(
                (isNat64Enabled, isPrefixDelegationEnabled, isTrelEnabled) -> {
                    ThreadConfiguration config1 =
                            new ThreadConfiguration.Builder()
                                    .setNat64Enabled(isNat64Enabled)
                                    .setPrefixDelegationEnabled(isPrefixDelegationEnabled)
                                    .setTrelEnabled(isTrelEnabled)
                                    .build();
                    ThreadConfiguration config2 =
                            new ThreadConfiguration.Builder()
                                    .setNat64Enabled(isNat64Enabled)
                                    .setPrefixDelegationEnabled(isPrefixDelegationEnabled)
                                    .setTrelEnabled(isTrelEnabled)
                                    .build();
                    assertThat(config1).isEqualTo(config2);
                });
    }

    @Test
    public void builder_correctValuesAreSet() throws Exception {
        forAllCombinations(
                (isNat64Enabled, isPrefixDelegationEnabled, isTrelEnabled) -> {
                    ThreadConfiguration config =
                            new ThreadConfiguration.Builder()
                                    .setNat64Enabled(isNat64Enabled)
                                    .setPrefixDelegationEnabled(isPrefixDelegationEnabled)
                                    .setTrelEnabled(isTrelEnabled)
                                    .build();
                    assertThat(config.isNat64Enabled()).isEqualTo(isNat64Enabled);
                    assertThat(config.isPrefixDelegationEnabled())
                            .isEqualTo(isPrefixDelegationEnabled);
                    assertThat(config.isTrelEnabled()).isEqualTo(isTrelEnabled);
                });
    }

    @Test
    public void builderConstructor_configsAreEqual() throws Exception {
        forAllCombinations(
                (isNat64Enabled, isPrefixDelegationEnabled, isTrelEnabled) -> {
                    ThreadConfiguration config1 =
                            new ThreadConfiguration.Builder()
                                    .setNat64Enabled(isNat64Enabled)
                                    .setPrefixDelegationEnabled(isPrefixDelegationEnabled)
                                    .setTrelEnabled(isTrelEnabled)
                                    .build();
                    ThreadConfiguration config2 = new ThreadConfiguration.Builder(config1).build();
                    assertThat(config1).isEqualTo(config2);
                });
    }

    private void forAllCombinations(TriConsumer<Boolean, Boolean, Boolean> consumer) {
        List<Boolean> BOOLEAN_VALUES = List.of(false, true);
        for (boolean isNat64Enabled : BOOLEAN_VALUES) {
            for (boolean isPrefixDelegationEnabled : BOOLEAN_VALUES) {
                for (boolean isTrelEnabled : BOOLEAN_VALUES) {
                    consumer.accept(isNat64Enabled, isPrefixDelegationEnabled, isTrelEnabled);
                }
            }
        }
    }

    private interface TriConsumer<A, B, C> {
        void accept(A a, B b, C c);
    }
}
