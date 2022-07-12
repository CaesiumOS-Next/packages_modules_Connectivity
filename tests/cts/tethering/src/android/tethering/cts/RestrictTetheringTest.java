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
package android.tethering.test;

import static android.Manifest.permission.TETHER_PRIVILEGED;
import static android.net.TetheringManager.TETHERING_WIFI;
import static android.net.TetheringManager.TETHER_ERROR_UNSUPPORTED;
import static android.os.UserManager.DISALLOW_CONFIG_TETHERING;

import static com.android.bedstead.remotedpc.RemoteDpc.DPC_COMPONENT_NAME;
import static com.android.testutils.TestPermissionUtil.runAsShell;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import android.content.Context;
import android.net.TetheringManager;
import android.net.TetheringManager.TetheringRequest;
import android.net.cts.util.CtsTetheringUtils;
import android.net.cts.util.CtsTetheringUtils.StartTetheringCallback;
import android.net.cts.util.CtsTetheringUtils.TestTetheringEventCallback;

import com.android.bedstead.harrier.BedsteadJUnit4;
import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.harrier.annotations.enterprise.PolicyAppliesTest;
import com.android.bedstead.harrier.policies.Delegation;
import com.android.bedstead.nene.TestApis;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(BedsteadJUnit4.class)
public class RestrictTetheringTest {
    public static final int RETRY_TIMES = 100;
    public static final long RETRY_INTERVAL_MS = 50L;

    @ClassRule @Rule
    public static final DeviceState sDeviceState = new DeviceState();

    private Context mContext;
    private CtsTetheringUtils mCtsTetheringUtils;
    private TestTetheringEventCallback mCallback;
    private TetheringManager mTetheringManager;

    @Before
    public void setUp() throws Exception {
        mContext = TestApis.context().instrumentedContext();
        mTetheringManager = mContext.getSystemService(TetheringManager.class);
        mCtsTetheringUtils = new CtsTetheringUtils(mContext);
        mCallback = mCtsTetheringUtils.registerTetheringEventCallback();
    }

    @After
    public void tearDown() throws Exception {
        mCtsTetheringUtils.unregisterTetheringEventCallback(mCallback);
    }

    @Test
    @PolicyAppliesTest(policy = Delegation.class)
    public void testRestrictTethering() throws Exception {
        // if tethering is restricted before this test, wifi tethering would not be supported.
        mCallback.assumeWifiTetheringSupported(mContext);
        mCallback.expectNoTetheringActive();
        try {
            // start wifi tethering.
            mCtsTetheringUtils.startWifiTethering(mCallback);

            // wifi tethering should be stopped and tethering is not supported after restricted.
            sDeviceState.dpc().devicePolicyManager().addUserRestriction(
                    DPC_COMPONENT_NAME, DISALLOW_CONFIG_TETHERING);
            mCtsTetheringUtils.expectSoftApDisabled();
            assertFalse(isTetheringSupported());

            // bug: onTetheredInterfacesChanged would not fired if tethering is restricted.
            // TODO: fire the callback and test it here.

            // tethering is not supported and could not be start after restricted.
            final StartTetheringCallback callback = new StartTetheringCallback();
            final TetheringRequest request = new TetheringRequest.Builder(
                    TETHERING_WIFI).setShouldShowEntitlementUi(false).build();
            mTetheringManager.startTethering(request, r -> r.run(), callback);
            callback.onTetheringFailed(TETHER_ERROR_UNSUPPORTED);

            // clear the restriction, then tethering could be start successfully.
            sDeviceState.dpc().devicePolicyManager().clearUserRestriction(
                    DPC_COMPONENT_NAME, DISALLOW_CONFIG_TETHERING);
            waitingTetheringSupported();

            startWifiTethering();

            mCtsTetheringUtils.stopWifiTethering(mCallback);
        } finally {
            sDeviceState.dpc().devicePolicyManager().clearUserRestriction(
                    DPC_COMPONENT_NAME, DISALLOW_CONFIG_TETHERING);
            mCtsTetheringUtils.stopAllTethering();
        }
    }

    // TODO: After no onTetheredInterfacesChanged callback bug fix, we could just reuse mCallback
    // to start wifi tethering instead of registering new callback.
    private void startWifiTethering() throws Exception {
        final TestTetheringEventCallback callback =
                mCtsTetheringUtils.registerTetheringEventCallback();
        try {
            mCtsTetheringUtils.startWifiTethering(callback);
        } finally {
            mCtsTetheringUtils.unregisterTetheringEventCallback(callback);
        }
    }

    private boolean isTetheringSupported() {
        return runAsShell(TETHER_PRIVILEGED, () -> mTetheringManager.isTetheringSupported());
    }

    // TODO: use #onSupportedTetheringTypes callback once it expose as API.
    private void waitingTetheringSupported() throws Exception {
        for (int i = 0; i < RETRY_TIMES; i++) {
            if (isTetheringSupported()) return;

            Thread.sleep(RETRY_INTERVAL_MS);
        }

        fail("Tethering still not be supported after 5 seconds");
    }
}
