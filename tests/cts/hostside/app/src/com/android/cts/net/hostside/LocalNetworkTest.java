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

import static com.android.compatibility.common.util.SystemUtil.runShellCommand;
import static com.android.testutils.TestPermissionUtil.runAsShell;

import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.UiAutomation;
import android.content.Context;
import android.util.Log;

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
import java.util.regex.Pattern;
import java.util.stream.Stream;

@RunWith(AndroidJUnit4.class)
public class LocalNetworkTest {

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();
    private final Context mTargetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static final UiAutomation sUiAutomation =
            InstrumentationRegistry.getInstrumentation().getUiAutomation();


    @Test
    public void permissionGranted_checksLocalNetworkRestrictions() throws InterruptedException {
        Log.d("LocalNetworkTest", "initial permission state is " +
                mTargetContext.getPackageManager().checkPermission(
                        Manifest.permission.NEARBY_WIFI_DEVICES, mTargetContext.getPackageName())); // granted = 0
        Date time = Calendar.getInstance().getTime();
        Log.d("LocalNetworkTest", "start time is " + time);
        runAsShell(Manifest.permission.GRANT_RUNTIME_PERMISSIONS,
                () -> mTargetContext.getPackageManager().grantRuntimePermission(
                        mTargetContext.getPackageName(),
                        Manifest.permission.NEARBY_WIFI_DEVICES,
                        mTargetContext.getUser())
        );
        Log.d("LocalNetworkTest", "new permission state is " +
                mTargetContext.getPackageManager().checkPermission(
                        Manifest.permission.NEARBY_WIFI_DEVICES, mTargetContext.getPackageName())); // granted = 0
        String logcat = runShellCommand("logcat -v time -v year -d PermissionMonitor:D *:S");

        Stream<String> parsedLogs = parseLogs(time, logcat);
        assertTrue(parsedLogs.count() > 0);
    }

    @Test
    public void permissionRevoked_checksLocalNetworkRestrictions() throws InterruptedException {
        Log.d("LocalNetworkTest", "initial permission state is " +
                mTargetContext.getPackageManager().checkPermission(
                        Manifest.permission.NEARBY_WIFI_DEVICES, mTargetContext.getPackageName())); // granted = 0
        Date time = Calendar.getInstance().getTime();
        Log.d("LocalNetworkTest", "start time is " + time);
        runAsShell(Manifest.permission.REVOKE_RUNTIME_PERMISSIONS,
                () -> mTargetContext.getPackageManager().revokeRuntimePermission(
                        mTargetContext.getPackageName(),
                        Manifest.permission.NEARBY_WIFI_DEVICES,
                        mTargetContext.getUser())
        );
        Thread.sleep(10000);
        Log.d("LocalNetworkTest", "new permission state is " +
                mTargetContext.getPackageManager().checkPermission(
                        Manifest.permission.NEARBY_WIFI_DEVICES, mTargetContext.getPackageName())); // granted = 0
        String logcat = runShellCommand("logcat -v time -v year -d PermissionMonitor:D *:S");
        Stream<String> parsedLogs = parseLogs(time, logcat);

        assertTrue(parsedLogs.count() == 0);
    }

    private static Stream<String> parseLogs(Date startTime, String logs) {
        String[] splitLogs = logs.split("\n");
        return Arrays.stream(splitLogs).filter(//logs.split("\n")).filter(
                (l) -> l.contains("Eligible for local network restrictions") && isAfterTestStart(l, startTime)
        );
    }

    private static Pattern LOGCAT_LOGS_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}.\\d{3} ");
    private static String LOGCAT_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS";
    private static SimpleDateFormat dateFormate = new SimpleDateFormat(LOGCAT_DATE_FORMAT, Locale.US);
    private static boolean isAfterTestStart(String logString, Date startTime) {
        Date logDate = dateFormate.parse(logString, new ParsePosition(0));
//        Log.d("LocalNetworkTest", "parsed log date is " + logDate);
        return (logDate != null && logDate.after(startTime));
    }
}
