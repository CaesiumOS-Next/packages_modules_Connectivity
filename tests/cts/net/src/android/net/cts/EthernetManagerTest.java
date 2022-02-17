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

import static android.net.NetworkCapabilities.TRANSPORT_ETHERNET;
import static android.net.NetworkCapabilities.TRANSPORT_TEST;

import static com.android.compatibility.common.util.SystemUtil.callWithShellPermissionIdentity;
import static com.android.compatibility.common.util.SystemUtil.runWithShellPermissionIdentity;

import static org.junit.Assert.fail;
import static org.junit.Assume.assumeNotNull;
import static org.junit.Assume.assumeTrue;

import android.annotation.NonNull;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.EthernetManager;
import android.net.EthernetNetworkManagementException;
import android.net.EthernetNetworkSpecifier;
import android.net.EthernetNetworkUpdateRequest;
import android.net.IpConfiguration;
import android.net.LinkAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.StaticIpConfiguration;
import android.net.TestNetworkManager;
import android.platform.test.annotations.AppModeFull;
import android.util.Log;
import android.util.Pair;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.testutils.RecorderCallback;
import com.android.testutils.TestableNetworkCallback;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

@RunWith(AndroidJUnit4.class)
public class EthernetManagerTest {
    private static final String TAG = EthernetManagerTest.class.getSimpleName();
    private static final NetworkRequest TEST_REQUEST = new NetworkRequest
            .Builder().clearCapabilities().addTransportType(TRANSPORT_TEST).build();
    private static final int TIMEOUT_MS = 30_000;
    private static final String LOCAL_IP_ADDRESS = "192.168.25.5/24";
    private static final LinkAddress LOCAL_LINK_ADDRESS = new LinkAddress(LOCAL_IP_ADDRESS);
    private static final StaticIpConfiguration LOCAL_STATIC_IP_CONFIG =
            new StaticIpConfiguration.Builder().setIpAddress(LOCAL_LINK_ADDRESS).build();
    private static final IpConfiguration LOCAL_IP_CONFIG =
            new IpConfiguration.Builder().setStaticIpConfiguration(LOCAL_STATIC_IP_CONFIG).build();

    private final Context mContext = InstrumentationRegistry.getContext();
    private final EthernetManager mEm = mContext.getSystemService(EthernetManager.class);
    private final ConnectivityManager mCm = mContext.getSystemService(ConnectivityManager.class);
    private final TestNetworkManager mTnm = callWithShellPermissionIdentity(() ->
            mContext.getSystemService(TestNetworkManager.class));

    public EthernetManagerTest() throws Exception {}

    @Before
    public void setUp() {
        // EthernetManager may be null depending on the device's configuration.
        assumeNotNull(mEm);
        runWithShellPermissionIdentity(() -> mEm.setIncludeTestInterfaces(true));
    }

    @After
    public void tearDown() throws Exception {
        runWithShellPermissionIdentity(() -> mEm.setIncludeTestInterfaces(false));
    }

    private String createTestInterface() throws Exception {
        final Executor executor = mContext.getMainExecutor();
        final EthManagementListener listener = new EthManagementListener();
        final NetworkCapabilities nc =
                new NetworkCapabilities.Builder().addTransportType(TRANSPORT_TEST).build();
        final EthernetNetworkUpdateRequest request =
                new EthernetNetworkUpdateRequest.Builder()
                        .setIpConfiguration(LOCAL_IP_CONFIG)
                        .setNetworkCapabilities(nc)
                        .build();

        final String testIface =
                callWithShellPermissionIdentity(mTnm::createTapInterface).getInterfaceName();
        runWithShellPermissionIdentity(() ->
                mEm.updateConfiguration(testIface, request, executor, listener));
        listener.expectComplete();
        Log.d(TAG, "Created test interface " + testIface);
        return testIface;
    }

    private Network getTestNetwork(@NonNull final String iface) {
        final TestableNetworkCallback cb = new TestableNetworkCallback();
        final NetworkRequest nr =
                new NetworkRequest.Builder()
                        .clearCapabilities()
                        .addTransportType(TRANSPORT_ETHERNET)
                        .setNetworkSpecifier(new EthernetNetworkSpecifier(iface))
                        .build();
        runWithShellPermissionIdentity(() -> mCm.requestNetwork(nr, cb));
        waitForAvailable(cb);
        return cb.getLastAvailableNetwork();
    }

    private void waitForAvailable(@NonNull final TestableNetworkCallback cb) {
        cb.eventuallyExpect(RecorderCallback.CallbackEntry.AVAILABLE, TIMEOUT_MS,
                c -> c instanceof RecorderCallback.CallbackEntry.Available);
    }

    private void waitForAvailable(
            @NonNull final TestableNetworkCallback cb, @NonNull final Network expectedNetwork) {
        cb.expectAvailableCallbacks(expectedNetwork, false /* suspended */,
                true /* validated */,
                false /* blocked */, TIMEOUT_MS);
    }

    private void waitForLost(@NonNull final TestableNetworkCallback cb) {
        cb.eventuallyExpect(RecorderCallback.CallbackEntry.LOST, TIMEOUT_MS,
                c -> c instanceof RecorderCallback.CallbackEntry.Lost);
    }

    private static final class EthManagementListener implements
            BiConsumer<Network, EthernetNetworkManagementException> {
        private final CompletableFuture<Pair<Network, EthernetNetworkManagementException>> mDone =
                new CompletableFuture<>();

        Pair<Network, EthernetNetworkManagementException> expectComplete() throws Exception {
            Pair<Network, EthernetNetworkManagementException> ret = null;
            try {
                return mDone.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                fail("Expected accept() not received after " + TIMEOUT_MS + " ms");
            }

            return ret;
        }

        @Override
        public void accept(Network network, EthernetNetworkManagementException e) {
            mDone.complete(new Pair<>(network, e));
        }
    }

    @AppModeFull(reason = "Instant apps cannot create test networks")
    @Test
    public void testConnectDisconnectNetwork() throws Exception {
        // Cannot use @IgnoreUpTo(Build.VERSION_CODES.R) because this test also requires API 31
        // shims, and @IgnoreUpTo does not check that.
        assumeTrue(TestUtils.shouldTestSApis());

        final TestableNetworkCallback cb = new TestableNetworkCallback();
        runWithShellPermissionIdentity(() -> mCm.requestNetwork(TEST_REQUEST, cb));
        cb.assertNoCallback();

        final String testIface = createTestInterface();
        final Network testNetwork = getTestNetwork(testIface);
        waitForAvailable(cb, testNetwork);

        final Executor executor = mContext.getMainExecutor();
        final EthManagementListener listener = new EthManagementListener();
        mEm.disconnectNetwork(testIface, executor, listener);
        listener.expectComplete();
        waitForLost(cb);

        mEm.connectNetwork(testIface, executor, listener);
        listener.expectComplete();
        waitForAvailable(cb, testNetwork);
    }
}
