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

import static android.Manifest.permission.MANAGE_TEST_NETWORKS;
import static android.Manifest.permission.NETWORK_SETTINGS;

import static androidx.test.InstrumentationRegistry.getContext;

import static com.android.testutils.DevSdkIgnoreRuleKt.SC_V2;
import static com.android.testutils.TestPermissionUtil.runAsShell;
import static com.android.testutils.TestableNetworkCallbackKt.anyNetwork;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.InetAddresses;
import android.net.MacAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TestNetworkInterface;
import android.net.TestNetworkManager;
import android.os.Handler;
import android.os.Looper;
import android.platform.test.annotations.AppModeFull;

import com.android.networkstack.apishim.EthernetManagerShimImpl;
import com.android.networkstack.apishim.common.EthernetManagerShim;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;
import com.android.testutils.RecorderCallback.CallbackEntry;
import com.android.testutils.RouterAdvertisementResponder;
import com.android.testutils.TapPacketReader;
import com.android.testutils.TestableNetworkCallback;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.NetworkInterface;
import java.util.ArrayList;


@AppModeFull(reason = "Instant apps cannot access /dev/tap, so createTapInterface fails")
@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(SC_V2)
public class EthernetNetworkTest {
    private static final String TAG = "EthernetNetworkTest";
    private static final NetworkRequest ETH_REQUEST = new NetworkRequest.Builder().addTransportType(
            NetworkCapabilities.TRANSPORT_TEST).addTransportType(
            NetworkCapabilities.TRANSPORT_ETHERNET).removeCapability(
            NetworkCapabilities.NET_CAPABILITY_TRUSTED).build();
    private static final int TIMEOUT_MS = 3000;

    private Handler mHandler;
    private Context mContext;
    private ConnectivityManager mCm;
    private EthernetManagerShim mEthernetManager;

    private final TestableNetworkCallback mNetworkCb = new TestableNetworkCallback();
    private final ArrayList<EthernetTestInterface> mTestInterfaces = new ArrayList<>();

    private static class EthernetTestInterface {
        private final Handler mHandler;
        private TestNetworkInterface mTapInterface;
        private final TapPacketReader mPacketReader;
        private final RouterAdvertisementResponder mRaResponder;

        EthernetTestInterface(Context context, Handler handler) throws IOException {
            mHandler = handler;
            runAsShell(MANAGE_TEST_NETWORKS, () -> {
                mTapInterface = context.getSystemService(
                        TestNetworkManager.class).createTapInterface();
            });
            NetworkInterface iface = NetworkInterface.getByName(mTapInterface.getInterfaceName());
            mPacketReader = new TapPacketReader(mHandler,
                    mTapInterface.getFileDescriptor().getFileDescriptor(), iface.getMTU());
            mRaResponder = new RouterAdvertisementResponder(mPacketReader);
            mRaResponder.addRouterEntry(MacAddress.fromString("01:23:45:67:89:ab"),
                    (Inet6Address) InetAddresses.parseNumericAddress("fe80::abcd"));
        }

        public void init() {
            mPacketReader.startAsyncForTest();
            mRaResponder.start();
        }

        public void destroy() {
            mRaResponder.stop();
            mHandler.post(() -> mPacketReader.stop());
        }

        public String getInterfaceName() {
            return mTapInterface.getInterfaceName();
        }
    }

    private EthernetTestInterface createTestInterface() throws IOException {
        EthernetTestInterface iface = new EthernetTestInterface(mContext, mHandler);
        iface.init();
        mTestInterfaces.add(iface);
        return iface;
    }

    @Before
    public void setUp() throws Exception {
        mHandler = new Handler(Looper.getMainLooper());
        mContext = getContext();
        mCm = mContext.getSystemService(ConnectivityManager.class);
        mEthernetManager = EthernetManagerShimImpl.newInstance(mContext);
        mCm.registerNetworkCallback(ETH_REQUEST, mNetworkCb);
    }

    @After
    public void tearDown() throws Exception {
        setIncludeTestInterfaces(false);

        if (mCm != null) mCm.unregisterNetworkCallback(mNetworkCb);
        for (EthernetTestInterface iface : mTestInterfaces) {
            iface.destroy();
        }
    }

    private void setIncludeTestInterfaces(boolean value) {
        runAsShell(NETWORK_SETTINGS, () -> {
            mEthernetManager.setIncludeTestInterfaces(value);
        });
    }

    private Network expectNetworkForRequestAvailable(TestableNetworkCallback cb) {
        CallbackEntry entry = cb.expectCallback(CallbackEntry.AVAILABLE, anyNetwork(), TIMEOUT_MS);
        assertNotNull(entry);
        return entry.getNetwork();
    }

    private void expectNetworkLost(Network n) {
        assertNotNull(mNetworkCb.eventuallyExpect(CallbackEntry.LOST, TIMEOUT_MS,
                (obj) -> n.equals(obj.getNetwork())));
    }

    @Test
    public void testEthernetWithSingleRequest() throws Exception {
        createTestInterface();
        setIncludeTestInterfaces(true);

        TestableNetworkCallback networkCb = new TestableNetworkCallback();
        mCm.requestNetwork(ETH_REQUEST, networkCb);
        Network n = expectNetworkForRequestAvailable(networkCb);

        mCm.unregisterNetworkCallback(networkCb);
        expectNetworkLost(n);
    }

    @Test
    public void testEthernetWithMultipleRequests() throws Exception {
        createTestInterface();
        TestableNetworkCallback firstRequestCb = new TestableNetworkCallback();
        mCm.requestNetwork(ETH_REQUEST, firstRequestCb);
        // wait some time to make sure ethernet-service has received the network request before
        // including the test interfaces.
        firstRequestCb.assertNoCallback(1000 /*timeout*/);
        setIncludeTestInterfaces(true);

        TestableNetworkCallback secondRequestCb = new TestableNetworkCallback();
        mCm.requestNetwork(ETH_REQUEST, secondRequestCb);

        // both requests should serve the same network
        Network firstNetwork = expectNetworkForRequestAvailable(firstRequestCb);
        Network secondNetwork = expectNetworkForRequestAvailable(secondRequestCb);
        assertEquals(firstNetwork, secondNetwork);

        // remove one request and assert that the second request still serves the network.
        mCm.unregisterNetworkCallback(firstRequestCb);
        secondRequestCb.assertNoCallbackThat(5000 /*timeout*/,
                (c) -> c instanceof CallbackEntry.Lost);

        mCm.unregisterNetworkCallback(secondRequestCb);
        expectNetworkLost(secondNetwork);
    }
}
