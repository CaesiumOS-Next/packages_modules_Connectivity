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

package com.android.server;

import static android.os.Build.VERSION_CODES.R;
import static android.os.Build.VERSION_CODES.S_V2;

import static com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;

import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import android.annotation.UserIdInt;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.net.ConnectivityManager;
import android.net.INetd;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;

import androidx.test.filters.SmallTest;

import com.android.server.connectivity.Vpn;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.util.List;
import java.util.Map;

@RunWith(DevSdkIgnoreRunner.class)
@IgnoreUpTo(R) // VpnManagerService is not available before R
@SmallTest
public class VpnManagerServiceTest {
    @Rule
    public final DevSdkIgnoreRule mIgnoreRule = new DevSdkIgnoreRule();

    private static final int PRIMARY_USER = 0;
    private static final String TEST_VPN_PKG = "com.testvpn.vpn";
    private static final List<String> PKGS =
            List.of("com.example", "org.example", "net.example", "web.vpn");
    private static final List<Integer> PKG_UIDS = List.of(11111, 11112, 11113, 11114);

    // Mock packages
    static final Map<String, Integer> sPackages = new ArrayMap<>();
    static {
        for (int i = 0; i < PKGS.size(); i++) {
            sPackages.put(PKGS.get(i), PKG_UIDS.get(i));
        }
        sPackages.put(TEST_VPN_PKG, Process.myUid());
    }

    private static final UserInfo PRIMARY_USER_INFO = new UserInfo(PRIMARY_USER, "",
            UserInfo.FLAG_PRIMARY);

    @Spy Context mContext;
    private HandlerThread mHandlerThread;
    @Mock private Handler mHandler;
    @Mock private Vpn mVpn;
    @Mock private INetworkManagementService mNms;
    @Mock private ConnectivityManager mCm;
    @Mock private UserManager mUserManager;
    @Mock private INetd mNetd;
    @Mock private PackageManager mPackageManager;
    private VpnManagerServiceDependencies mDeps;
    private VpnManagerService mService;

    class VpnManagerServiceDependencies extends VpnManagerService.Dependencies {

        @Override
        public HandlerThread makeHandlerThread() {
            return mHandlerThread;
        }

        @Override
        public INetworkManagementService getINetworkManagementService() {
            return mNms;
        }

        @Override
        public INetd getNetd() {
            return mNetd;
        }

        @Override
        public Vpn createVpn(Looper looper, Context context, INetworkManagementService nms,
                INetd netd, @UserIdInt int userId) {
            return mVpn;
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mHandlerThread = new HandlerThread("TestVpnManagerService");
        mDeps = new VpnManagerServiceDependencies();
        doReturn(mContext).when(mContext).createContextAsUser(any(), anyInt());
        doReturn(mPackageManager).when(mContext).getPackageManager();
        setMockedPackages(sPackages);

        mockService(ConnectivityManager.class, Context.CONNECTIVITY_SERVICE, mCm);
        mockService(UserManager.class, Context.USER_SERVICE, mUserManager);

        doReturn(new Intent()).when(mContext).registerReceiver(
                any() /* receiver */,
                any() /* intentFilter */,
                any() /* broadcastPermission */,
                eq(mHandler) /* scheduler */);
        doReturn(PRIMARY_USER_INFO).when(mUserManager).getUserInfo(eq(PRIMARY_USER));
        mService = new VpnManagerService(mContext, mDeps);
    }

    private <T> void mockService(Class<T> clazz, String name, T service) {
        doReturn(service).when(mContext).getSystemService(name);
        doReturn(name).when(mContext).getSystemServiceName(clazz);
    }

    // Populate a fake packageName-to-UID mapping.
    private void setMockedPackages(final Map<String, Integer> packages) {
        try {
            doAnswer(invocation -> {
                final String appName = (String) invocation.getArguments()[0];
                final int userId = (int) invocation.getArguments()[1];

                Integer appId = packages.get(appName);
                if (appId == null) {
                    throw new PackageManager.NameNotFoundException(appName);
                }

                return UserHandle.getUid(userId, appId);
            }).when(mPackageManager).getPackageUidAsUser(anyString(), anyInt());
        } catch (Exception e) {
        }
    }

    @Test @IgnoreUpTo(S_V2)
    public void testUpdateAppExclusionList() {
        // Add user to create vpn in mVpn
        mService.onUserStarted(PRIMARY_USER);
        assertNotNull(mService.mVpns.get(PRIMARY_USER));

        // Start vpn
        mService.startVpnProfile(TEST_VPN_PKG);
        verify(mVpn).startVpnProfile(eq(TEST_VPN_PKG));

        // Remove package due to package replaced.
        mService.onPackageRemoved(PKGS.get(0), PKG_UIDS.get(0), true /* isReplacing */);
        verify(mVpn, never()).refreshPlatformVpnAppExclusionList();

        // Add package due to package replaced.
        mService.onPackageAdded(PKGS.get(0), PKG_UIDS.get(0), true /* isReplacing */);
        verify(mVpn, never()).refreshPlatformVpnAppExclusionList();

        // Remove package
        mService.onPackageRemoved(PKGS.get(0), PKG_UIDS.get(0), false /* isReplacing */);
        verify(mVpn).refreshPlatformVpnAppExclusionList();

        reset(mVpn);

        // Add the package back
        mService.onPackageAdded(PKGS.get(0), PKG_UIDS.get(0), false /* isReplacing */);
        verify(mVpn).refreshPlatformVpnAppExclusionList();
    }
}
