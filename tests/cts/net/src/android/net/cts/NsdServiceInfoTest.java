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

package android.net.nsd;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.net.nsd.NsdServiceInfo;
import android.os.Build;

import java.util.List;
import java.util.Set;

import androidx.test.filters.SmallTest;

import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@IgnoreUpTo(Build.VERSION_CODES.S_V2)
@RunWith(DevSdkIgnoreRunner.class)
public class NsdServiceInfoTest {
    @Test
    public void setSubtypes_emptySubtype_throwsIllegalArgumentException() {
        NsdServiceInfo info = new NsdServiceInfo();
        Set<String> subtypes = Set.of("_thread", "");

        assertThrows(IllegalArgumentException.class, () -> info.setSubtypes(subtypes));
    }

    @Test
    public void setSubtypes_validSubtypes_Success() {
        NsdServiceInfo info = new NsdServiceInfo();

        info.setSubtypes(Set.of("_thread", "_matter"));

        assertThat(info.getSubtypes()).containsExactly("_thread", "_matter");
    }

    @Test
    public void setSubtypes_caseInsensitiveDuplicatedSubtypes_onlyOneIsAdded() {
        NsdServiceInfo info = new NsdServiceInfo();

        info.setSubtypes(Set.of("_thread", "_THREAD", "_ThrEad"));

        Set<String> subtypes = info.getSubtypes();
        assertThat(subtypes).hasSize(1);
        assertThat(subtypes).containsAnyOf("_thread", "_THREAD", "_ThrEad");
    }
}
