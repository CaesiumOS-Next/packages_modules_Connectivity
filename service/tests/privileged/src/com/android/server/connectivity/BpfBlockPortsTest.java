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

package com.android.server.connectivity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.Build;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.ArrayMap;

import com.android.net.module.util.BpfMap;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.InetAddress;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

import android.net.IPV4;
import android.net.IPV4V6;
import android.net.ProtocolType;


@RunWith(DevSdkIgnoreRunner.class)
//@IgnoreUpTo(Build.VERSION_CODES.S)
public final class BpfBlockPortsTest {
    // Sync from packages/modules/Connectivity/Tethering/bpf_progs/offload.c.
    private static final int TEST_MAP_SIZE = 16;
    private static final String BLOCK_PORTS_FS_PATH =
            "/sys/fs/bpf/connectivity/map_offload_connectivity_blocked_ports_map";

    private ArrayMap<PortBlockKey, int> mTestData;
    private BpfMap<TetherDownstream6Key, Tether6Value> mTestMap;

    @BeforeClass
    public static void setupOnce() {
        // Not needed since is present in ConnectivityServiceInitializer?
        //     System.loadLibrary("service-connectivity");
    }

    @Before
    public void setUp() throws Exception {
        initTestMap();
        assertNotNull(mTestMap);
    }

    private void initTestMap() throws Exception {
        mTestMap = new BpfMap<>(BLOCK_PORTS_FS_PATH, BpfMap.BPF_F_RDWR, PortBlockKey.class,
                PortBlockValue.class);
        mTestMap.insertEntry(PortBlockKey(1111, IPV4, ProtocolType::UDP), PortBlockValue(1));
        mTestMap.insertEntry(PortBlockKey(2222, IPV4, ProtocolType::TCP), PortBlockValue(1));
        mTestMap.insertEntry(PortBlockKey(3333, IPV4, ProtocolType::TCP_UDP), PortBlockValue(1));
        mTestMap.insertEntry(PortBlockKey(1616, IPV4V6, ProtocolType::UDP), PortBlockValue(1));
        mTestMap.insertEntry(PortBlockKey(2626, IPV4V6, ProtocolType::TCP), PortBlockValue(1));
        mTestMap.insertEntry(PortBlockKey(3636, IPV4V6, ProtocolType::TCP_UDP), PortBlockValue(1));
    }

    @Test
    public void testBlockIpv4UdpPort() throws Exception {
        // test that port 1111 for IPv4 is blocked for UDP

        // test that port 1110/1112 is not blocked for UDP
    }

    @Test
    public void testBlockIpv4TcpPort() throws Exception {
        // test that port 2222 for IPv4 is blocked for TCP

        // test that port 2221/2223 is not blocked for TCP
    }

    @Test
    public void testBlockIpv4UdpTcpPort() throws Exception {
        // test that port 3333 for IPv4 is blocked for UDP_TCP

        // test that port 3332/3334 is not blocked for UDP_TCP
    }

    @Test
    public void testBlockIpv4v6UdpPort() throws Exception {
        // test that port 1616 for IPv4v6 is blocked for UDP

        // test that port 1615/1617 is not blocked for UDP
    }

    @Test
    public void testBlockIpv4v6TcpPort() throws Exception {
        // test that port 2626 for IPv4v6 is blocked for UDP

        // test that port 2625/2627 is not blocked for UDP
    }

    @Test
    public void testBlockIpv4v6UdpTcpPort() throws Exception {
        // test that port 3636 for IPv4v6 is blocked for UDP

        // test that port 3635/3637 is not blocked for UDP
    }
}
