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

import static org.junit.Assert.assertTrue;

import android.net.TrafficStats;
import android.system.Os;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class SkDestroyListenerTest {
    private static final int TEST_COOKIE_TAG = 0xab;
    private static final int SOCKET_COUNT = 100;
    private static final int CHECK_MAX_TRIES = 10;
    private static final int CHECK_INTERVAL_MS = 1000;

    private boolean socketIsTagged() {
        final String dump = runShellCommandOrThrow("dumpsys connectivity trafficcontroller");
        return dump.contains(String.format("tag=0x%x", TEST_COOKIE_TAG));
    }

    private boolean socketIsNotTagged() {
        return !socketIsTagged();
    }

    private boolean waitSocketIsNotTagged() throws InterruptedException {
        for (int i = 0; i < CHECK_MAX_TRIES; i++) {
            if (socketIsNotTagged()) return true;
            Thread.sleep(CHECK_INTERVAL_MS);
        }
        return false;
    }

    private void doTestSkDestroyListener(final int family, final int type) throws Exception {
        TrafficStats.setThreadStatsTag(TEST_COOKIE_TAG);
        assertTrue("There are tagged sockets before test", socketIsNotTagged());

        List<FileDescriptor> fds = new ArrayList<>();
        for (int i = 0; i < SOCKET_COUNT; i++) {
            final FileDescriptor fd = Os.socket(family, type, 0 /* protocol */);
            fds.add(fd);
        }
        assertTrue("There is no tagged socket after creating sockets", socketIsTagged());

        for (final FileDescriptor fd: fds) {
            Os.close(fd);
        }
        assertTrue("There are tagged sockets after closing sockets", waitSocketIsNotTagged());
    }

    @Test
    public void testSkDestroyListener() throws Exception {
        doTestSkDestroyListener(AF_INET, SOCK_STREAM);
        doTestSkDestroyListener(AF_INET, SOCK_DGRAM);
        doTestSkDestroyListener(AF_INET6, SOCK_STREAM);
        doTestSkDestroyListener(AF_INET6, SOCK_DGRAM);
    }
}
