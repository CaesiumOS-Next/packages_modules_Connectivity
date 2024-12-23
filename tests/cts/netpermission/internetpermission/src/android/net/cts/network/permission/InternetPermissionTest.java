/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net.cts.networkpermission.internetpermission;

import static com.android.compatibility.common.util.SystemUtil.runShellCommand;
import static com.android.testutils.TestPermissionUtil.runAsShell;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.app.compat.CompatChanges;
import android.content.Context;
import android.os.Process;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.Log;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

//import com.android.cts.install.lib.TestApp;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.Socket;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
* Test that protected android.net.ConnectivityManager methods cannot be called without
* permissions
*/
@RunWith(AndroidJUnit4.class)
public class InternetPermissionTest {

    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();
//    private static final TestApp testApp = new TestApp(
//                    "TestApp",
//                            "com.android.testutils.connectivitypreparer",
//                            1,
//                            false,
//                            "ConnectivityTestPreparer.apk");

    /**
     * Verify that create inet socket failed because of the permission is missing.
     * <p>Tests Permission:
     *   {@link android.Manifest.permission#INTERNET}.
     */
    @SmallTest
    @Test
    public void testCreateSocket() throws Exception {
        try {
            Socket socket = new Socket("example.com", 80);
            fail("Ceate inet socket did not throw SecurityException as expected");
        } catch (SecurityException e) {
            // expected
        }
    }


    /**
     * Verify that permission listener checks for local network restrictions.
     */
    @Test
    @RequiresFlagsEnabled("com.android.net.flags.restrict_local_network")
    public void testPermissionChange_checksForLocalNetworkRestrictions() throws Exception {
        runShellCommand("am compat enable 365139289 com.android.testutils.connectivitypreparer");
        boolean compat = runAsShell(
                "android.permission.LOG_COMPAT_CHANGE",
                "android.permission.READ_COMPAT_CHANGE_CONFIG",
//                Manifest.permission.LOG_COMPAT_CHANGE,
//                Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
                () ->
                    CompatChanges.isChangeEnabled(
                            365139289L, "com.android.testutils.connectivitypreparer",
//testApp.getPackageName(),//mContext.getPackageName(),
                            mContext.getUser())
                );
        Log.d("PermissionMonitorTest", "compat change enabled? " + compat);
        assumeTrue(compat);
        Date time = Calendar.getInstance().getTime();
        Log.d("PermissionMonitorTest", "time is " + time);
        runAsShell(Manifest.permission.GRANT_RUNTIME_PERMISSIONS, () ->
            mContext.getPackageManager().grantRuntimePermission(
                    mContext.getPackageName(),
//                    Manifest.permission.NEARBY_WIFI_DEVICES,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                    Process.myUserHandle())
        );

        // Pull the logcat since the app started, filter for tags
        // This command should look something like this:
        // adb logcat -d -t '03-27 21:35:05.392' -s "RootlessGpuDebugDeviceActivity,nullLayerC"
        String logs = runShellCommand("logcat -v time -v year -d");
//        Log.d("PermissionMonitor", "logs look like this: " + logs);
        Stream<String> parsed = Arrays.stream(logs.split("\n")).filter(
                (l) -> l.contains("Eligible for local network restrictions") && parseLogTime(l).after(time)
        );

//        List<String> parsed = LogcatParser.INSTANCE.findSpecificLogAfter(logs, "Eligible for local network restrictions", time);
        assertTrue(parsed.count() > 0);
//        String logcat = runShellCommand(
//                "logcat -d " +
//                        "-t '" + removeWhitespace(appStartTime) + "' " +
//                        "-s \"PermissionMonitor\"");
    }

    private static Pattern LOGCAT_LOGS_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}.\\d{3} ");
    private static String LOGCAT_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS";
    private static SimpleDateFormat dateFormate = new SimpleDateFormat(LOGCAT_DATE_FORMAT, Locale.US);
    private static Date parseLogTime(String logString) {
        return dateFormate.parse(logString, new ParsePosition(0));
    }
}
