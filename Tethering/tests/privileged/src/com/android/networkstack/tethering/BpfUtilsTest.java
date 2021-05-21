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

package com.android.networkstack.tethering;

import static com.android.networkstack.tethering.BpfUtils.DOWNSTREAM;
import static com.android.networkstack.tethering.BpfUtils.IPV4;
import static com.android.networkstack.tethering.BpfUtils.IPV6;
import static com.android.networkstack.tethering.BpfUtils.UPSTREAM;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.net.util.InterfaceParams;
import android.os.Build;

import androidx.test.runner.AndroidJUnit4;

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

@RunWith(AndroidJUnit4.class)
@IgnoreUpTo(Build.VERSION_CODES.R)
public final class BpfUtilsTest {
    @Rule
    public final DevSdkIgnoreRule mIgnoreRule = new DevSdkIgnoreRule();

    private static final boolean ETHERNET = true;
    private static final boolean RAW = false;
    private static final String LOOPBACK_NAME = "lo";

    @BeforeClass
    public static void setupOnce() {
        System.loadLibrary("tetherutilsjni");
    }

    @After
    public void tearDown() throws Exception {
        // Do cleanup and ignore the errors if any.
        final InterfaceParams params = InterfaceParams.getByName(LOOPBACK_NAME);
        if (params != null) {
            try {
                BpfUtils.tcQdiscDelDevClsact(params.index);
            } catch (IOException e) {
                // ignore.
            }
        }
    }

    void checkErrorMessageDetachProgram(final String msg, final boolean ipv6) {
        final String expectedMsgV6 = ""
                + "tc filter del dev (1[lo]) ingress prio PRIO_TETHER6 protocol ipv6 failure: "
                + "java.io.IOException: NLMSG_ERROR message return error: Invalid argument";
        final String expectedMsgV4 = ""
                + "tc filter del dev (1[lo]) ingress prio PRIO_TETHER4 protocol ip failure: "
                + "java.io.IOException: NLMSG_ERROR message return error: Invalid argument";

        if (ipv6) {
            assertEquals(expectedMsgV6, msg);
        } else {
            assertEquals(expectedMsgV4, msg);
        }
    }

    // Sync from system/netd/server/OffloadUtilsTest.cpp.
    void checkAttachDetachBpfFilterClsactLo(final boolean downstream, final boolean ipv6,
            final boolean ethernet) throws Exception {
        // TODO: Get the kernel version and check the error code of BpfUtils.detachProgram while
        // the device clsact exists and target filter doesn't exists. The error code depends on
        // the kernel verion. Older kernels return EINVAL instead of ENOENT due to lacking proper
        // error propagation...
        // See #checkAttachDetachBpfFilterClsactLo in system/netd/server/OffloadUtilsTest.cpp.

        // This attaches and detaches a clsact plus ebpf program to loopback
        // interface, but it should not affect traffic by virtue of us not
        // actually populating the ebpf control map.
        // Furthermore: it only takes fractions of a second.
        final InterfaceParams params = InterfaceParams.getByName(LOOPBACK_NAME);
        assertNotNull(params);

        try {
            BpfUtils.detachProgram(params.name, ipv6);
        } catch (IOException expected) {
            checkErrorMessageDetachProgram(expected.getMessage(), ipv6);
        }

        BpfUtils.tcQdiscAddDevClsact(params.index);

        // TODO: call BpfUtils.detachProgram to check the error code which is ENOENT or EINVAL.
        // Note that the error code depends on the kernel version.

        BpfUtils.attachProgram(params.name, downstream, ipv6, ethernet);
        BpfUtils.detachProgram(params.name, ipv6);

        // TODO: call BpfUtils.detachProgram to check the error code which is ENOENT or EINVAL.
        // Note that the error code depends on the kernel version.

        BpfUtils.tcQdiscDelDevClsact(params.index);

        try {
            BpfUtils.detachProgram(params.name, ipv6);
        } catch (IOException expected) {
            checkErrorMessageDetachProgram(expected.getMessage(), ipv6);
        }
    }

    @Test
    public void testAttachBpfFilterEthernetClsactIngressLo() throws Exception {
        checkAttachDetachBpfFilterClsactLo(DOWNSTREAM, ETHERNET, IPV6);
        checkAttachDetachBpfFilterClsactLo(DOWNSTREAM, ETHERNET, IPV4);
        checkAttachDetachBpfFilterClsactLo(DOWNSTREAM, RAW, IPV6);
        checkAttachDetachBpfFilterClsactLo(DOWNSTREAM, RAW, IPV4);
        checkAttachDetachBpfFilterClsactLo(UPSTREAM, ETHERNET, IPV6);
        checkAttachDetachBpfFilterClsactLo(UPSTREAM, ETHERNET, IPV4);
        checkAttachDetachBpfFilterClsactLo(UPSTREAM, RAW, IPV6);
        checkAttachDetachBpfFilterClsactLo(UPSTREAM, RAW, IPV4);
    }
}
