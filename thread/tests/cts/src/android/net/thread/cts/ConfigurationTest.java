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

import com.google.common.testing.EqualsTester;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

/** Tests for {@link Configuration}. */
@SmallTest
@RequiresThreadFeature
@RunWith(Parameterized.class)
public final class ConfigurationTest {
    @Rule public final ThreadFeatureCheckerRule mThreadRule = new ThreadFeatureCheckerRule();

    public final boolean mIsNat64Enabled;
    public final boolean mIsDhcp6PdEnabled;
    public final boolean mIsTrelEnabled;

    @Parameterized.Parameters
    public static Collection configArguments() {
        return Arrays.asList(
                new Object[][] {
                    {false, false, false}, // All disabled
                    {true, false, false}, // NAT64 enabled
                    {false, true, false}, // DHCP6-PD enabled
                    {false, false, true}, // TREL enabled
                    {true, true, true}, // All enabled
                });
    }

    public ConfigurationTest(
            boolean isNat64Enabled, boolean isDhcp6PdEnabled, boolean isTrelEnabled) {
        mIsNat64Enabled = isNat64Enabled;
        mIsDhcp6PdEnabled = isDhcp6PdEnabled;
        mIsTrelEnabled = isTrelEnabled;
    }

    @Test
    public void parcelable_parcelingIsLossLess() {
        Configuration config =
                new Configuration.Builder()
                        .setNat64Enabled(mIsNat64Enabled)
                        .setDhcp6PdEnabled(mIsDhcp6PdEnabled)
                        .setTrelEnabled(mIsTrelEnabled)
                        .build();
        assertParcelingIsLossless(config);
    }

    @Test
    public void builder_correctValuesAreSet() {
        Configuration config =
                new Configuration.Builder()
                        .setNat64Enabled(mIsNat64Enabled)
                        .setDhcp6PdEnabled(mIsDhcp6PdEnabled)
                        .setTrelEnabled(mIsTrelEnabled)
                        .build();

        assertThat(config.isNat64Enabled()).isEqualTo(mIsNat64Enabled);
        assertThat(config.isDhcp6PdEnabled()).isEqualTo(mIsDhcp6PdEnabled);
        assertThat(config.isTrelEnabled()).isEqualTo(mIsTrelEnabled);
    }

    @Test
    public void builderConstructor_configsAreEqual() {
        Configuration config1 =
                new Configuration.Builder()
                        .setNat64Enabled(mIsNat64Enabled)
                        .setDhcp6PdEnabled(mIsDhcp6PdEnabled)
                        .setTrelEnabled(mIsTrelEnabled)
                        .build();
        Configuration config2 = new Configuration.Builder(config1).build();
        assertThat(config1).isEqualTo(config2);
    }
}
