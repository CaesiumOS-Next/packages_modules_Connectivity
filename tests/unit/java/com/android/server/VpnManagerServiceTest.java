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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.UserIdInt;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.net.ConnectivityManager;
import android.net.INetd;
import android.net.Ikev2VpnProfile;
import android.net.LinkProperties;
import android.net.NetworkAgent;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.NetworkProvider;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.security.Credentials;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Range;
import android.util.SparseArray;

import androidx.test.filters.SmallTest;

import com.android.internal.net.VpnProfile;
import com.android.internal.util.HexDump;
import com.android.server.connectivity.Vpn;
import com.android.server.connectivity.VpnProfileStore;
import com.android.server.vcn.util.PersistableBundleUtils;
import com.android.testutils.DevSdkIgnoreRunner;
import com.android.testutils.HandlerUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

@RunWith(DevSdkIgnoreRunner.class)
@SmallTest
public class VpnManagerServiceTest {
    private static final String TAG = "VpnManagerServiceTest";

    private static final int TIMEOUT_MS = 2_000;
    private static final int TEST_PROVIDER_ID = 1;
    private static final String TEST_VPN_SERVER = "1.2.3.4";
    private static final String TEST_VPN_IDENTITY = "identity";
    private static final byte[] TEST_VPN_PSK = "psk".getBytes();
    private static final int PRIMARY_USER = 0;
    private static final String TEST_VPN_PKG = "com.testvpn.vpn";
    private static final List<String> PKGS =
            List.of("com.example", "org.example", "net.example", "web.vpn");
    private static final List<Integer> PKG_UIDS = List.of(11111, 11112, 11113, 11114);

    // Mock packages
    static final Map<String, Integer> sPackages = new ArrayMap<>();
    static final String PKGS_BYTES = getPackageByteString(PKGS);
    static {
        for (int i = 0; i < PKGS.size(); i++) {
            sPackages.put(PKGS.get(i), PKG_UIDS.get(i));
        }
        sPackages.put(TEST_VPN_PKG, Process.myUid());
    }

    private static final UserInfo PRIMARY_USER_INFO = new UserInfo(PRIMARY_USER, "",
            UserInfo.FLAG_PRIMARY);

    @Mock Context mContext;
    private HandlerThread mHandlerThread;
    @Mock private Handler mHandler;
    @Mock private INetworkManagementService mNms;
    @Mock private ConnectivityManager mCm;
    @Mock private NotificationManager mNotificationManager;
    @Mock private UserManager mUserManager;
    @Mock private INetd mNetd;
    @Mock private VpnProfileStore mVpnProfileStore;
    @Mock private PackageManager mPackageManager;
    @Mock private AppOpsManager mAppOps;
    private VpnManagerServiceDependencies mVMSDeps;
    private VpnManagerService mService;
    private final VpnProfile mVpnProfile;

    public VpnManagerServiceTest() throws Exception {
        // Build an actual VPN profile that is capable of being converted to and from an
        // Ikev2VpnProfile
        final Ikev2VpnProfile.Builder builder =
                new Ikev2VpnProfile.Builder(TEST_VPN_SERVER, TEST_VPN_IDENTITY);
        builder.setAuthPsk(TEST_VPN_PSK);
        mVpnProfile = builder.build().toVpnProfile();
    }

    private class MockVpn extends Vpn {
        MockVpn(int userId) {
            super(mHandlerThread.getLooper(), mContext,
                    new Dependencies() {
                        @Override
                        public boolean isCallerSystem() {
                            return true;
                        }
                    },
                    mNms, mNetd, userId, mVpnProfileStore,
                    new SystemServices(mContext) {
                        @Override
                        public String settingsSecureGetStringForUser(String key, int userId) {
                            switch (key) {
                                case Settings.Secure.ALWAYS_ON_VPN_APP:
                                case Settings.Secure.ALWAYS_ON_VPN_LOCKDOWN_WHITELIST:
                                    return null;
                                default:
                                    return super.settingsSecureGetStringForUser(key, userId);
                            }
                        }

                        @Override
                        public int settingsSecureGetIntForUser(String key, int def, int userId) {
                            switch (key) {
                                case Settings.Secure.ALWAYS_ON_VPN_LOCKDOWN:
                                    return 0;
                                default:
                                    return super.settingsSecureGetIntForUser(key, def, userId);
                            }
                        }
                    }, new Ikev2SessionCreator());
        }

