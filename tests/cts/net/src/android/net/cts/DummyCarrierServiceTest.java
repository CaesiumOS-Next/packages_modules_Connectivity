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

import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;

import static com.android.compatibility.common.util.SystemUtil.runWithShellPermissionIdentity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.CarrierPrivilegeUtils;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DummyCarrierServiceTest {
    private static final String TAG = "DummyCarrierServiceTest";
    private final Context mContext;

    public DummyCarrierServiceTest() {
        mContext = InstrumentationRegistry.getContext();
    }

    private int verifyAndGetValidDataSubId() {
        final int dataSubId = SubscriptionManager.getDefaultDataSubscriptionId();
        assertNotEquals(
                "There must be an active data subscription to complete CTS",
                INVALID_SUBSCRIPTION_ID,
                dataSubId);
        return dataSubId;
    }

    @Test
    public void testCarrierService() throws Exception {
        final int dataSubId = verifyAndGetValidDataSubId();
        final TelephonyManager telMgr =
                mContext.getSystemService(TelephonyManager.class)
                        .createForSubscriptionId(dataSubId);
        final String pkgName = mContext.getOpPackageName();

        // Verify that CTS package IS NOT carrier service before starting
        runWithShellPermissionIdentity(
                () -> {
                    assertNotEquals(
                            pkgName,
                            telMgr.getCarrierServicePackageNameForLogicalSlot(
                                    telMgr.getSlotIndex()));
                },
                android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE);

        CarrierPrivilegeUtils.asCarrierService(
                mContext,
                dataSubId,
                () -> {
                    // Verify that CTS package IS carrier service when running in a carrier service
                    // block
                    runWithShellPermissionIdentity(
                            () -> {
                                assertEquals(
                                        pkgName,
                                        telMgr.getCarrierServicePackageNameForLogicalSlot(
                                                telMgr.getSlotIndex()));
                            },
                            android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE);

                    // Also verify that it is carrier privileged
                    assertTrue(telMgr.hasCarrierPrivileges());
                });

        // Verify that CTS package IS NOT carrier service after relinquishing
        runWithShellPermissionIdentity(
                () -> {
                    assertNotEquals(
                            pkgName,
                            telMgr.getCarrierServicePackageNameForLogicalSlot(
                                    telMgr.getSlotIndex()));
                },
                android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE);
    }
}
