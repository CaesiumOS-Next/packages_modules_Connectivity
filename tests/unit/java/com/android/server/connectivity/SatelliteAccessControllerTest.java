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

package com.android.server.connectivity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.annotation.NonNull;
import android.app.role.OnRoleHoldersChangedListener;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.HandlerThread;
import android.os.UserHandle;

import com.android.server.ConnectivityService;
import com.android.server.connectivity.SatelliteAccessController.Dependencies;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;

import org.junit.Before;
import org.junit.Test;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

@IgnoreUpTo(Build.VERSION_CODES.S_V2)
public class SatelliteAccessControllerTest {
    @NonNull
    private Context mContext;
    @NonNull private PackageManager mPackageManager;
    @NonNull private SatelliteAccessController mSatelliteAccessController;
    private static final String DEFAULT_MESSAGING_APP1 = "default_messaging_app_1";
    private static final String DEFAULT_MESSAGING_APP2 = "default_messaging_app_2";
    private static final int DEFAULT_MESSAGING_APP1_UID = 1234;
    private static final int DEFAULT_MESSAGING_APP2_UID = 5678;
    private OnRoleHoldersChangedListener mRoleHolderChangedListener;
    private SatelliteAccessController.RoleManagerAdapter mRoleManager;
    ConnectivityService.OnUpdateSatelliteNetworkPreferenceUids mCallback;
    private final Executor mExecutor = Runnable::run;

    @Before
    public void setup() throws PackageManager.NameNotFoundException {
        mContext = mock(Context.class);
        mPackageManager = mock(PackageManager.class);
        mRoleManager = mock(SatelliteAccessController.RoleManagerAdapter.class);
        mCallback = mock(ConnectivityService.OnUpdateSatelliteNetworkPreferenceUids.class);
        final Dependencies deps = mock(SatelliteAccessController.Dependencies.class);
        doReturn(new HandlerThread("")).when(deps).makeHandlerThread();
        doReturn(mPackageManager).when(mContext).getPackageManager();
        doReturn(PackageManager.PERMISSION_GRANTED)
                .when(mPackageManager)
                .checkPermission(
                        "android.permission.SATELLITE_COMMUNICATION", DEFAULT_MESSAGING_APP1);
        doReturn(PackageManager.PERMISSION_GRANTED)
                .when(mPackageManager)
                .checkPermission(
                        "android.permission.SATELLITE_COMMUNICATION", DEFAULT_MESSAGING_APP2);

        //Initialise default message application package1
        final ApplicationInfo applicationInfo1 = new ApplicationInfo();
        applicationInfo1.uid = DEFAULT_MESSAGING_APP1_UID;
        doReturn(applicationInfo1)
                .when(mPackageManager).getApplicationInfo(eq(DEFAULT_MESSAGING_APP1), anyInt());

        //Initialise default message application package2
        final ApplicationInfo applicationInfo2 = new ApplicationInfo();
        applicationInfo2.uid = DEFAULT_MESSAGING_APP2_UID;
        doReturn(applicationInfo2)
                .when(mPackageManager).getApplicationInfo(eq(DEFAULT_MESSAGING_APP2), anyInt());

        doAnswer((Answer<Void>) invocation -> {
            mRoleHolderChangedListener = (OnRoleHoldersChangedListener)
                    invocation.getArguments()[1];
            return null;
        }).when(mRoleManager).addOnRoleHoldersChangedListenerAsUser(any(Executor.class),
                any(OnRoleHoldersChangedListener.class), any(UserHandle.class));

        mSatelliteAccessController =
                new SatelliteAccessController(mContext, deps, mRoleManager, mCallback);
    }

