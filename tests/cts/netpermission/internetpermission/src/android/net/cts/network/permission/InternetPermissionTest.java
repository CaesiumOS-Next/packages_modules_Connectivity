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

//import static android.net.connectivity.ConnectivityCompatChanges.RESTRICT_LOCAL_NETWORK;

import static com.android.compatibility.common.util.SystemUtil.runShellCommand;
import static com.android.testutils.TestPermissionUtil.runAsShell;

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

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.Socket;
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

    @Test
    @RequiresFlagsEnabled("com.android.net.flags.restrict_local_network")
    public void testConnectToLocalNetwork_usingLocalNetworkProtections() throws Exception {
        assumeTrue(CompatChanges.isChangeEnabled(365139289L)); // ConnectivityCompatChanges.RESTRICT_LOCAL_NETWORK
        // check for flags & app compat
        runAsShell(Manifest.permission.GRANT_RUNTIME_PERMISSIONS, () -> {
            mContext.getPackageManager().grantRuntimePermission("android.networkpermission.internetpermission.cts",
                    Manifest.permission.NEARBY_WIFI_DEVICES, Process.myUserHandle());
        });
        Log.d("PermissionMonitor", "call apk is " + Process.myUserHandle().toString());

        try {
            Socket socket = new Socket("example.com", 80);
            // thsi should be successful, check for log statement
            fail("Ceate inet socket did not throw SecurityException as expected");
        } catch (SecurityException e) {
            // expected
        }
    }
}
