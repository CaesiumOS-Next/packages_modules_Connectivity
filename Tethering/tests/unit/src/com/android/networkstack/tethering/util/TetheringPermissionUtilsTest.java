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

package com.android.networkstack.tethering.util;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.os.UserHandle;
import android.telephony.TelephonyManager;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class TetheringPermissionUtilsTest {
    @Mock Context mContext;
    @Mock Context mUserContext;
    @Mock DevicePolicyManager mDevicePolicyManager;
    @Mock TelephonyManager mTelephonyManager;

    TetheringPermissionUtils mTetheringPermissionUtils;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mContext.createContextAsUser(UserHandle.CURRENT, 0)).thenReturn(mUserContext);
        when(mUserContext.getSystemService(DevicePolicyManager.class))
                .thenReturn(mDevicePolicyManager);
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        mTetheringPermissionUtils = new TetheringPermissionUtils(mContext);
    }

    @Test
    public void testIsDeviceOwner() {
        String testPackage = "test.package";

        when(mDevicePolicyManager.isDeviceOwnerApp(testPackage)).thenReturn(false);
        assertThat(mTetheringPermissionUtils.isDeviceOwner(testPackage)).isFalse();

        when(mDevicePolicyManager.isDeviceOwnerApp(testPackage)).thenReturn(true);
        assertThat(mTetheringPermissionUtils.isDeviceOwner(testPackage)).isTrue();
    }

    @Test
    public void testHasCarrierPrivilege() {
        String testPackage = "test.package";

        when(mTelephonyManager.checkCarrierPrivilegesForPackageAnyPhone(testPackage))
                .thenReturn(TelephonyManager.CARRIER_PRIVILEGE_STATUS_NO_ACCESS);
        assertThat(mTetheringPermissionUtils.hasCarrierPrivileges(testPackage)).isFalse();

        when(mTelephonyManager.checkCarrierPrivilegesForPackageAnyPhone(testPackage))
                .thenReturn(TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS);
        assertThat(mTetheringPermissionUtils.hasCarrierPrivileges(testPackage)).isTrue();
    }
}
