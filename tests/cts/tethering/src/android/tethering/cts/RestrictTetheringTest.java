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
        // if tethering is restricted before this test, wifi tethering would not be supported.
        mCallback.assumeWifiTetheringSupported(mContext);
        mCallback.expectNoTetheringActive();
    }

    @After
    public void tearDown() throws Exception {
        mCtsTetheringUtils.unregisterTetheringEventCallback(mCallback);
    }

    @Test
    @PolicyAppliesTest(policy = Delegation.class)
    public void testRestrictAfterTetheringStarted() throws Exception {
        try {
            // start wifi tethering.
            mCtsTetheringUtils.startWifiTethering(mCallback);

            // wifi tethering should be stopped and tethering is not supported after restricted.
            sDeviceState.dpc().devicePolicyManager().addUserRestriction(
                    DPC_COMPONENT_NAME, DISALLOW_CONFIG_TETHERING);
            mCtsTetheringUtils.expectSoftApDisabled();
            expectStateEventually(false /* supported */);

            // bug: onTetheredInterfacesChanged would not fired if tethering is restricted.
            // TODO: fire the callback and test it here.

            // clear the restriction, then tethering still can be on/off normally.
            clearTetheringRestriction();
            testTetheringOnOff();
        } finally {
            clearTetheringRestriction();
            mCtsTetheringUtils.stopAllTethering();
        }
    }

    @Test
    @PolicyAppliesTest(policy = Delegation.class)
    public void testRestrictBeforeStartTethering() throws Exception {
        try {
            sDeviceState.dpc().devicePolicyManager().addUserRestriction(
                    DPC_COMPONENT_NAME, DISALLOW_CONFIG_TETHERING);
            expectStateEventually(false /* supported */);

            final StartTetheringCallback callback = new StartTetheringCallback();
            final TetheringRequest request = new TetheringRequest.Builder(TETHERING_WIFI).build();
            mTetheringManager.startTethering(request, r -> r.run(), callback);
            callback.onTetheringFailed(TETHER_ERROR_UNSUPPORTED);

            // clear the restriction, then tethering still can be on/off normally.
            clearTetheringRestriction();
            testTetheringOnOff();
        } finally {
            clearTetheringRestriction();
            mCtsTetheringUtils.stopAllTethering();
        }
    }

    // TODO: After no onTetheredInterfacesChanged callback bug fix, we could just reuse mCallback.
    private void testTetheringOnOff() throws Exception {
        final TestTetheringEventCallback callback =
                mCtsTetheringUtils.registerTetheringEventCallback();
        try {
            mCtsTetheringUtils.startWifiTethering(callback);
            mCtsTetheringUtils.stopWifiTethering(callback);
        } finally {
            mCtsTetheringUtils.unregisterTetheringEventCallback(callback);
        }
    }

    private void clearTetheringRestriction() throws Exception {
        sDeviceState.dpc().devicePolicyManager().clearUserRestriction(
                DPC_COMPONENT_NAME, DISALLOW_CONFIG_TETHERING);

        expectStateEventually(true /* supported */);
    }

    // TODO: use #onSupportedTetheringTypes callback once it expose as API.
    private void expectStateEventually(final boolean supported) throws Exception {
        for (int i = 0; i < RETRY_TIMES; i++) {
            if (isTetheringSupported() == supported) return;

            Thread.sleep(RETRY_INTERVAL_MS);
        }
        fail("Tethering supported(" + supported + ") does not change after 5 seconds");
    }

    private boolean isTetheringSupported() {
        return runAsShell(TETHER_PRIVILEGED, () -> mTetheringManager.isTetheringSupported());
    }
}
