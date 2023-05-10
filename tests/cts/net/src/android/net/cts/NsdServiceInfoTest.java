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

import android.os.Build;
import android.os.StrictMode;

import androidx.test.filters.SmallTest;

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(DevSdkIgnoreRunner.class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public class NsdServiceInfoTest {
    @Test
    public void setAttribute_nullBinaryValue_throwsNullPointerException() {
        NsdServiceInfo info = new NsdServiceInfo();

        assertThrows(NullPointerException.class, () -> info.setAttribute("a", (byte[])null));
    }

    @Test
    public void setAttribute_emptyBinaryValue_success() {
        NsdServiceInfo info = new NsdServiceInfo();

        info.setAttribute("a", new byte[]{});

        assertThat(info.getAttributes().get("a")).isEqualTo(new byte[]{});
    }

    @Test
    public void setAttribute_nullKey_throwsIllegalArgument() {
        NsdServiceInfo info = new NsdServiceInfo();

        assertThrows(IllegalArgumentException.class, () -> info.setAttribute(null, new byte[]{0}));
    }

    @Test
    public void setAttribute_emptyKey_throwsIllegalArgument() {
        NsdServiceInfo info = new NsdServiceInfo();

        assertThrows(IllegalArgumentException.class, () -> info.setAttribute("", new byte[]{0}));
    }

    @Test
    public void setAttribute_keyIncludesNotPrintableAscii_throwsIllegalArgumentException() {
        NsdServiceInfo info = new NsdServiceInfo();

        assertThrows(IllegalArgumentException.class,
                () -> info.setAttribute("\u0019", new byte[]{0}));
        assertThrows(IllegalArgumentException.class,
                () -> info.setAttribute("\u007F", new byte[]{0}));
    }

    @Test
    public void setAttribute_keyIncludesEqualSign_throwsIllegalArgumentException() {
        NsdServiceInfo info = new NsdServiceInfo();

        assertThrows(IllegalArgumentException.class, () -> info.setAttribute("=", new byte[]{0}));
    }

    @Test
    public void setAttribute_keyValueTotalLengthIsTooLong_throwsIllegalArgumentException() {
        NsdServiceInfo info = new NsdServiceInfo();
        final String key = "a".repeat(128);
        final byte[] value = new byte[127];

        assertThrows(IllegalArgumentException.class, () -> info.setAttribute(key, value));
    }

    @Test
    public void setAttribute_totalTxtRecordSizeIsTooLarge_throwsIllegalArgumentException() {
        NsdServiceInfo info = new NsdServiceInfo();
        final byte[] value = new byte[127];

        info.setAttribute("a".repeat(127), value);
        info.setAttribute("b".repeat(127), value);
        info.setAttribute("c".repeat(127), value);
        info.setAttribute("d".repeat(127), value);
        info.setAttribute("e".repeat(127), value);
        assertThat(info.getTxtRecord().length).isEqualTo(1280); // 1280 = 5 * (127 + 127 + 1 + 1)

        // The upper bound size of TXT record (1300 bytes) is exceeded
        assertThrows(IllegalArgumentException.class,
                () -> info.setAttribute("f".repeat(127), value));
    }

    @Test
    public void setAttribute_setTheSameKeyTwice_secondSetWins() {
        NsdServiceInfo info = new NsdServiceInfo();

        info.setAttribute("a", new byte[]{0x1a, 0x2a});
        info.setAttribute("a", new byte[]{0x1b, 0x2b});

        assertThat(info.getAttributes().get("a")).isEqualTo(new byte[]{0x1b, 0x2b});
    }

    @Test
    public void setAttribute_validKeyValue_attributesCanBeRetrievedBack() {
        NsdServiceInfo info = new NsdServiceInfo();

        info.setAttribute("a", new byte[]{0x1a, 0x2a});
        info.setAttribute("b", new byte[]{0x1b, 0x2b});

        assertThat(info.getAttributes()).hasSize(2);
        assertThat(info.getAttributes().get("a")).isEqualTo(new byte[]{0x1a, 0x2a});
        assertThat(info.getAttributes().get("b")).isEqualTo(new byte[]{0x1b, 0x2b});
    }
}
