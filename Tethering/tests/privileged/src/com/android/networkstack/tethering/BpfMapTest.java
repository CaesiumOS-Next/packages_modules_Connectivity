/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.networkstack.tethering;

import static android.system.OsConstants.ETH_P_IPV6;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.net.MacAddress;
import android.os.Build;
import android.util.ArrayMap;
import android.util.ArraySet;

import androidx.test.runner.AndroidJUnit4;

import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.InetAddress;
import java.util.NoSuchElementException;

@RunWith(AndroidJUnit4.class)
@IgnoreUpTo(Build.VERSION_CODES.R)
public final class BpfMapTest {
    private static final int TEST_DATA_SIZE = 3;
    private static final int MAX_IIF = 10000;
    private ArrayMap<TetherIngressKey, TetherIngressValue> mTestData =
            new ArrayMap<>(TEST_DATA_SIZE);
    private ArrayMap<TetherIngressKey, TetherIngressValue> mOriginalData = new ArrayMap<>();
    private ArraySet<Long> mInusedKeyIif = new ArraySet<>();

    // TODO: Instead of using the production map, perhaps define a new
    // "map_offload_tether_ingress_map_TEST" for testing.
    private static final String TETHER_INGRESS_FS_PATH =
            "/sys/fs/bpf/map_offload_tether_ingress_map";

    @BeforeClass
    public static void setupOnce() {
        System.loadLibrary("tetherutilsjni");
    }

    @Before
    public void setUp() throws Exception {
        backupExistingEntries();

        // Make sure don't use existing key (if any) for testing.
        final long[] testKeyIif = new long[TEST_DATA_SIZE];
        getUnusedKeyIif(testKeyIif);
        mTestData.clear();
        mTestData.put(createTetherIngressKey(testKeyIif[0], "2001:1111:1111::1111"),
                createTetherIngressValue(11, "01:01:01:01:01:01", "11:11:11:11:11:11", ETH_P_IPV6,
                1000));
        mTestData.put(createTetherIngressKey(testKeyIif[1], "2001:2222:2222::2222"),
                createTetherIngressValue(22, "02:02:02:02:02:02", "22:22:22:22:22:22", ETH_P_IPV6,
                1000));
        mTestData.put(createTetherIngressKey(testKeyIif[2], "2001:3333:3333::3333"),
                createTetherIngressValue(33, "03:03:03:03:03:03", "33:33:33:33:33:33", ETH_P_IPV6,
                1000));
    }

    private void getUnusedKeyIif(long[] output) {
        int i = 0;
        long iif = 1;
        while (i < output.length && iif <= Integer.MAX_VALUE) {
            if (iif == MAX_IIF) fail("All iif values up to " + MAX_IIF + " already in map");
            if (!mInusedKeyIif.contains(iif)) output[i++] = iif;

            iif++;
        }
    }

    private void backupExistingEntries() throws Exception {
        try (BpfMap<TetherIngressKey, TetherIngressValue> bpfMap = new BpfMap<>(
                TETHER_INGRESS_FS_PATH, BpfMap.BPF_F_RDWR,
                TetherIngressKey.class, TetherIngressValue.class)) {
            mOriginalData.clear();
            mInusedKeyIif.clear();
            bpfMap.forEach((key, value) -> {
                mInusedKeyIif.add(key.iif);
                mOriginalData.put(key, value);
            });
        }
    }

    @After
    public void tearDown() throws Exception {
        try (BpfMap<TetherIngressKey, TetherIngressValue> bpfMap = new BpfMap<>(
                TETHER_INGRESS_FS_PATH, BpfMap.BPF_F_RDWR,
                TetherIngressKey.class, TetherIngressValue.class)) {
            for (int i = 0; i < mTestData.size(); i++) {
                bpfMap.deleteEntry(mTestData.keyAt(i));
            }

            for (int i = 0; i < mOriginalData.size(); i++) {
                bpfMap.updateEntry(mOriginalData.keyAt(i), mOriginalData.valueAt(i));
            }
        }
    }

    private TetherIngressKey createTetherIngressKey(long iif, String address) throws Exception {
        final InetAddress ipv6Address = InetAddress.getByName(address);

        return new TetherIngressKey(iif, ipv6Address.getAddress());
    }

    private TetherIngressValue createTetherIngressValue(long oif, String src, String dst, int proto,
            int pmtu) throws Exception {
        final MacAddress srcMac = MacAddress.fromString(src);
        final MacAddress dstMac = MacAddress.fromString(dst);

        return new TetherIngressValue(oif, dstMac, srcMac, proto, pmtu);
    }

    @Test
    public void testGetFd() throws Exception {
        try (BpfMap readOnlyMap = new BpfMap<>(TETHER_INGRESS_FS_PATH, BpfMap.BPF_F_RDONLY,
                TetherIngressKey.class, TetherIngressValue.class)) {
            assertNotNull(readOnlyMap);
        }
        try (BpfMap writeOnlyMap = new BpfMap<>(TETHER_INGRESS_FS_PATH, BpfMap.BPF_F_WRONLY,
                TetherIngressKey.class, TetherIngressValue.class)) {
            assertNotNull(writeOnlyMap);
        }
        try (BpfMap readWriteMap = new BpfMap<>(TETHER_INGRESS_FS_PATH, BpfMap.BPF_F_RDWR,
                TetherIngressKey.class, TetherIngressValue.class)) {
            assertNotNull(readWriteMap);
        }
    }

