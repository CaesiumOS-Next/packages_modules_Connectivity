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

package android.net.cts;

import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.AF_INET6;
import static android.system.OsConstants.SOCK_DGRAM;
import static android.system.OsConstants.SOCK_STREAM;

import static com.android.compatibility.common.util.SystemUtil.runShellCommandOrThrow;
import static com.android.testutils.DevSdkIgnoreRuleKt.SC_V2;

import static org.junit.Assert.assertTrue;

import android.net.TrafficStats;
import android.os.Process;
import android.system.Os;

import com.android.testutils.ConnectivityModuleTest;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.List;

@RunWith(DevSdkIgnoreRunner.class)
public class SkDestroyListenerTest {
    private static final int COOKIE_TAG = 0xab;
    private static final int SOCKET_COUNT = 100;
    private static final int CHECK_MAX_TRIES = 5;
    private static final int CHECK_INTERVAL_MS = 1000;

    private boolean hasTaggedSocket() {
        final String dump = runShellCommandOrThrow("dumpsys connectivity trafficcontroller");
        return dump.contains(String.format("tag=0x%x uid=%d", COOKIE_TAG, Process.myUid()));
    }

    private boolean noTaggedSocket() {
        return !hasTaggedSocket();
    }

    private boolean waitNoTaggedSocket() throws InterruptedException {
        for (int i = 0; i < CHECK_MAX_TRIES; i++) {
            if (noTaggedSocket()) return true;
            Thread.sleep(CHECK_INTERVAL_MS);
        }
        return false;
    }

    private void doTestSkDestroyListener(final int family, final int type) throws Exception {
        TrafficStats.setThreadStatsTag(COOKIE_TAG);
        assertTrue("There are tagged sockets before test", noTaggedSocket());

        final List<FileDescriptor> fds = new ArrayList<>();
        for (int i = 0; i < SOCKET_COUNT; i++) {
            final FileDescriptor fd = Os.socket(family, type, 0 /* protocol */);
            fds.add(fd);
        }
        assertTrue("There is no tagged socket after creating sockets", hasTaggedSocket());

        for (final FileDescriptor fd: fds) {
            Os.close(fd);
        }
        assertTrue("There are tagged sockets after closing sockets", waitNoTaggedSocket());
    }

    @Test @IgnoreUpTo(SC_V2) @ConnectivityModuleTest
    public void testSkDestroyListener() throws Exception {
        doTestSkDestroyListener(AF_INET, SOCK_STREAM);
        doTestSkDestroyListener(AF_INET, SOCK_DGRAM);
        doTestSkDestroyListener(AF_INET6, SOCK_STREAM);
        doTestSkDestroyListener(AF_INET6, SOCK_DGRAM);
    }
}
