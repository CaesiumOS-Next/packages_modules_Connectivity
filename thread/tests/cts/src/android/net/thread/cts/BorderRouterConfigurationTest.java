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

import static com.android.testutils.ParcelUtils.assertParcelingIsLossless;

import static com.google.common.truth.Truth.assertThat;

import android.net.thread.BorderRouterConfiguration;
import android.net.thread.BorderRouterConfiguration.Builder;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.google.common.testing.EqualsTester;

import org.junit.Test;
import org.junit.runner.RunWith;

/** CTS tests for {@link BorderRouterConfiguration}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class BorderRouterConfigurationTest {

    @Test
    public void parcelable_parcelingIsLossless() {
        BorderRouterConfiguration config = new Builder(true).build();

        assertParcelingIsLossless(config);
    }

    @Test
    public void equalityTests() {
        new EqualsTester()
                .addEqualityGroup(new Builder(true).build(), new Builder(true).build())
                .addEqualityGroup(new Builder(false).build(), new Builder(false).build())
                .testEquals();
    }

    @Test
    public void builder_constructor_createsExpectedValues() {
        BorderRouterConfiguration configEnabled = new Builder(true).build();
        BorderRouterConfiguration configDisabled = new Builder(false).build();

        assertThat(configEnabled.isBorderRouterEnabled()).isTrue();
        assertThat(configDisabled.isBorderRouterEnabled()).isFalse();
    }

    @Test
    public void builder_copyConstructor_configsInherited() {
        Builder builder = new Builder(new Builder(true).build());

        BorderRouterConfiguration config = builder.build();

        assertThat(config.isBorderRouterEnabled()).isTrue();
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
