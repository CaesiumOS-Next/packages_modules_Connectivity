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

package android.net.thread;

import static com.android.testutils.ParcelUtils.assertParcelingIsLossless;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.net.thread.BorderRouterConfiguration.Builder;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.google.common.testing.EqualsTester;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link BorderRouterConfiguration}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class BorderRouterConfigurationTest {

    @Test
    public void parcelable_parcelingIsLossless() {
        BorderRouterConfiguration config =
                new Builder(true).setNat64Enabled(false).setPrefixDelegationEnabled(true).build();

        assertParcelingIsLossless(config);
    }

    @Test
    public void equalityTests() {
        new EqualsTester()
                .addEqualityGroup(
                        new Builder(true)
                                .setNat64Enabled(true)
                                .setPrefixDelegationEnabled(true)
                                .build(),
                        new Builder(true)
                                .setNat64Enabled(true)
                                .setPrefixDelegationEnabled(true)
                                .build())
                .addEqualityGroup(
                        new Builder(false)
                                .setNat64Enabled(false)
                                .setPrefixDelegationEnabled(false)
                                .build(),
                        new Builder(false)
                                .setNat64Enabled(false)
                                .setPrefixDelegationEnabled(false)
                                .build())
                .addEqualityGroup(
                        new Builder(true)
                                .setNat64Enabled(false)
                                .setPrefixDelegationEnabled(false)
                                .build(),
                        new Builder(true)
                                .setNat64Enabled(false)
                                .setPrefixDelegationEnabled(false)
                                .build())
                .addEqualityGroup(
                        new Builder(true)
                                .setNat64Enabled(false)
                                .setPrefixDelegationEnabled(true)
                                .build(),
                        new Builder(true)
                                .setNat64Enabled(false)
                                .setPrefixDelegationEnabled(true)
                                .build())
                .addEqualityGroup(
                        new Builder(true)
                                .setNat64Enabled(true)
                                .setPrefixDelegationEnabled(false)
                                .build(),
                        new Builder(true)
                                .setNat64Enabled(true)
                                .setPrefixDelegationEnabled(false)
                                .build())
                .testEquals();
    }

    @Test
    public void builder_build_createsExpectedValues() {
        BorderRouterConfiguration config =
                new Builder(true).setNat64Enabled(true).setPrefixDelegationEnabled(true).build();

        assertThat(config.isBorderRouterEnabled()).isTrue();
        assertThat(config.isNat64Enabled()).isTrue();
        assertThat(config.isPrefixDelegationEnabled()).isTrue();
    }

    @Test
    public void builder_constructor_featuresDefaultDisabled() {
        BorderRouterConfiguration config = new Builder(false).build();

        assertThat(config.isBorderRouterEnabled()).isFalse();
        assertThat(config.isNat64Enabled()).isFalse();
        assertThat(config.isPrefixDelegationEnabled()).isFalse();
    }

    @Test
    public void builder_constructorWithConfig_configsInherited() {
        Builder builder =
                new Builder(
                        new Builder(true)
                                .setNat64Enabled(false)
                                .setPrefixDelegationEnabled(true)
                                .build());

        BorderRouterConfiguration config = builder.build();

        assertThat(config.isBorderRouterEnabled()).isTrue();
        assertThat(config.isNat64Enabled()).isFalse();
        assertThat(config.isPrefixDelegationEnabled()).isTrue();
    }

    @Test
    public void builder_EnableNat64ButBorderRouterDisabled_buildThrowsIllegalState() {
        Builder builder = new Builder(false);

        assertThrows(IllegalStateException.class, () -> builder.setNat64Enabled(true));
    }

    @Test
    public void builder_EnableIpv6pdButBorderRouterDisabled_buildThrowsIllegalState() {
        Builder builder = new Builder(false);

        assertThrows(IllegalStateException.class, () -> builder.setPrefixDelegationEnabled(true));
    }

    @Test
    public void toString_includesAllFeatureFlags() {
        BorderRouterConfiguration config = new Builder(true).build();

        String str = config.toString();

        assertThat(str)
                .isEqualTo(
                        "{borderRouterEnabled=true, prefixDelegationEnabled=false,"
                                + " nat64Enabled=false}");
    }

    @Test
    public void toString_equalObjects_returnsEqualStrings() {
        BorderRouterConfiguration config1 = new Builder(true).build();
        BorderRouterConfiguration config2 = new Builder(true).build();

        String str1 = config1.toString();
        String str2 = config2.toString();

        assertThat(str1).isEqualTo(str2);
    }
}