        public NetworkAgent setNetworkAgent(NetworkAgent agent) {
            return mNetworkAgent = agent;
        }

        public NetworkCapabilities getNc() {
            return mNetworkCapabilities;
        }
    }

    class VpnManagerServiceDependencies extends VpnManagerService.Dependencies {
        protected final SparseArray<MockVpn> mVpns = new SparseArray<>();
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
            MockVpn vpn = new MockVpn(userId);
            mVpns.put(userId, vpn);
            return vpn;
        }

        public MockVpn getVpn(int userId) {
            return mVpns.get(userId);
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mHandlerThread = new HandlerThread("TestVpnManagerService");
        mVMSDeps = new VpnManagerServiceDependencies();
        doReturn(mContext).when(mContext).createContextAsUser(any(), anyInt());
        doReturn(mPackageManager).when(mContext).getPackageManager();
        setMockedPackages(sPackages);

        mockService(ConnectivityManager.class, Context.CONNECTIVITY_SERVICE, mCm);
        mockService(NotificationManager.class, Context.NOTIFICATION_SERVICE, mNotificationManager);
        mockService(UserManager.class, Context.USER_SERVICE, mUserManager);
        mockService(AppOpsManager.class, Context.APP_OPS_SERVICE, mAppOps);

        doReturn(TEST_PROVIDER_ID).when(mCm).registerNetworkProvider(any());
        doReturn(new Intent()).when(mContext).registerReceiver(
                any(), /* receiver */
                any() /* intentFilter */,
                any() /* broadcastPermission */,
                eq(mHandler) /* scheduler */);
        doReturn(PRIMARY_USER_INFO).when(mUserManager).getUserInfo(eq(PRIMARY_USER));

        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;
        doReturn(applicationInfo).when(mContext).getApplicationInfo();
        doReturn(applicationInfo).when(mPackageManager)
                .getApplicationInfoAsUser(anyString(), anyInt(), anyInt());

        doNothing().when(mNms).registerObserver(any());
        mService = new VpnManagerService(mContext, mVMSDeps);
    }

    private <T> void mockService(Class<T> clazz, String name, T service) {
        doReturn(service).when(mContext).getSystemService(name);
        doReturn(name).when(mContext).getSystemServiceName(clazz);
        if (mContext.getSystemService(clazz).getClass().equals(Object.class)) {
            doCallRealMethod().when(mContext).getSystemService(clazz);
        }
    }

    @After
    public void tearDown() throws Exception {

    }

    private static String getPackageByteString(List<String> packages) {
        try {
            return HexDump.toHexString(
                    PersistableBundleUtils.toDiskStableBytes(PersistableBundleUtils.fromList(
                            packages, PersistableBundleUtils.STRING_SERIALIZER)),
                    true /* upperCase */);
        } catch (IOException e) {
            return null;
        }
    }

    // Populate a fake packageName-to-UID mapping.
    private void setMockedPackages(final Map<String, Integer> packages) {
        try {
            doAnswer(invocation -> {
                final String appName = (String) invocation.getArguments()[0];
                final int userId = (int) invocation.getArguments()[1];
                Integer appId = packages.get(appName);
                if (appId == null) throw new PackageManager.NameNotFoundException(appName);
                return UserHandle.getUid(userId, appId);
            }).when(mPackageManager).getPackageUidAsUser(anyString(), anyInt());
        } catch (Exception e) {
        }
    }

    // Equal to Vpn.getProfileNameForPackage()
    private String getProfileNameForPackage(int userId, String packageName) {
        return Credentials.PLATFORM_VPN + userId + "_" + packageName;
    }

