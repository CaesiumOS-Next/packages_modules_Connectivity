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

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NsdServiceInfoTest {
    @Test
    public void setAttribute_nullBinaryValue_success() {
        NsdServiceInfo info = new NsdServiceInfo();

        info.setAttribute("a", (byte[])null);

        assertThat(info.getAttributes().get("a")).isNull();
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
    public void setAttribute_keyIncludesPrintableAscii_success() {
        NsdServiceInfo info = new NsdServiceInfo();

        info.setAttribute("\u0020", new byte[]{1});
        info.setAttribute("\u007E", new byte[]{2});

        assertThat(info.getAttributes().get("\u0020")).isEqualTo(new byte[]{1});
        assertThat(info.getAttributes().get("\u007E")).isEqualTo(new byte[]{2});
    }

    @Test
    public void setAttribute_keyIncludesEqualSign_throwsIllegalArgumentException() {
        NsdServiceInfo info = new NsdServiceInfo();

        assertThrows(IllegalArgumentException.class, () -> info.setAttribute("=", new byte[]{0}));
    }

    @Test
    public void setAttribute_keyValueTotalLengthIsTooLong_throwsIllegalArgumentException() {
        NsdServiceInfo info = new NsdServiceInfo();
        final String key = repeat("a", 128);
        final byte[] value = new byte[127];

        assertThrows(IllegalArgumentException.class, () -> info.setAttribute(key, value));
    }

    @Test
    public void setAttribute_totalTxtRecordSizeIsTooLarge_throwsIllegalArgumentException() {
        NsdServiceInfo info = new NsdServiceInfo();
        final byte[] value = new byte[127];

        info.setAttribute(repeat("a", 127), value);
        info.setAttribute(repeat("b", 127), value);
        info.setAttribute(repeat("c", 127), value);
        info.setAttribute(repeat("d", 127), value);
        info.setAttribute(repeat("e", 127), value);
        assertThat(info.getTxtRecord().length).isEqualTo(1280); // 1280 = 5 * (127 + 127 + 1 + 1)

        // The upper bound size of TXT record (1300 bytes) is exceeded
        assertThrows(IllegalArgumentException.class,
                () -> info.setAttribute(repeat("f", 127), value));
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

    // {@link String#repeat} is available only starts from Java 11
    /** Creates a new String by repeating {@code str} for times {@code count}. */
    private String repeat(String str, int count) {
        StringBuilder sb = new StringBuilder();
        while (count > 0) {
            sb.append(str);
            count--;
        }
        return sb.toString();
    }
}
