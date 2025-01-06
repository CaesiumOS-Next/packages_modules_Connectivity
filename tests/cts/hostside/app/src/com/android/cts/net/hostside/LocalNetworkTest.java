package com.android.cts.net.hostside;

import static com.android.compatibility.common.util.SystemUtil.runShellCommand;
import static com.android.testutils.TestPermissionUtil.runAsShell;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.compat.CompatChanges;
import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
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

//    @Before
//    public void setUp() {
//        InstrumentationRegistry.getInstrumentation().getUiAutomation()
//                .adoptShellPermissionIdentity(Manifest.permission.LOG_COMPAT_CHANGE,
//                        Manifest.permission.READ_COMPAT_CHANGE_CONFIG);
//    }

    @Test
    public void permissionChanged_checkLocalNetworkRestrictions() {
        Date time = Calendar.getInstance().getTime();
        Log.d("LocalNetworkTest", "start time is " + time);
        runAsShell(Manifest.permission.GRANT_RUNTIME_PERMISSIONS, () ->
            mContext.getPackageManager().grantRuntimePermission(
                    mContext.getPackageName(),
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                    mContext.getUser())
        );
        String logs = runShellCommand("logcat -v time -v year -d PermissionMonitor:D *:S");
        String[] splitLogs = logs.split("\n");
        String firstLog = splitLogs[0];
        Stream<String> parsed = Arrays.stream(splitLogs).filter(//logs.split("\n")).filter(
                (l) -> l.contains("Eligible for local network restrictions") && isAfterTestStart(l, time)
        );
        assertTrue(parsed.count() > 0);
    }

    private static Pattern LOGCAT_LOGS_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}.\\d{3} ");
    private static String LOGCAT_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS";
    private static SimpleDateFormat dateFormate = new SimpleDateFormat(LOGCAT_DATE_FORMAT, Locale.US);
    private static boolean isAfterTestStart(String logString, Date startTime) {
        Date logDate = dateFormate.parse(logString, new ParsePosition(0));
        Log.d("LocalNetworkTest", "parsed log date is " + logDate);
        return (logDate != null && logDate.after(startTime));
    }
}