    // Equal to Vpn.getVpnAppExcludedForPackage()
    private String getVpnAppExcludedForPackage(int userId, String packageName) {
        return "VPN_APP_EXCLUDED_" + userId + "_" + packageName;
    }

    private void waitForIdle() {
        HandlerUtils.waitForIdle(mHandlerThread, TIMEOUT_MS);
    }

    private Set<Range<Integer>> makeExcludedUidRange(List<Integer> excludedList) {
        final SortedSet<Integer> list = new TreeSet<>();
        for (int uid : excludedList) {
            list.add(uid);
            list.add(uid + 10000); // Add Sdk Sandbox UID
        }
        int minUid = 0;
        int maxUid = 99999;
        Set<Range<Integer>> ranges = new ArraySet<>();
        int start = minUid;
        for (int uid : list) {
            if (uid == start) {
                start++;
            } else {
                ranges.add(new Range<>(start, uid - 1));
                start = uid + 1;
            }
        }
        if (start <= maxUid) {
            ranges.add(new Range<>(start, maxUid));
        }
        return ranges;
    }

    @Test
    public void testUpdateAppExclusionList() {
        doReturn(AppOpsManager.MODE_ALLOWED).when(mAppOps).noteOpNoThrow(
                AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN, Process.myUid(), TEST_VPN_PKG,
                null /* attributionTag */, null /* message */);
        doReturn(mVpnProfile.encode()).when(mVpnProfileStore).get(
                eq(getProfileNameForPackage(PRIMARY_USER, TEST_VPN_PKG)));
        when(mVpnProfileStore.get(getVpnAppExcludedForPackage(PRIMARY_USER, TEST_VPN_PKG)))
                .thenReturn(HexDump.hexStringToByteArray(PKGS_BYTES));

        // Add user to create vpn in mVpn
        mService.onUserStarted(PRIMARY_USER);
        assertNotNull(mService.mVpns.get(PRIMARY_USER));

        // Start vpn
        mService.startVpnProfile(TEST_VPN_PKG);
        MockVpn vpn = (MockVpn) mService.mVpns.get(PRIMARY_USER);

        // Configure networkAgent for the following app exclusion list update
        vpn.setNetworkAgent(new NetworkAgent(mContext, Looper.getMainLooper(), TAG,
                new NetworkCapabilities.Builder().build(), new LinkProperties(), 10 /* score */,
                new NetworkAgentConfig.Builder().build(),
                new NetworkProvider(mContext, Looper.getMainLooper(), TAG)) {});

        // No uid range assigned at the beginning.
        assertEquals(null, vpn.getNc().getUids());

        // Set the app exclusion list
        mService.setAppExclusionList(PRIMARY_USER, TEST_VPN_PKG, PKGS);
        waitForIdle();

        // Verify the excluded list is updated.
        assertEquals(makeExcludedUidRange(PKG_UIDS), vpn.getNc().getUids());
        verify(mVpnProfileStore).put(getVpnAppExcludedForPackage(PRIMARY_USER, TEST_VPN_PKG),
                HexDump.hexStringToByteArray(PKGS_BYTES));

        // Remove one of the package
        List<Integer> newExcludedUids = new ArrayList<>(PKG_UIDS);
        newExcludedUids.remove(PKG_UIDS.get(0));
        sPackages.remove(PKGS.get(0));
        mService.onPackageRemoved(PKGS.get(0), PKG_UIDS.get(0), false);
        waitForIdle();
        assertEquals(makeExcludedUidRange(newExcludedUids), vpn.getNc().getUids());

        // Add the package back
        newExcludedUids.add(PKG_UIDS.get(0));
        sPackages.put(PKGS.get(0), PKG_UIDS.get(0));
        mService.onPackageAdded(PKGS.get(0), PKG_UIDS.get(0));
        waitForIdle();
        assertEquals(makeExcludedUidRange(newExcludedUids), vpn.getNc().getUids());
    }
}
