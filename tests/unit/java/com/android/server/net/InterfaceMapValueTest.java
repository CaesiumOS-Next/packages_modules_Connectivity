/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.net;

import static org.junit.Assert.assertArrayEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public class InterfaceMapValueTest {
    private static final String IF_NAME = "wlan0";
    private static final byte[] IF_NAME_BYTE = new byte[]{0x77, 0x6c, 0x61, 0x6e, 0x30}; //wlan0
    private static final byte[] IF_NAME_BYTE_WITH_PADDING =
            new byte[]{0x77, 0x6c, 0x61, 0x6e, 0x30, 0x0, 0x0, 0x0,
                    0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0};

    @Test
    public void testTestInterfaceMapValueFromString() {
        final InterfaceMapValue value = new InterfaceMapValue(IF_NAME);
        assertArrayEquals(IF_NAME_BYTE_WITH_PADDING, value.interfaceName);
    }

    @Test
    public void testTestInterfaceMapValueFromByte() {
        final InterfaceMapValue value = new InterfaceMapValue(IF_NAME_BYTE_WITH_PADDING);
        assertArrayEquals(IF_NAME_BYTE_WITH_PADDING, value.interfaceName);
    }

    @Test
    public void testTestInterfaceMapValueFromByteShort() {
        final InterfaceMapValue value = new InterfaceMapValue(IF_NAME_BYTE);
        assertArrayEquals(IF_NAME_BYTE_WITH_PADDING, value.interfaceName);
    }

    @Test
    public void testTestInterfaceMapValueFromByteLong() {
        final byte[] ifNameLong = Arrays.copyOf(IF_NAME_BYTE, 1000);
        final InterfaceMapValue value = new InterfaceMapValue(ifNameLong);
        assertArrayEquals(IF_NAME_BYTE_WITH_PADDING, value.interfaceName);
    }
}
