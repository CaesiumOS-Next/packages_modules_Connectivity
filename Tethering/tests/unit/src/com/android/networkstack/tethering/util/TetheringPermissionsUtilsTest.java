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
package com.android.networkstack.tethering.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
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
public class TetheringPermissionsUtilsTest {
    private static final String TEST_PACKAGE_NAME = "com.google.somePackage";
    private static final String INVALID_PACKAGE  = "BAD_PACKAGE";
    private static final int MANAGED_PROFILE_UID = 1100000;
    private static final int OTHER_USER_UID = 1200000;

    @Mock
    Context mContext;
    @Mock
    PackageManager mPackageManager;
    @Mock
    DevicePolicyManager mDevicePolicyManager;
    @Mock
    TelephonyManager mTelephonyManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mContext.getPackageManager())
                .thenReturn(mPackageManager);
        when(mContext.getSystemService(DevicePolicyManager.class))
                .thenReturn(mDevicePolicyManager);
        when(mContext.getSystemService(TelephonyManager.class))
                .thenReturn(mTelephonyManager);
    }

    @Test
    public void testIsDeviceOwner() throws Exception {
        TetheringPermissionsUtils tetherPermissionUtils = new TetheringPermissionsUtils(mContext);
        when(mDevicePolicyManager.getDeviceOwnerComponentOnAnyUser())
                .thenReturn(new ComponentName(TEST_PACKAGE_NAME, new String()));
        when(mDevicePolicyManager.getDeviceOwnerUser())
                .thenReturn(UserHandle.getUserHandleForUid(MANAGED_PROFILE_UID));
        assertTrue(tetherPermissionUtils.isDeviceOwner(
                MANAGED_PROFILE_UID, TEST_PACKAGE_NAME));

        // userId does not match
        when(mDevicePolicyManager.getDeviceOwnerComponentOnAnyUser())
                .thenReturn(new ComponentName(TEST_PACKAGE_NAME, new String()));
        when(mDevicePolicyManager.getDeviceOwnerUser())
                .thenReturn(UserHandle.getUserHandleForUid(OTHER_USER_UID));
        assertFalse(tetherPermissionUtils.isDeviceOwner(
                MANAGED_PROFILE_UID, TEST_PACKAGE_NAME));

        // Package Name does not match
        when(mDevicePolicyManager.getDeviceOwnerComponentOnAnyUser())
                .thenReturn(new ComponentName(INVALID_PACKAGE, new String()));
        when(mDevicePolicyManager.getDeviceOwnerUser())
                .thenReturn(UserHandle.getUserHandleForUid(MANAGED_PROFILE_UID));
        assertFalse(tetherPermissionUtils.isDeviceOwner(
                MANAGED_PROFILE_UID, TEST_PACKAGE_NAME));

        // No device owner.
        when(mDevicePolicyManager.getDeviceOwnerComponentOnAnyUser())
                .thenReturn(null);
        assertFalse(tetherPermissionUtils.isDeviceOwner(
                MANAGED_PROFILE_UID, TEST_PACKAGE_NAME));

        // DevicePolicyManager does not exist.
        when(mContext.getSystemService(DevicePolicyManager.class))
                .thenReturn(null);
        assertFalse(tetherPermissionUtils.isDeviceOwner(
                MANAGED_PROFILE_UID, TEST_PACKAGE_NAME));
    }

    @Test
    public void testIsCarrierApp() throws Exception {
        TetheringPermissionsUtils tetherPermissionUtils = new TetheringPermissionsUtils(mContext);
        assertFalse(tetherPermissionUtils.isCarrierApp(TEST_PACKAGE_NAME));

        when(mTelephonyManager.checkCarrierPrivilegesForPackageAnyPhone(TEST_PACKAGE_NAME))
                .thenReturn(TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS);
        assertTrue(tetherPermissionUtils.isCarrierApp(TEST_PACKAGE_NAME));

        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(null);
        assertFalse(tetherPermissionUtils.isCarrierApp(TEST_PACKAGE_NAME));
    }
}
