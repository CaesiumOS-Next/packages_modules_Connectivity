/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.net;

import static android.net.RouteInfo.RTN_UNICAST;

import static com.android.net.module.util.NetworkStackConstants.IPV4_ADDR_ANY;
import static com.android.net.module.util.NetworkStackConstants.IPV6_ADDR_ANY;

import static org.junit.Assert.assertNull;

import android.annotation.NonNull;
import android.content.Context;
import android.os.Looper;
import android.util.Log;
import android.util.Pair;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Class that allows creation and management a network with test and cellular transport.
 */
public class TestNetworkRequester extends ConnectivityManager.NetworkCallback {
    private static final String TAG = TestNetworkRequester.class.getSimpleName();
    private static final int TIMEOUT_MS = 5000;

    private final Context mContext;
    private final Looper mLooper;
    private final CompletableFuture<Pair<Network, LinkProperties>> mFuture =
            new CompletableFuture<>();
    final ConnectivityManager mCm;

    private TestNetworkAgent mAgent;
    /** Constructor of TestNetworkRequester. */
    public TestNetworkRequester(final Context ctx, final Looper looper) {
        mContext = ctx;
        mCm = ctx.getSystemService(ConnectivityManager.class);
        mLooper = looper;
    }

    private CompletableFuture<Pair<Network, LinkProperties>> requestTestNetwork(final String iface,
            final List<LinkAddress> addresses, final List<InetAddress> dnses, int mtu) {
        assertNull("BUG: test network already connected", mAgent);

        Log.d(TAG, "requestTestNetwork addresses: " + addresses + ", dnses: " + dnses);
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addTransportType(NetworkCapabilities.TRANSPORT_TEST)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                .build();
        mCm.requestNetwork(request, this);

        final LinkProperties lp = new LinkProperties();
        lp.setInterfaceName(iface);
        lp.setLinkAddresses(addresses);
        lp.addRoute(new RouteInfo(new IpPrefix(IPV6_ADDR_ANY, 0), null, iface, RTN_UNICAST, mtu));
        lp.addRoute(new RouteInfo(new IpPrefix(IPV4_ADDR_ANY, 0), null, iface, RTN_UNICAST, mtu));
        lp.setDnsServers(dnses);
        lp.setMtu(mtu);

        mAgent = setupTestNetworkAgent(lp);

        return mFuture;
    }

    /** Get network and linkproperties of testing network. This method would request the testing
     * network and waiting for connected. */
    public Pair<Network, LinkProperties> getTestNetwork(final String iface,
            final List<LinkAddress> addresses, final List<InetAddress> dnses, int mtu)
            throws Exception {
        return requestTestNetwork(iface, addresses, dnses, mtu).get(TIMEOUT_MS,
                TimeUnit.MILLISECONDS);
    }

    private TestNetworkAgent setupTestNetworkAgent(final LinkProperties lp) {
        final NetworkCapabilities nc = new NetworkCapabilities.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addTransportType(NetworkCapabilities.TRANSPORT_TEST)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build();

        Log.d(TAG, "register test network agent");
        return new TestNetworkAgent(mContext, mLooper, nc, lp);

    }

    /** Release testing network request and unreigster related NetworkAgent. */
    public void release() {
        if (mAgent != null) {
            mFuture.obtrudeException(new IllegalStateException("Request already released"));
            mAgent.unregister();
            mAgent = null;
        }

        mCm.unregisterNetworkCallback(this);

    }

    @Override
    public void onLinkPropertiesChanged(@NonNull Network network,
            @NonNull LinkProperties linkProperties) {
        Log.d(TAG, "Test network" + network + " connected: " + linkProperties);
        mFuture.complete(new Pair(network, linkProperties));
    }

    @Override
    public void onLost(@NonNull Network network) {
        Log.d(TAG, "Test network" + network + " disconnected");
        mFuture.completeExceptionally(new IllegalStateException("onLost received"));
    }

    // NetworkAgent is abstract, so a subclass is necessary
    private static final class TestNetworkAgent extends NetworkAgent {
        TestNetworkAgent(Context context, Looper looper, NetworkCapabilities nc,
                LinkProperties lp) {
            super(context, looper, "TetheringTest", nc, lp, 100,
                    new NetworkAgentConfig.Builder().build(),
                    new NetworkProvider(context, looper, "ScoreReportTest agent"));
            register();
            markConnected();
        }
    }
}