    @Test
    public void testGetFirstKey() throws Exception {
        assumeTrue(mOriginalData.isEmpty());

        try (BpfMap<TetherIngressKey, TetherIngressValue> bpfMap = new BpfMap<>(
                TETHER_INGRESS_FS_PATH, BpfMap.BPF_F_RDWR,
                TetherIngressKey.class, TetherIngressValue.class)) {
            assertFalse(bpfMap.containsKey(mTestData.keyAt(0)));
            assertNull(bpfMap.getFirstKey());
            assertNull(bpfMap.getValue(mTestData.keyAt(0)));

            bpfMap.updateEntry(mTestData.keyAt(0), mTestData.valueAt(0));
            assertEquals(mTestData.keyAt(0),  bpfMap.getFirstKey());
        }
    }

    @Test
    public void testGetNextKey() throws Exception {
        try (BpfMap<TetherIngressKey, TetherIngressValue> bpfMap = new BpfMap<>(
                TETHER_INGRESS_FS_PATH, BpfMap.BPF_F_RDWR,
                TetherIngressKey.class, TetherIngressValue.class)) {
            final ArrayMap<TetherIngressKey, TetherIngressValue> resultMap =
                    new ArrayMap<>(mOriginalData);
            bpfMap.updateEntry(mTestData.keyAt(0), mTestData.valueAt(0));
            resultMap.put(mTestData.keyAt(0), mTestData.valueAt(0));
            bpfMap.updateEntry(mTestData.keyAt(1), mTestData.valueAt(1));
            resultMap.put(mTestData.keyAt(1), mTestData.valueAt(1));

            TetherIngressKey nextKey = null;
            for (int i = 0; i < resultMap.size(); i++) {
                nextKey = bpfMap.getNextKey(nextKey);
                if (!resultMap.containsKey(nextKey)) {
                    fail("Unexpected result: " + nextKey);
                }
            }
            assertNull(bpfMap.getNextKey(nextKey));
        }
    }

    @Test
    public void testUpdateBpfMap() throws Exception {
        try (BpfMap<TetherIngressKey, TetherIngressValue> bpfMap = new BpfMap<>(
                TETHER_INGRESS_FS_PATH, BpfMap.BPF_F_RDWR,
                TetherIngressKey.class, TetherIngressValue.class)) {

            final TetherIngressKey key = mTestData.keyAt(0);
            final TetherIngressValue value = mTestData.valueAt(0);
            final TetherIngressValue value2 = mTestData.valueAt(1);
            assertFalse(bpfMap.deleteEntry(key));

            bpfMap.updateEntry(key, value);
            assertTrue(bpfMap.containsKey(key));
            final TetherIngressValue result = bpfMap.getValue(key);
            assertEquals(value, result);

            bpfMap.updateEntry(key, value2);
            assertTrue(bpfMap.containsKey(key));
            final TetherIngressValue result2 = bpfMap.getValue(key);
            assertEquals(value2, result2);

            assertTrue(bpfMap.deleteEntry(key));
            assertFalse(bpfMap.containsKey(key));
        }
    }

    @Test
    public void testInsertReplaceEntry() throws Exception {
        try (BpfMap<TetherIngressKey, TetherIngressValue> bpfMap = new BpfMap<>(
                TETHER_INGRESS_FS_PATH, BpfMap.BPF_F_RDWR,
                TetherIngressKey.class, TetherIngressValue.class)) {

            final TetherIngressKey key = mTestData.keyAt(0);
            final TetherIngressValue value = mTestData.valueAt(0);
            final TetherIngressValue value2 = mTestData.valueAt(1);

            try {
                bpfMap.replaceEntry(key, value);
                fail(key + " already exist");
            } catch (NoSuchElementException e) { }
            assertFalse(bpfMap.containsKey(key));

            bpfMap.insertEntry(key, value);
            assertTrue(bpfMap.containsKey(key));
            final TetherIngressValue result = bpfMap.getValue(key);
            assertEquals(value, result);
            try {
                bpfMap.insertEntry(key, value);
                fail(key + " do not exist");
            } catch (IllegalStateException e) { }

            bpfMap.replaceEntry(key, value2);
            assertTrue(bpfMap.containsKey(key));
            final TetherIngressValue result2 = bpfMap.getValue(key);
            assertEquals(value2, result2);
        }
    }

    @Test
    public void testIterateBpfMap() throws Exception {
        try (BpfMap<TetherIngressKey, TetherIngressValue> bpfMap = new BpfMap<>(
                TETHER_INGRESS_FS_PATH, BpfMap.BPF_F_RDWR,
                TetherIngressKey.class, TetherIngressValue.class)) {
            final ArrayMap<TetherIngressKey, TetherIngressValue> resultMap =
                    new ArrayMap<>(mOriginalData);
            resultMap.putAll(mTestData);

            for (int i = 0; i < resultMap.size(); i++) {
                bpfMap.updateEntry(resultMap.keyAt(i), resultMap.valueAt(i));
            }

            bpfMap.forEach((key, value) -> {
                if (mOriginalData.containsKey(key)) return;

                if (!value.equals(resultMap.remove(key))) {
                    fail("Unexpected result: " + key + ", value: " + value);
                }
            });

            assertEquals(mOriginalData, resultMap);
        }
    }
}
