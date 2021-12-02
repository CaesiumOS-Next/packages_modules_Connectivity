/*
 * Copyright (C) 2021 The Android Open Source Project
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

//package com.android.net.module.util;
package com.android.networkstack.tethering;

import static com.android.networkstack.tethering.util.TetheringUtils.getTetheringJniLibraryName;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.filters.SmallTest;

import com.android.net.module.util.BpfBitmap;
import com.android.testutils.DevSdkIgnoreRunner;

import java.math.BigInteger;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(DevSdkIgnoreRunner.class)
public final class BpfBitmapTest {
    private static final String BLOCKED_PORTS_FS_PATH =
            "/sys/fs/bpf/tethering/map_test_blocked_ports_map";
    private static final int TEST_INDEX_1 = 6;
    private static final int TEST_INDEX_2 = 72;

    private BpfBitmap mTestBitmap;

    @BeforeClass
    public static void setupOnce() {
        System.loadLibrary(getTetheringJniLibraryName());
    }

    @Before
    public void setUp() throws Exception {
        mTestBitmap = new BpfBitmap(BLOCKED_PORTS_FS_PATH);
        mTestBitmap.clear();
        assertTrue(mTestBitmap.isEmpty());
    }

    @Test
    public void testSet() throws Exception {
        mTestBitmap.set(TEST_INDEX_1);
        assertFalse(mTestBitmap.isEmpty());
        assertTrue(mTestBitmap.getValueAtIndex(TEST_INDEX_1).equals(BigInteger.ONE));

        mTestBitmap.set(TEST_INDEX_2);
        assertFalse(mTestBitmap.isEmpty());
        assertTrue(mTestBitmap.getValueAtIndex(TEST_INDEX_2).equals(BigInteger.ONE));
    }

    @Test
    public void testUnset() throws Exception {
        mTestBitmap.set(TEST_INDEX_1);
        mTestBitmap.set(TEST_INDEX_2);

        mTestBitmap.unset(TEST_INDEX_2);
        assertFalse(mTestBitmap.isEmpty());
        assertTrue(mTestBitmap.getValueAtIndex(TEST_INDEX_2).equals(BigInteger.ZERO));

        mTestBitmap.unset(TEST_INDEX_1);
        assertTrue(mTestBitmap.getValueAtIndex(TEST_INDEX_1).equals(BigInteger.ZERO));
        assertTrue(mTestBitmap.isEmpty());
    }

    @Test
    public void testClear() throws Exception {
        mTestBitmap.set(TEST_INDEX_1);
        mTestBitmap.set(TEST_INDEX_2);
        assertFalse(mTestBitmap.isEmpty());
        mTestBitmap.clear();
        assertTrue(mTestBitmap.isEmpty());
    }
}
