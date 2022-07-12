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

import static android.os.UserManager.DISALLOW_CONFIG_TETHERING;

import static com.android.bedstead.remotedpc.RemoteDpc.DPC_COMPONENT_NAME;

import android.content.Context;
import android.os.UserManager;

import com.android.bedstead.harrier.BedsteadJUnit4;
import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.harrier.annotations.EnsureHasPermission;
import com.android.bedstead.harrier.annotations.enterprise.PositivePolicyTest;
import com.android.bedstead.harrier.policies.Delegation;
import com.android.bedstead.nene.TestApis;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(BedsteadJUnit4.class)
public final class RestrictTetheringTest {
    @ClassRule @Rule
    public static final DeviceState sDeviceState = new DeviceState();

    private static final Context sContext = TestApis.context().instrumentedContext();
    private static final UserManager sUserManager = sContext.getSystemService(UserManager.class);

    @Test
    @PositivePolicyTest(policy = Delegation.class)
    public void testRestrictTethering() {
        // Test whether we could update restriction without permission problem.
        // Open hotspot manually before the test.
        try {
            sDeviceState.dpc().devicePolicyManager().addUserRestriction(
                    DPC_COMPONENT_NAME, DISALLOW_CONFIG_TETHERING);
        } finally {
            sDeviceState.dpc().devicePolicyManager().clearUserRestriction(
                    DPC_COMPONENT_NAME, DISALLOW_CONFIG_TETHERING);
        }
        // Check whether hotspot is disabled after the test manually.
    }
}