    @Test
    public void test_OnRoleSmsChanged_SatellitePreferredUid_Changed() {
        doReturn(List.of()).when(mRoleManager).getRoleHolders(eq(RoleManager.ROLE_SMS));
        verify(mCallback, never()).updateSatelliteNetworkPreferenceUids();

        //check DEFAULT_MESSAGING_APP1 is available as satellite network preferred uid
        List<String> dmas = new ArrayList<>();
        dmas.add(DEFAULT_MESSAGING_APP1);
        doReturn(dmas).when(mRoleManager).getRoleHolders(eq(RoleManager.ROLE_SMS));
        mSatelliteAccessController.onRoleSmsChanged();
        assertTrue(mSatelliteAccessController
                .getSatelliteNetworkPreferredUids().contains(DEFAULT_MESSAGING_APP1_UID));
        assertFalse(mSatelliteAccessController
                .getSatelliteNetworkPreferredUids().contains(DEFAULT_MESSAGING_APP2_UID));
        // Check Callback triggered
        verify(mCallback).updateSatelliteNetworkPreferenceUids();

        // check DEFAULT_MESSAGING_APP1 and DEFAULT_MESSAGING_APP2 is available
        // as satellite network preferred uid
        dmas = new ArrayList<>();
        dmas.add(DEFAULT_MESSAGING_APP1);
        dmas.add(DEFAULT_MESSAGING_APP2);
        doReturn(dmas).when(mRoleManager).getRoleHolders(eq(RoleManager.ROLE_SMS));
        mSatelliteAccessController.onRoleSmsChanged();
        assertTrue(mSatelliteAccessController
                .getSatelliteNetworkPreferredUids().contains(DEFAULT_MESSAGING_APP1_UID));
        assertTrue(mSatelliteAccessController
                .getSatelliteNetworkPreferredUids().contains(DEFAULT_MESSAGING_APP2_UID));
        // Check Callback triggered
        verify(mCallback, times(2)).updateSatelliteNetworkPreferenceUids();

        // check no uid is available as satellite network preferred uid
        dmas = new ArrayList<>();
        doReturn(dmas).when(mRoleManager).getRoleHolders(eq(RoleManager.ROLE_SMS));
        mSatelliteAccessController.onRoleSmsChanged();
        assertFalse(mSatelliteAccessController
                .getSatelliteNetworkPreferredUids().contains(DEFAULT_MESSAGING_APP1_UID));
        assertFalse(mSatelliteAccessController
                .getSatelliteNetworkPreferredUids().contains(DEFAULT_MESSAGING_APP2_UID));
        verify(mCallback, times(3)).updateSatelliteNetworkPreferenceUids();

        // No Change received at OnRoleSmsChanged, check callback not triggered
        dmas = new ArrayList<>();
        doReturn(dmas).when(mRoleManager).getRoleHolders(eq(RoleManager.ROLE_SMS));
        mSatelliteAccessController.onRoleSmsChanged();
        verify(mCallback, times(3)).updateSatelliteNetworkPreferenceUids();
    }

    @Test
    public void test_OnRoleSmsChanged_NoSatelliteCommunicationPermission() {
        doReturn(List.of()).when(mRoleManager).getRoleHolders(eq(RoleManager.ROLE_SMS));
        verify(mCallback, never()).updateSatelliteNetworkPreferenceUids();

        doReturn(PackageManager.PERMISSION_DENIED)
                .when(mPackageManager)
                .checkPermission(
                        "android.permission.SATELLITE_COMMUNICATION", DEFAULT_MESSAGING_APP1);

        // check DEFAULT_MESSAGING_APP1 is not available as satellite network preferred uid
        // since satellite communication permission not available.
        List<String> dmas = new ArrayList<>();
        dmas.add(DEFAULT_MESSAGING_APP1);
        doReturn(dmas).when(mRoleManager).getRoleHolders(eq(RoleManager.ROLE_SMS));

        mSatelliteAccessController.onRoleSmsChanged();
        assertFalse(mSatelliteAccessController
                .getSatelliteNetworkPreferredUids().contains(DEFAULT_MESSAGING_APP1_UID));
        verify(mCallback, never()).updateSatelliteNetworkPreferenceUids();
    }

}
