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

import static com.android.networkstack.tethering.BpfMap.BPF_MAP_TYPE_HASH;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.net.MacAddress;
import android.net.util.Struct;
import android.util.ArrayMap;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import android.util.Log;

@RunWith(AndroidJUnit4.class)
public final class BpfMapTest {
    private ArrayMap<TetherIngressKey, TetherIngressValue> mTestData;
    private static final int KEY_SIZE = 20;
    private static final int VALUE_SIZE = 20;
    private static final int MAX_ENTRIES = 5;

    private static final String TETHER_INGRESS_FS_PATH = "/sys/fs/bpf/map_offload_tether_ingress_map";

    @BeforeClass
    public static void setupOnce() {
        System.loadLibrary("tetherutilsjni");
    }

    @Before
    public void setUp() throws Exception {
        mTestData = new ArrayMap<>();
        mTestData.put(createTetherIngressKey(1, "2001:1111:1111::1111"),
                createTetherIngressValue(11, "01:01:01:01:01:01", "11:11:11:11:11:11", ETH_P_IPV6,
                1000));
        mTestData.put(createTetherIngressKey(2, "2001:2222:2222::2222"),
                createTetherIngressValue(22, "02:02:02:02:02:02", "22:22:22:22:22:22", ETH_P_IPV6,
                1000));
        mTestData.put(createTetherIngressKey(3, "2001:3333:3333::3333"),
                createTetherIngressValue(33, "03:03:03:03:03:03", "33:33:33:33:33:33", ETH_P_IPV6,
                1000));
    }

    private TetherIngressKey createTetherIngressKey(int iif, String address) throws Exception{
        final InetAddress ipv6Address = InetAddress.getByName(address);

        return new TetherIngressKey(iif, ipv6Address.getAddress());
    }

    private TetherIngressValue createTetherIngressValue(int oif, String src, String dst, int proto,
            int pmtu) throws Exception {
        final MacAddress srcMac = MacAddress.fromString(src);
        final MacAddress dstMac = MacAddress.fromString(dst);

        return new TetherIngressValue(oif, dstMac.toByteArray(), srcMac.toByteArray(), proto, pmtu);
    }

    @Test
    public void testGetFd() throws Exception {
        try (BpfMap readOnlyMap = new BpfMap<>(TETHER_INGRESS_FS_PATH, BpfMap.BPF_F_RDONLY,
                KEY_SIZE, VALUE_SIZE)) { }
        try (BpfMap writeOnlyMap = new BpfMap<>(TETHER_INGRESS_FS_PATH, BpfMap.BPF_F_WRONLY,
                KEY_SIZE, VALUE_SIZE)) { }
        try (BpfMap readWriteMap = new BpfMap<>(TETHER_INGRESS_FS_PATH, BpfMap.BPF_F_RDWR,
                KEY_SIZE, VALUE_SIZE)) { }
    }

    @Test
    public void testEmptyMap() throws Exception {
        try (BpfMap<TetherIngressKey, TetherIngressValue> bpfMap = new BpfMap<>(BPF_MAP_TYPE_HASH,
                KEY_SIZE, VALUE_SIZE, MAX_ENTRIES)) {
            assertFalse(bpfMap.containsKey(mTestData.keyAt(0)));
            assertNull(bpfMap.getFirstKey(TetherIngressKey.class));
            assertNull(bpfMap.getValue(mTestData.keyAt(0), TetherIngressValue.class));
        }
    }

    @Test
    public void testGetNextKey() throws Exception {
        try (BpfMap<TetherIngressKey, TetherIngressValue> bpfMap = new BpfMap<>(BPF_MAP_TYPE_HASH,
                KEY_SIZE, VALUE_SIZE, MAX_ENTRIES)) {
            assertNull(bpfMap.getFirstKey(TetherIngressKey.class));
            assertNull(bpfMap.getNextKey(null, TetherIngressKey.class));

            bpfMap.updateEntry(mTestData.keyAt(0), mTestData.valueAt(0));
            TetherIngressKey firstKey = mTestData.keyAt(0);
            assertEquals(firstKey, bpfMap.getFirstKey(TetherIngressKey.class));
            assertNull(bpfMap.getNextKey(firstKey, TetherIngressKey.class));

            bpfMap.updateEntry(mTestData.keyAt(1), mTestData.valueAt(1));
            firstKey = bpfMap.getFirstKey(TetherIngressKey.class);
            final TetherIngressKey nextKey = mTestData.keyAt(1).equals(firstKey) ?
                    mTestData.keyAt(0) : mTestData.keyAt(1);
            assertEquals(nextKey, bpfMap.getNextKey(firstKey, TetherIngressKey.class));
            assertNull(bpfMap.getNextKey(nextKey, TetherIngressKey.class));
        }
    }

    @Test
    public void testUpdateBpfMap() throws Exception {
        try (BpfMap<TetherIngressKey, TetherIngressValue> bpfMap = new BpfMap<>(BPF_MAP_TYPE_HASH,
                KEY_SIZE, VALUE_SIZE, MAX_ENTRIES)) {
            final TetherIngressKey key = mTestData.keyAt(0);
            final TetherIngressValue value = mTestData.valueAt(0);
            assertFalse(bpfMap.deleteEntry(key));

            bpfMap.updateEntry(key, value);
            assertTrue(bpfMap.containsKey(key));
            final TetherIngressValue result = bpfMap.getValue(key, TetherIngressValue.class);
            assertEquals(value, result);
            assertTrue(bpfMap.deleteEntry(key));
            assertFalse(bpfMap.containsKey(key));
        }
    }

    @Test
    public void testIterateBpfMap() throws Exception {
        try (BpfMap<TetherIngressKey, TetherIngressValue> bpfMap = new BpfMap<>(BPF_MAP_TYPE_HASH,
                KEY_SIZE, VALUE_SIZE, MAX_ENTRIES)) {
            final ArrayMap<TetherIngressKey, TetherIngressValue> resultMap =
                    new ArrayMap<>(mTestData);

            for (int i = 0; i < resultMap.size(); i++) {
                bpfMap.updateEntry(resultMap.keyAt(i), resultMap.valueAt(i));
            }

            bpfMap.forEach((rawKey, rawValue) -> {
                final ByteBuffer keyBuf = ByteBuffer.wrap(rawKey);
                keyBuf.order(ByteOrder.LITTLE_ENDIAN);
                final TetherIngressKey key = Struct.parse(TetherIngressKey.class, keyBuf);

                final ByteBuffer valueBuf = ByteBuffer.wrap(rawValue);
                valueBuf.order(ByteOrder.LITTLE_ENDIAN);
                final TetherIngressValue value = Struct.parse(TetherIngressValue.class, valueBuf);

                if (!value.equals(resultMap.remove(key))) {
                    fail("Unexpected result: " + key + ", value: " + value);
                }
            });
            assertTrue(resultMap.isEmpty());
        }
    }
}
