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

import static android.net.NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED;
import static android.net.NetworkCapabilities.TRANSPORT_TEST;

import static com.android.compatibility.common.util.SystemUtil.callWithShellPermissionIdentity;
import static com.android.compatibility.common.util.SystemUtil.runWithShellPermissionIdentity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeNotNull;

import android.annotation.NonNull;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.EthernetManager;
import android.net.EthernetNetworkManagementException;
import android.net.EthernetNetworkUpdateRequest;
import android.net.IpConfiguration;
import android.net.LinkAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.StaticIpConfiguration;
import android.net.TestNetworkInterface;
import android.net.TestNetworkManager;
import android.os.OutcomeReceiver;
import android.platform.test.annotations.AppModeFull;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SdkSuppress;
import androidx.test.runner.AndroidJUnit4;

import com.android.testutils.RecorderCallback;
import com.android.testutils.TestableNetworkCallback;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class EthernetManagerTest {
    private static final String TAG = EthernetManagerTest.class.getSimpleName();
    private static final NetworkRequest TEST_REQUEST = new NetworkRequest
            .Builder().clearCapabilities().addTransportType(TRANSPORT_TEST).build();
    private static final int TIMEOUT_MS = 30_000;
    private static final String LOCAL_IP_ADDRESS = "192.168.26.5/32";
    private static final LinkAddress LOCAL_LINK_ADDRESS = new LinkAddress(LOCAL_IP_ADDRESS);
    private static final StaticIpConfiguration LOCAL_STATIC_IP_CONFIG =
            new StaticIpConfiguration.Builder().setIpAddress(LOCAL_LINK_ADDRESS).build();
    private static final IpConfiguration LOCAL_IP_CONFIG =
            new IpConfiguration.Builder().setStaticIpConfiguration(LOCAL_STATIC_IP_CONFIG).build();

    private final Context mContext = InstrumentationRegistry.getContext();
    private final Executor mExecutor = mContext.getMainExecutor();
    private final EthernetManager mEm = mContext.getSystemService(EthernetManager.class);
    private final ConnectivityManager mCm = mContext.getSystemService(ConnectivityManager.class);
    private final TestNetworkManager mTnm = callWithShellPermissionIdentity(() ->
            mContext.getSystemService(TestNetworkManager.class));

    private TestableNetworkCallback mTestNetworkCb;
    private TestNetworkInterface mTestInterface;

    public EthernetManagerTest() throws Exception {}

    @Before
    public void setUp() {
        // EthernetManager may be null depending on the device's configuration.
        assumeNotNull(mEm);
        runWithShellPermissionIdentity(() -> mEm.setIncludeTestInterfaces(true));
    }

    @After
    public void tearDown() {
        runWithShellPermissionIdentity(() -> mEm.setIncludeTestInterfaces(false));
        if (null != mTestNetworkCb) {
            if (null != mTestNetworkCb.getLastAvailableNetwork()) {
                try {
                    mTestInterface.getFileDescriptor().close();
                } catch (IOException e) {
                    Log.e(TAG, "Closing test interface fd failed.", e);
                }
                waitForLost(mTestNetworkCb);
            }
            mCm.unregisterNetworkCallback(mTestNetworkCb);
        }
    }

    private String connectNetwork(@NonNull final String iface) throws Exception {
        final EthManagementListener connectListener = new EthManagementListener();
        runWithShellPermissionIdentity(
                () -> mEm.enableInterface(iface, mExecutor, connectListener));
        return connectListener.expectComplete();
    }

    private void disconnectNetwork(@NonNull final String iface) throws Exception {
        final EthManagementListener disconnectListener = new EthManagementListener();
        runWithShellPermissionIdentity(
                () -> mEm.disableInterface(iface, mExecutor, disconnectListener));
        disconnectListener.expectComplete();
    }

    private void waitForLost(@NonNull final TestableNetworkCallback cb) {
        cb.eventuallyExpect(RecorderCallback.CallbackEntry.LOST, TIMEOUT_MS,
                c -> c instanceof RecorderCallback.CallbackEntry.Lost);
    }

    private String createTestNetwork() throws Exception {
        mTestInterface = callWithShellPermissionIdentity(mTnm::createTapInterface);
        Log.i(TAG, "Created test interface " + mTestInterface.getInterfaceName());
        final String iface = updateConfigurationWithDefaults();
        assertEquals("The test iface does not match the iface updated",
                mTestInterface.getInterfaceName(), iface);
        return mTestInterface.getInterfaceName();
    }

    private String updateConfigurationWithDefaults() throws Exception {
        final NetworkCapabilities nc =
                new NetworkCapabilities.Builder().addTransportType(TRANSPORT_TEST).build();
        return updateConfiguration(LOCAL_IP_CONFIG, nc);
    }

    private String updateConfiguration(@NonNull final IpConfiguration ipConfig,
            @NonNull final NetworkCapabilities nc) throws Exception {
        final EthManagementListener listener = new EthManagementListener();
        final EthernetNetworkUpdateRequest request =
                new EthernetNetworkUpdateRequest.Builder()
                        .setIpConfiguration(ipConfig)
                        .setNetworkCapabilities(nc)
                        .build();
        runWithShellPermissionIdentity(() ->
                mEm.updateConfiguration(
                        mTestInterface.getInterfaceName(), request, mExecutor, listener));
        final String ret = listener.expectComplete();
        assertNotNull("Provisioning test interface failed.", ret);
        return ret;
    }

    private static final class EthManagementListener implements
            OutcomeReceiver<String, EthernetNetworkManagementException> {
        private final CompletableFuture<String> mDone = new CompletableFuture<>();

        String expectComplete() throws Exception {
            return mDone.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        @Override
        public void onResult(@androidx.annotation.NonNull String result) {
            Log.i(TAG, "EthManagementListener resulting network " + result);
            mDone.complete(result);
        }

        @Override
        public void onError(@androidx.annotation.NonNull EthernetNetworkManagementException error) {
            Log.i(TAG, "EthManagementListener resulting error " + error);
            throw(error);
        }
    }

    private IpConfiguration createIpConfig(@NonNull final LinkAddress addr) {
        final StaticIpConfiguration staticIpConfig =
                new StaticIpConfiguration.Builder().setIpAddress(addr).build();
        return new IpConfiguration.Builder().setStaticIpConfiguration(staticIpConfig).build();
    }

    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    @AppModeFull(reason = "Instant apps cannot create test networks")
    @Test
    public void testConnectDisconnectNetwork() throws Exception {
        mTestNetworkCb = new TestableNetworkCallback();
        runWithShellPermissionIdentity(() -> mCm.requestNetwork(TEST_REQUEST, mTestNetworkCb));
        final String iface = createTestNetwork();
        mTestNetworkCb.expectAvailableThenValidatedCallbacks(TIMEOUT_MS);

        disconnectNetwork(iface);
        mTestNetworkCb.expectCallback(RecorderCallback.CallbackEntry.LOST,
                mTestNetworkCb.getLastAvailableNetwork(), TIMEOUT_MS);

        connectNetwork(iface);
        mTestNetworkCb.expectAvailableThenValidatedCallbacks(TIMEOUT_MS);
    }

    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    @AppModeFull(reason = "Instant apps cannot create test networks")
    @Test
    public void testUpdateConfiguration() throws Exception {
        mTestNetworkCb = new TestableNetworkCallback();
        runWithShellPermissionIdentity(() -> mCm.requestNetwork(TEST_REQUEST, mTestNetworkCb));
        createTestNetwork();
        mTestNetworkCb.expectAvailableThenValidatedCallbacks(TIMEOUT_MS);
        final Network n = mTestNetworkCb.getLastAvailableNetwork();
        assertNotNull(n);

        final int testCapability = NET_CAPABILITY_TEMPORARILY_NOT_METERED;
        final NetworkCapabilities nc =
                new NetworkCapabilities.Builder()
                        .addTransportType(TRANSPORT_TEST)
                        .addCapability(testCapability).build();
        final String ipAddress = "192.168.26.200/32";
        final LinkAddress newAddr = new LinkAddress(ipAddress);
        updateConfiguration(createIpConfig(newAddr), nc);

        mTestNetworkCb.expectCallback(RecorderCallback.CallbackEntry.LOST, n, TIMEOUT_MS);
        mTestNetworkCb.expectCallback(RecorderCallback.CallbackEntry.AVAILABLE, TIMEOUT_MS);
        mTestNetworkCb.expectCapabilitiesThat(mTestNetworkCb.getLastAvailableNetwork(), TIMEOUT_MS,
                netcap -> netcap.hasCapability(testCapability));
        mTestNetworkCb.expectLinkPropertiesThat(mTestNetworkCb.getLastAvailableNetwork(),
                TIMEOUT_MS, lp -> {
                    final LinkAddress linkAddr = lp.getLinkAddresses().get(0);
                    return linkAddr.getAddress().equals(newAddr.getAddress())
                            && linkAddr.getPrefixLength() == newAddr.getPrefixLength();
                });
    }
}
