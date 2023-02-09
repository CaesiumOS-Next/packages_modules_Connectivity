/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.tethering.test;

import static android.Manifest.permission.TETHER_PRIVILEGED;
import static com.android.testutils.TestPermissionUtil.runAsShell;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.TetheringManager;
import android.os.Build;
import android.os.SystemClock;
import android.os.UserHandle;
import android.test.AndroidTestCase;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests the tethering SystemApi methods in ConnectivityManager.
 *
 * Note that If tether provisioining is not required on the device by the
 * carrier, these tests will simply enable and disable tethering. If provsioning
 * is required, it is expected to succeed and allow tethering. OEMs are expected
 * to implement the provisioning calls as part of these functions.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class ProvisioningTest {
    private static final String TAG = "ProvisioningTest";

    @Rule
    public final DevSdkIgnoreRule mIgnoreRule = new DevSdkIgnoreRule();

    private Context mContext;
    private ConnectivityManager mConnectivityManager;
    private BluetoothAdapter mBluetoothAdapter;
    private TetheringManager mTetheringManager;
    protected AtomicReference<BluetoothProfile> mBluetoothPan;
    private static final int SHORT_TIMEOUT = 1000;
    private static final int BLUETOOTH_TIMEOUT = 10000;
    private static final int PROVISION_TIMEOUT = 60000;

    private OnStartTetheringCallback mCallback;
    boolean mLastTetherSucceeded;
    private UserHandle mcurrentUserHandle;

    private static final class OnStartTetheringCallback extends
            ConnectivityManager.OnStartTetheringCallback {
        final WeakReference<ProvisioningTest> mTest;

        OnStartTetheringCallback(ProvisioningTest test) {
            mTest = new WeakReference<ProvisioningTest>(test);
        }

        @Override
        public void onTetheringStarted() {
            Log.i(TAG, "Got tether started response");
            ProvisioningTest test = mTest.get();
            if (test != null) {
                test.mLastTetherSucceeded = true;
            }
        }

        @Override
        public void onTetheringFailed() {
            Log.i(TAG, "Got tether failed response");
            ProvisioningTest test = mTest.get();
            if (test != null) {
                test.mLastTetherSucceeded = false;
            }
        }
    };

    @Before
    public void setUp() throws Exception {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity();

        mContext = InstrumentationRegistry.getContext();
        mConnectivityManager = (ConnectivityManager)
                mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        assertNotNull(mConnectivityManager);
        mTetheringManager = (TetheringManager)
                mContext.getSystemService(Context.TETHERING_SERVICE);
        assertNotNull(mTetheringManager);

        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mLastTetherSucceeded = false;
        mCallback = new OnStartTetheringCallback(this);
        mcurrentUserHandle = android.os.Process.myUserHandle();
    }

    @After
    public void tearDown() {
        mConnectivityManager.stopTethering(ConnectivityManager.TETHERING_BLUETOOTH);
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .dropShellPermissionIdentity();
    }

    private boolean isTetheringSupported() {
        // TODO: runAsShell(TETHER_PRIVILEGED, ..) once adoptShellPermissionIdentity is removed.
        return mTetheringManager.isTetheringSupported();
    }

    private void runBluetoothTest(boolean showProvisioningUi) throws Exception {
        // Enable bluetooth before tethering.
        boolean adapterWasDisabled = false;
        if (!mBluetoothAdapter.isEnabled()) {
          Log.v(TAG, "Enabling bluetooth for tethering");
          adapterWasDisabled = true;
          mBluetoothAdapter.enable();
          waitForBluetoothState(BluetoothAdapter.STATE_ON);
        }

        mConnectivityManager.startTethering(
                ConnectivityManager.TETHERING_BLUETOOTH, showProvisioningUi, mCallback);
        assertTrue(waitForSuccessCallback());
        assertTrue(waitForBluetoothTetherState(true));

        // Restore original bluetooth state.
        if (adapterWasDisabled) {
            mBluetoothAdapter.disable();
            waitForBluetoothState(BluetoothAdapter.STATE_OFF);
        }
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
    public void testRunSilentBluetoothTetherProvisioningAndEnable() throws Exception {
        assumeTrue(isTetheringSupported());
        assumeTrue(isBluetoothTetheringSupported());
        assumeFalse(mcurrentUserHandle != null && !mcurrentUserHandle.isSystem());

        runBluetoothTest(false);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
    public void testRunUiBluetoothTetherProvisioningAndEnable() throws Exception {
        assumeTrue(isTetheringSupported());
        assumeTrue(isBluetoothTetheringSupported());
        assumeFalse(mcurrentUserHandle != null && !mcurrentUserHandle.isSystem());

        runBluetoothTest(true);
    }

    @Test
    @IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
    public void testSilentInvalidTetherTypeTest() {
        assumeTrue(isTetheringSupported());

        mLastTetherSucceeded = true;
        final int BOGUS_TYPE = -7;
        mConnectivityManager.startTethering(BOGUS_TYPE, false, mCallback);
        assertTrue(waitForFailureCallback());
    }

    private boolean waitForBluetoothState(int expectedState) {
        long startTime = SystemClock.uptimeMillis();
        while (true) {
            int state = mBluetoothAdapter.getState();
            if (state == expectedState) {
                return true;
            }
            if ((SystemClock.uptimeMillis() - startTime) > BLUETOOTH_TIMEOUT) {
                Log.v(TAG, String.format("waitForBluetoothState timeout: expected=%d, actual=%d",
                        expectedState, state));
                return false;
            }
            SystemClock.sleep(SHORT_TIMEOUT);
        }
    }

    private boolean waitForBluetoothTetherState(boolean enabled) throws Exception {
        long startTime = SystemClock.uptimeMillis();
        while (true) {
            boolean state = isBluetoothTetheringActive();
            if (state == enabled) {
                return true;
            }
            if ((SystemClock.uptimeMillis() - startTime) > BLUETOOTH_TIMEOUT) {
                Log.v(TAG, String.format(
                        "waitForBluetoothTetherState timeout: expected=%b, actual=%d",
                        enabled, state));
                return false;
            }
            SystemClock.sleep(SHORT_TIMEOUT);
        }
    }

    private boolean waitForSuccessCallback() {
        return waitForCallback(true);
    }

    private boolean waitForFailureCallback() {
        return waitForCallback(false);
    }

    private boolean waitForCallback(boolean success) {
        long startTime = SystemClock.uptimeMillis();
        while (true) {
            if (mLastTetherSucceeded == success) {
                // Always reset to false.
                mLastTetherSucceeded = false;
                return true;
            }
            if ((SystemClock.uptimeMillis() - startTime) > PROVISION_TIMEOUT) {
                String successStr = success ? "Success" : "Failure";
                Log.v(TAG, "waitFor" + successStr + "Callback timeout.");
                return false;
            }
            SystemClock.sleep(SHORT_TIMEOUT);
        }
    }

    private BluetoothProfile.ServiceListener mProfileServiceListener =
            new BluetoothProfile.ServiceListener() {
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    mBluetoothPan.set(proxy);
                }
                public void onServiceDisconnected(int profile) {
                    mBluetoothPan.set(null);
                }
            };

    // TODO: Find a way to get rid of this hacky reflection code.
    private boolean isBluetoothTetheringSupported() throws Exception {
        PackageManager packageManager = mContext.getPackageManager();
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) {
            return false;
        }

        mBluetoothPan = new AtomicReference<BluetoothProfile>();
        try {
            mBluetoothAdapter.getProfileProxy(mContext, mProfileServiceListener,
                    (Integer) BluetoothProfile.class.getField("PAN").get(null));
        } catch (NoSuchFieldException e) {
            // Device doesn't support pan at all. Pass.
            Log.i(TAG, "Device does not support bluetooth PAN");
            return false;
        }

        final boolean supported = mTetheringManager.getTetherableBluetoothRegexs().length > 0;
        if (!supported) {
            Log.i(TAG, "Device does not have tetherable Bluetooth interfaces");
        }
        return supported;
    }

    private boolean isBluetoothTetheringActive() throws Exception {
        mBluetoothPan = new AtomicReference<BluetoothProfile>();
        try {
            mBluetoothAdapter.getProfileProxy(mContext, mProfileServiceListener,
                    (Integer) BluetoothProfile.class.getField("PAN").get(null));
        } catch (NoSuchFieldException e) {
            // Device doesn't support pan at all. Pass.
            return false;
        }

        long start = System.currentTimeMillis();
        final int SUPER_SHORT_TIMEOUT = 100;
        while (mBluetoothPan.get() == null || !mBluetoothAdapter.isEnabled()) {
            if (System.currentTimeMillis() - start > BLUETOOTH_TIMEOUT) {
                break;
            }
            Thread.sleep(SUPER_SHORT_TIMEOUT);
        }

        BluetoothProfile pan = mBluetoothPan.get();
        if (pan != null) {
            try {
                Method method = pan.getClass().getMethod("isTetheringOn");
                return (Boolean) method.invoke(pan);
            } catch (NoSuchMethodException e) {
                // Device doesn't implement this method.
                return false;
            }
        }

        return false;
    }
}
