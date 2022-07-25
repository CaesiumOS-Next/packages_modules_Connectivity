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

package com.android.cts.net.hostside;

import static android.Manifest.permission.NETWORK_SETTINGS;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_DOZABLE;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_LOW_POWER_STANDBY;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_OEM_DENY_1;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_OEM_DENY_2;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_OEM_DENY_3;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_POWERSAVE;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_RESTRICTED;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_STANDBY;
import static android.net.ConnectivityManager.FIREWALL_RULE_ALLOW;
import static android.net.ConnectivityManager.FIREWALL_RULE_DENY;
import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.AF_INET6;
import static android.system.OsConstants.SOCK_DGRAM;
import static android.system.OsConstants.SOCK_STREAM;

import static com.android.compatibility.common.util.SystemUtil.runShellCommandOrThrow;
import static com.android.compatibility.common.util.SystemUtil.runWithShellPermissionIdentity;
import static com.android.testutils.Cleanup.testAndCleanup;
import static com.android.testutils.DevSdkIgnoreRuleKt.SC_V2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Instrumentation;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.InetAddresses;
import android.net.NetworkStats;
import android.net.TrafficStats;
import android.os.Process;
import android.system.Os;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.testutils.DevSdkIgnoreRule;

import org.junit.Before;
import org.junit.Test;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@DevSdkIgnoreRule.IgnoreUpTo(SC_V2)
public class BpfNetMapsTest {

    private static final int TEST_COOKIE_TAG = 0xab;
    private static final int CLEAN_UP_SOCKET_COUNT = 100;
    private static final int SOCKET_TIMEOUT_MS = 100;

    private Context mContext;
    private Instrumentation mInstrumentation;
    private ConnectivityManager mCm;

    @Before
    public void setUp() throws Exception {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mContext = mInstrumentation.getContext();
        mCm = mContext.getSystemService(ConnectivityManager.class);
    }

    private void checkCookieTagMap(final boolean expectExist) {
        final String dump = runShellCommandOrThrow("dumpsys connectivity trafficcontroller");

        Log.e("MDBG", String.format("tag=0x%x", TEST_COOKIE_TAG));
        if (expectExist) {
            assertTrue(dump.contains(String.format("tag=0x%x", TEST_COOKIE_TAG)));
        } else {
            assertFalse(dump.contains(String.format("tag=0x%x", TEST_COOKIE_TAG)));
        }
    }

    private void doTestTagSocketCleanUp(final int family, final int type) throws Exception {
        TrafficStats.setThreadStatsTag(TEST_COOKIE_TAG);
        checkCookieTagMap(false /* expectExist */);

        List<FileDescriptor> fds = new ArrayList<>();
        for (int i = 0; i < CLEAN_UP_SOCKET_COUNT; i++) {
            final FileDescriptor fd = Os.socket(family, type, 0 /* protocol */);
            fds.add(fd);
        }
        checkCookieTagMap(true /* expectExist */);

        for (final FileDescriptor fd: fds) {
            Os.close(fd);
        }
        checkCookieTagMap(false /* expectExist */);
    }

    @Test
    public void testTagSocketCleanUp() throws Exception {
        doTestTagSocketCleanUp(AF_INET, SOCK_STREAM);
        doTestTagSocketCleanUp(AF_INET, SOCK_DGRAM);
        doTestTagSocketCleanUp(AF_INET6, SOCK_STREAM);
        doTestTagSocketCleanUp(AF_INET6, SOCK_DGRAM);
    }

    private void checkFirewallBlocking(final DatagramSocket srcSock, final DatagramSocket dstSock,
            final boolean expectBlock) throws Exception {
        final Random random = new Random();
        final byte[] sendData = new byte[100];
        random.nextBytes(sendData);

        final DatagramPacket pkt = new DatagramPacket(sendData, sendData.length,
                InetAddresses.parseNumericAddress("::1"), dstSock.getLocalPort());
        try {
            srcSock.send(pkt);
        } catch (IOException e) {
            if (expectBlock) {
                return;
            }
            fail("Expect not to be blocked by firewall but sending packet was blocked");
        }

        if (expectBlock) {
            fail("Expect to be blocked by firewall but sending packet was not blocked");
        }

        dstSock.receive(pkt);
        assertArrayEquals(sendData, pkt.getData());
    }

    private static final boolean EXPECT_PASS = false;
    private static final boolean EXPECT_BLOCK = true;
    private static final boolean ALLOWLIST = true;
    private static final boolean DENYLIST = false;

