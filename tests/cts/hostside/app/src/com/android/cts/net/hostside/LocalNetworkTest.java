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

package com.android.cts.net.hostside;

import static android.Manifest.permission.MANAGE_TEST_NETWORKS;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.net.NetworkCapabilities.TRANSPORT_TEST;
import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.IPPROTO_TCP;
import static android.system.OsConstants.IPPROTO_UDP;
import static android.system.OsConstants.SOCK_DGRAM;

import static com.android.compatibility.common.util.SystemUtil.runShellCommand;
import static com.android.compatibility.common.util.SystemUtil.runWithShellPermissionIdentity;

import static com.android.testutils.Cleanup.testAndCleanup;
import static com.android.testutils.TestPermissionUtil.runAsShell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.annotation.NonNull;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.InetAddresses;
import android.net.LinkAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TestNetworkInterface;
import android.net.TestNetworkManager;
import android.os.Binder;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.Os;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.testutils.AutoReleaseNetworkCallbackRule;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.RecorderCallback.CallbackEntry;
import com.android.testutils.TestableNetworkCallback;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileDescriptor;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class LocalNetworkTest {

    private static final long NETWORK_CALLBACK_TIMEOUT_MS = 30_000;
    @Rule
    public final AutoReleaseNetworkCallbackRule
            networkCallbackRule = new AutoReleaseNetworkCallbackRule();


    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();
    private static final String TEST_APP_PKG = "com.android.cts.net.hostside.app2";
//    private static final List<LinkAddress> ifaceAddresses = List.of(new LinkAddress("192.0.2.2/24"),
//            new LinkAddress("2001:db8:1:2::ffe/64"));
//    private static final InetAddress localNetAddress =
    private MyActivity mActivity;
    private ConnectivityManager mCM;

    @Before
    public void setUp() {

    }

    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.BAKLAVA)
    @Test
    public void testMissingPermission_dropsPacketToLocalNet() throws Exception {
        assertEquals(
                mContext.getPackageManager().checkPermission(
                        Manifest.permission.NEARBY_WIFI_DEVICES, TEST_APP_PKG),
                PERMISSION_DENIED);

        final ParcelFileDescriptor srcTunFd = setupTestNetworkAndGetFd();  // source, from the test network
        final Network testNetwork = getTestNetwork();
        assertNotNull(testNetwork);
        final DatagramSocket dstSock = new DatagramSocket();

        final FileDescriptor sock = Os.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
        testNetwork.bindSocket(sock);  // network is sending / receiving data on this socket

        final InetAddress dstAddress = InetAddresses.parseNumericAddress("10.1.0.0");
        final InetSocketAddress dstSockAddress = new InetSocketAddress(dstAddress, 0);
        Os.connect(sock, dstSockAddress);
//        testAndCleanup(() -> {
//        })
    }

    private ParcelFileDescriptor setupTestNetworkAndGetFd() {
        return runWithShellPermissionIdentity(() -> {
            final TestNetworkManager tnm = mContext.getSystemService(TestNetworkManager.class);
            final List<LinkAddress> linkAddresses = List.of(new LinkAddress("192.0.2.2/24"),  // nonlocal
                    new LinkAddress("192.168.0.0/16"), new LinkAddress("10.0.0.0/8 "));  // local networks

            final TestNetworkInterface iface = tnm.createTunInterface(linkAddresses);
            tnm.setupTestNetwork(iface.getInterfaceName(), new Binder());
            return iface.getFileDescriptor();
        }, MANAGE_TEST_NETWORKS);
    }
    private Network getTestNetwork() {
        final TestableNetworkCallback callback = networkCallbackRule.requestNetwork(
                new NetworkRequest.Builder()
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                        .addTransportType(TRANSPORT_TEST)
                        .build()
        );
        return waitForAvailable(callback).getNetwork();
    }

    private CallbackEntry.Available waitForAvailable(@NonNull final TestableNetworkCallback cb) {
        return cb.eventuallyExpect(CallbackEntry.AVAILABLE, NETWORK_CALLBACK_TIMEOUT_MS,
                c -> c instanceof CallbackEntry.Available);
    }
}
