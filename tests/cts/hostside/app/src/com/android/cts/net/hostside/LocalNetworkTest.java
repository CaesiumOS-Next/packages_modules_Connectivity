/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import static com.android.compatibility.common.util.SystemUtil.runShellCommand;
import static com.android.testutils.TestPermissionUtil.runAsShell;

import static org.junit.Assert.assertEquals;

import android.Manifest;
import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.stream.Stream;

@RunWith(AndroidJUnit4.class)
public class LocalNetworkTest {

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();
    private static final String TEST_APP_PKG = "com.android.cts.net.hostside.app2";
    private static final String LOGCAT_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS";
    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat(LOGCAT_DATE_FORMAT, Locale.US);

    @Test
    public void permissionGranted_checksLocalNetworkRestrictions() {
        assertEquals(
                mContext.getPackageManager().checkPermission(
                        Manifest.permission.NEARBY_WIFI_DEVICES, TEST_APP_PKG),
                PERMISSION_DENIED);
        Date time = Calendar.getInstance().getTime();
        runAsShell(Manifest.permission.GRANT_RUNTIME_PERMISSIONS,
                () -> mContext.getPackageManager().grantRuntimePermission(
                        TEST_APP_PKG,
                        Manifest.permission.NEARBY_WIFI_DEVICES,
                        mContext.getUser())
        );
        assertEquals(
                mContext.getPackageManager().checkPermission(
                        Manifest.permission.NEARBY_WIFI_DEVICES, TEST_APP_PKG),
                PERMISSION_GRANTED);

        Stream<String> parsedLogs = parseLogs(time);
        assertEquals(1, parsedLogs.count());
    }

    @Test
    public void permissionRevoked_checksLocalNetworkRestrictions() {
        assertEquals(
                mContext.getPackageManager().checkPermission(
                        Manifest.permission.NEARBY_WIFI_DEVICES, TEST_APP_PKG),
                PERMISSION_GRANTED);
        Date time = Calendar.getInstance().getTime();
        runAsShell(Manifest.permission.REVOKE_RUNTIME_PERMISSIONS,
                () -> mContext.getPackageManager().revokeRuntimePermission(
                        TEST_APP_PKG,
                        Manifest.permission.NEARBY_WIFI_DEVICES,
                        mContext.getUser())
        );
        assertEquals(
            mContext.getPackageManager().checkPermission(
                    Manifest.permission.NEARBY_WIFI_DEVICES, TEST_APP_PKG),
            PERMISSION_DENIED);

        Stream<String> parsedLogs = parseLogs(time);
        assertEquals(1, parsedLogs.count());
    }

    private static Stream<String> parseLogs(Date startTime) {
        String logcat = runShellCommand("logcat -v time -v year -d PermissionMonitor:D *:S");
        return Arrays.stream(logcat.split("\n")).filter(
                (log) -> log.contains("Eligible for local network restrictions")
                        && isAfterTestStart(log, startTime)
        );
    }

    private static boolean isAfterTestStart(String logString, Date startTime) {
        Date logDate = DATE_FORMAT.parse(logString, new ParsePosition(0));
        return (logDate != null && logDate.after(startTime));
    }
}