    private void doTestFirewallBlocking(final int chain, final boolean isAllowList) {
        final int myUid = Process.myUid();
        final int ruleToAddMatch = isAllowList ? FIREWALL_RULE_ALLOW : FIREWALL_RULE_DENY;
        final int ruleToRemoveMatch = isAllowList ? FIREWALL_RULE_DENY : FIREWALL_RULE_ALLOW;

        runWithShellPermissionIdentity(() -> {
            // Firewall chain status will be restored after the test.
            final boolean wasChainEnabled = mCm.getFirewallChainEnabled(chain);
            final DatagramSocket srcSock = new DatagramSocket();
            final DatagramSocket dstSock = new DatagramSocket();
            testAndCleanup(() -> {
                if (wasChainEnabled) {
                    mCm.setFirewallChainEnabled(chain, false /* enable */);
                }
                dstSock.setSoTimeout(SOCKET_TIMEOUT_MS);

                // Chain disabled, UID not on chain.
                checkFirewallBlocking(srcSock, dstSock, EXPECT_PASS);

                // Chain enabled, UID not on chain.
                mCm.setFirewallChainEnabled(chain, true /* enable */);
                assertTrue(mCm.getFirewallChainEnabled(chain));
                checkFirewallBlocking(srcSock, dstSock, isAllowList ? EXPECT_BLOCK : EXPECT_PASS);

                // Chain enabled, UID on chain.
                mCm.setUidFirewallRule(chain, myUid, ruleToAddMatch);
                checkFirewallBlocking(srcSock, dstSock, isAllowList ?  EXPECT_PASS : EXPECT_BLOCK);

                // Chain disabled, UID on chain.
                mCm.setFirewallChainEnabled(chain, false /* enable */);
                assertFalse(mCm.getFirewallChainEnabled(chain));
                checkFirewallBlocking(srcSock, dstSock, EXPECT_PASS);

                // Chain disabled, UID not on chain.
                mCm.setUidFirewallRule(chain, myUid, ruleToRemoveMatch);
                checkFirewallBlocking(srcSock, dstSock, EXPECT_PASS);
            }, /* cleanup */ () -> {
                    srcSock.close();
                    dstSock.close();
                }, /* cleanup */ () -> {
                    // Restore the global chain status
                    mCm.setFirewallChainEnabled(chain, wasChainEnabled);
                }, /* cleanup */ () -> {
                    try {
                        mCm.setUidFirewallRule(chain, myUid, ruleToRemoveMatch);
                    } catch (IllegalStateException ignored) {
                        // Removing match causes an exception when the rule entry for the uid does
                        // not exist. But this is fine and can be ignored.
                    }
                });
        }, NETWORK_SETTINGS);
    }

    @Test
    public void testFirewallBlocking() {
        // ALLOWLIST means the firewall denies all by default, uids must be explicitly allowed
        doTestFirewallBlocking(FIREWALL_CHAIN_DOZABLE, ALLOWLIST);
        doTestFirewallBlocking(FIREWALL_CHAIN_POWERSAVE, ALLOWLIST);
        doTestFirewallBlocking(FIREWALL_CHAIN_RESTRICTED, ALLOWLIST);
        doTestFirewallBlocking(FIREWALL_CHAIN_LOW_POWER_STANDBY, ALLOWLIST);

        // DENYLIST means the firewall allows all by default, uids must be explicitly denyed
        doTestFirewallBlocking(FIREWALL_CHAIN_STANDBY, DENYLIST);
        doTestFirewallBlocking(FIREWALL_CHAIN_OEM_DENY_1, DENYLIST);
        doTestFirewallBlocking(FIREWALL_CHAIN_OEM_DENY_2, DENYLIST);
        doTestFirewallBlocking(FIREWALL_CHAIN_OEM_DENY_3, DENYLIST);
    }

    @Test
    public void testDataProfiling() throws Exception {
        TrafficStats.startDataProfiling(mContext);

        final DatagramSocket srcSocket = new DatagramSocket();
        final DatagramSocket dstSocket = new DatagramSocket();
        dstSocket.setSoTimeout(SOCKET_TIMEOUT_MS);

        final Random random = new Random();
        final byte[] sendData = new byte[100];
        random.nextBytes(sendData);
        final DatagramPacket pkt = new DatagramPacket(sendData, sendData.length,
                InetAddresses.parseNumericAddress("::1"), dstSocket.getLocalPort());
        srcSocket.send(pkt);
        dstSocket.receive(pkt);

        final NetworkStats stats = TrafficStats.stopDataProfiling(mContext);
        final NetworkStats.Entry entry = stats.getTotal(null, Process.myUid());
        assertNotEquals(0, entry.txPackets);
    }
}
