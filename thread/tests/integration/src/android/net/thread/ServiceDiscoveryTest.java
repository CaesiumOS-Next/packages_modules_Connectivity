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

package android.net.thread;

import static android.Manifest.permission.MANAGE_TEST_NETWORKS;
import static android.Manifest.permission.NETWORK_SETTINGS;
import static android.net.InetAddresses.parseNumericAddress;
import static android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED;
import static android.net.NetworkCapabilities.TRANSPORT_TEST;
import static android.net.thread.ThreadNetworkManager.PERMISSION_THREAD_NETWORK_PRIVILEGED;
import static android.net.thread.utils.IntegrationTestUtils.JOIN_TIMEOUT;
import static android.net.thread.utils.IntegrationTestUtils.RESTART_JOIN_TIMEOUT;
import static android.net.thread.utils.IntegrationTestUtils.SERVICE_DISCOVERY_TIMEOUT;
import static android.net.thread.utils.IntegrationTestUtils.discoverForServiceLost;
import static android.net.thread.utils.IntegrationTestUtils.discoverService;
import static android.net.thread.utils.IntegrationTestUtils.isSimulatedThreadRadioSupported;
import static android.net.thread.utils.IntegrationTestUtils.resolveService;
import static android.net.thread.utils.IntegrationTestUtils.resolveServiceUntil;
import static android.net.thread.utils.IntegrationTestUtils.waitFor;
import static android.system.OsConstants.AF_INET6;
import static android.system.OsConstants.IPPROTO_UDP;
import static android.system.OsConstants.SOCK_DGRAM;

import static com.android.testutils.RecorderCallback.CallbackEntry.LINK_PROPERTIES_CHANGED;
import static com.android.testutils.TestPermissionUtil.runAsShell;

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeNotNull;
import static org.junit.Assume.assumeTrue;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TestNetworkInterface;
import android.net.TestNetworkManager;
import android.net.TestNetworkSpecifier;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.thread.utils.FullThreadDevice;
import android.os.HandlerThread;
import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.PollingCheck;
import com.android.testutils.TestableNetworkAgent;
import com.android.testutils.TestableNetworkCallback;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;

/** Integration test cases for Service Discovery feature. */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class ServiceDiscoveryTest {
    private static final String TAG = ServiceDiscoveryTest.class.getSimpleName();
    private final Context mContext = ApplicationProvider.getApplicationContext();

    private HandlerThread mHandlerThread;
    private ThreadNetworkController mController;
    private NsdManager mNsdManager;
    private TestNetworkTracker mTestNetworkTracker;

    // A valid Thread Active Operational Dataset generated from OpenThread CLI "dataset init new".
    private static final byte[] DEFAULT_DATASET_TLVS =
            base16().decode(
                            "0E080000000000010000000300001335060004001FFFE002"
                                    + "08ACC214689BC40BDF0708FD64DB1225F47E0B0510F26B31"
                                    + "53760F519A63BAFDDFFC80D2AF030F4F70656E5468726561"
                                    + "642D643961300102D9A00410A245479C836D551B9CA557F7"
                                    + "B9D351B40C0402A0FFF8");
    private static final ActiveOperationalDataset DEFAULT_DATASET =
            ActiveOperationalDataset.fromThreadTlvs(DEFAULT_DATASET_TLVS);

    @Before
    public void setUp() throws Exception {
        final ThreadNetworkManager manager = mContext.getSystemService(ThreadNetworkManager.class);
        if (manager != null) {
            mController = manager.getAllThreadNetworkControllers().get(0);
        }

        // Run the tests on only devices where the Thread feature is available.
        assumeNotNull(mController);

        // Run the tests only when the device uses simulated Thread radio.
        assumeTrue(isSimulatedThreadRadioSupported());

        // BR forms a network.
        CompletableFuture<Void> joinFuture = new CompletableFuture<>();
        runAsShell(
                PERMISSION_THREAD_NETWORK_PRIVILEGED,
                () -> mController.join(DEFAULT_DATASET, directExecutor(), joinFuture::complete));
        joinFuture.get(RESTART_JOIN_TIMEOUT.toMillis(), MILLISECONDS);

        mNsdManager = mContext.getSystemService(NsdManager.class);

        mHandlerThread = new HandlerThread(getClass().getSimpleName());
        mHandlerThread.start();

        mTestNetworkTracker = new TestNetworkTracker(mContext, mHandlerThread.getLooper());
        assertThat(mTestNetworkTracker).isNotNull();
        runAsShell(
                PERMISSION_THREAD_NETWORK_PRIVILEGED,
                NETWORK_SETTINGS,
                () -> {
                    CompletableFuture<Void> future = new CompletableFuture<>();
                    mController.setTestNetworkAsUpstream(
                            mTestNetworkTracker.getInterfaceName(),
                            directExecutor(),
                            v -> future.complete(null));
                    future.get(5, SECONDS);
                });
    }

    @After
    public void tearDown() throws Exception {
        mTestNetworkTracker.tearDown();
        mHandlerThread.quitSafely();
        mHandlerThread.join();
        runAsShell(
                PERMISSION_THREAD_NETWORK_PRIVILEGED,
                NETWORK_SETTINGS,
                () -> {
                    CountDownLatch latch = new CountDownLatch(2);
                    mController.setTestNetworkAsUpstream(
                            null, directExecutor(), v -> latch.countDown());
                    mController.leave(directExecutor(), v -> latch.countDown());
                    latch.await(10, SECONDS);
                });
    }

    @Test
    public void advertisingProxy_multipleSrpClientsRegisterServices_servicesResolvableByMdns()
            throws Exception {
        /*
         * <pre>
         * Topology:
         *                    Thread
         *  Border Router -------------- Full Thread device 1
         *  (Cuttlefish)         |
         *                       +------ Full Thread device 2
         *                       |
         *                       +------ Full Thread device 3
         * </pre>
         */

        final int NUM_FTD = 3;

        // Creates Full Thread Devices (FTD) and let them join the network.
        List<FullThreadDevice> ftds = new ArrayList<>();
        for (int i = 0; i < NUM_FTD; ++i) {
            FullThreadDevice ftd = new FullThreadDevice(i + 10 /* node ID */);

            ftd.factoryReset();
            ftd.joinNetwork(DEFAULT_DATASET);
            ftd.waitForStateAnyOf(List.of("router", "child"), JOIN_TIMEOUT);
            ftds.add(ftd);
        }

        // For every FTD, let it register an SRP service.
        for (int i = 0; i < ftds.size(); ++i) {
            FullThreadDevice ftd = ftds.get(i);

            ftd.autoStartSrpClient();
            ftd.setSrpHostname(String.format("host-%d", i));
            ftd.setSrpHostAddresses(
                    List.of("2001:db8::" + (2 * i + 1), "2001:db8::" + (2 * i + 2)));
            ftd.addSrpService(
                    "service-" + i,
                    "_test" + i + "._tcp",
                    Collections.emptyList() /* subtypes */,
                    12345,
                    Map.of("key1", bytes(0x01, 0x02), "key2", bytes(i)));
        }

        // Check the advertised services are discoverable and resolvable by NsdManager
        for (int i = 0; i < ftds.size(); ++i) {
            NsdServiceInfo discoveredService = discoverService(mNsdManager, "_test" + i + "._tcp");
            assertThat(discoveredService).isNotNull();
            NsdServiceInfo resolvedService = resolveService(mNsdManager, discoveredService);

            assertThat(resolvedService.getServiceName()).isEqualTo("service-" + i);
            assertThat(resolvedService.getServiceType()).isEqualTo("_test" + i + "._tcp");
            assertThat(resolvedService.getPort()).isEqualTo(12345);
            assertThat(resolvedService.getServiceName()).isEqualTo("service-" + i);

            Map<String, byte[]> resolvedTxtMap = resolvedService.getAttributes();
            assertThat(resolvedTxtMap.get("key1")).isEqualTo(bytes(0x01, 0x02));
            assertThat(resolvedTxtMap.get("key2")).isEqualTo(bytes(i));
            assertThat(resolvedService.getHostname()).isEqualTo("host-" + i);
            assertThat(resolvedService.getHostAddresses())
                    .isEqualTo(
                            List.of(
                                    parseNumericAddress("2001:db8::" + (2 * i + 1)),
                                    parseNumericAddress("2001:db8::" + (2 * i + 2))));
        }

        // Clear the registered host and services
        for (FullThreadDevice ftd : ftds) {
            ftd.removeSrpHost();
        }
    }

    @Test
    public void advertisingProxy_srpClientUpdatesService_updatedServiceResolvableByMdns()
            throws Exception {

        /*
         * <pre>
         * Topology:
         *                    Thread
         *  Border Router -------------- Full Thread device
         *  (Cuttlefish)
         * </pre>
         */

        // Creates a Full Thread Devices (FTD) and let it join the network.
        FullThreadDevice ftd = new FullThreadDevice(20 /* node ID */);

        ftd.factoryReset();
        ftd.joinNetwork(DEFAULT_DATASET);
        ftd.waitForStateAnyOf(List.of("router", "child"), JOIN_TIMEOUT);

        ftd.autoStartSrpClient();
        ftd.setSrpHostname("my-host");
        ftd.setSrpHostAddresses(List.of("2001:db8::1", "2001:db8::2"));
        ftd.addSrpService(
                "my-service",
                "_test._tcp",
                Collections.emptyList() /* subtypes */,
                12345,
                Map.of("key1", bytes(0x01, 0x02), "key2", bytes(0x03)));

        // Check the advertised service is discoverable and resolvable by NsdManager
        NsdServiceInfo discoveredService = discoverService(mNsdManager, "_test._tcp");
        assertThat(discoveredService).isNotNull();
        NsdServiceInfo resolvedService = resolveService(mNsdManager, discoveredService);

        assertThat(resolvedService.getServiceName()).isEqualTo("my-service");
        assertThat(resolvedService.getServiceType()).isEqualTo("_test._tcp");
        assertThat(resolvedService.getPort()).isEqualTo(12345);
        assertThat(resolvedService.getServiceName()).isEqualTo("my-service");

        Map<String, byte[]> resolvedTxtMap = resolvedService.getAttributes();
        assertThat(resolvedTxtMap.size()).isEqualTo(2);
        assertThat(resolvedTxtMap.get("key1")).isEqualTo(bytes(0x01, 0x02));
        assertThat(resolvedTxtMap.get("key2")).isEqualTo(bytes(0x03));
        assertThat(resolvedService.getHostname()).isEqualTo("my-host");
        assertThat(resolvedService.getHostAddresses())
                .isEqualTo(
                        List.of(
                                parseNumericAddress("2001:db8::1"),
                                parseNumericAddress("2001:db8::2")));

        // Update the host addresses and check the updated addresses are in the resolved service
        // instance.
        ftd.setSrpHostAddresses(List.of("2001:db8::3"));
        waitFor(() -> ftd.isSrpHostRegistered("my-host"), SERVICE_DISCOVERY_TIMEOUT);
        resolvedService =
                resolveServiceUntil(
                        mNsdManager, discoveredService, s -> s.getHostAddresses().size() == 1);

        assertThat(resolvedService.getServiceName()).isEqualTo("my-service");
        assertThat(resolvedService.getServiceType()).isEqualTo("_test._tcp");
        assertThat(resolvedService.getPort()).isEqualTo(12345);
        assertThat(resolvedService.getServiceName()).isEqualTo("my-service");

        resolvedTxtMap = resolvedService.getAttributes();
        assertThat(resolvedTxtMap.size()).isEqualTo(2);
        assertThat(resolvedTxtMap.get("key1")).isEqualTo(bytes(0x01, 0x02));
        assertThat(resolvedTxtMap.get("key2")).isEqualTo(bytes(0x03));
        assertThat(resolvedService.getHostname()).isEqualTo("my-host");
        assertThat(resolvedService.getHostAddresses())
                .isEqualTo(List.of(parseNumericAddress("2001:db8::3")));

        // Update the service and check the updated service is discoverable and resolvable by
        // NsdManager
        ftd.updateSrpService(
                "my-service", "_test._tcp", List.of("_sub3"), 11111, Map.of("key1", bytes(0x04)));
        //        waitFor(
        //                () -> ftd.isSrpServiceRegistered("my-service", "_test._tcp"),
        //                SERVICE_DISCOVERY_TIMEOUT);

        discoveredService = discoverService(mNsdManager, "_test._tcp");
        assertThat(discoveredService).isNotNull();
        resolvedService =
                resolveServiceUntil(mNsdManager, discoveredService, s -> s.getPort() == 11111);
        assertThat(resolvedService.getServiceName()).isEqualTo("my-service");
        assertThat(resolvedService.getServiceType()).isEqualTo("_test._tcp");
        assertThat(resolvedService.getPort()).isEqualTo(11111);
        assertThat(resolvedService.getServiceName()).isEqualTo("my-service");
        resolvedTxtMap = resolvedService.getAttributes();
        assertThat(resolvedTxtMap.size()).isEqualTo(1);
        assertThat(resolvedTxtMap.get("key1")).isEqualTo(bytes(0x04));
        assertThat(resolvedService.getHostname()).isEqualTo("my-host");
        assertThat(resolvedService.getHostAddresses())
                .isEqualTo(List.of(parseNumericAddress("2001:db8::3")));

        // Clean the registered host and services
        ftd.removeSrpHost();
    }

    @Test
    public void advertisingProxy_srpClientUnregistersService_serviceIsNotDiscoverableByMdns()
            throws Exception {
        /*
         * <pre>
         * Topology:
         *                    Thread
         *  Border Router -------------- Full Thread device
         *  (Cuttlefish)
         * </pre>
         */

        // Creates a Full Thread Devices (FTD) and let it join the network.
        FullThreadDevice ftd = new FullThreadDevice(20 /* node ID */);

        ftd.factoryReset();
        ftd.joinNetwork(DEFAULT_DATASET);
        ftd.waitForStateAnyOf(List.of("router", "child"), JOIN_TIMEOUT);

        ftd.autoStartSrpClient();
        ftd.setSrpHostname("my-host");
        ftd.setSrpHostAddresses(List.of("2001:db8::1", "2001:db8::2"));
        ftd.addSrpService(
                "my-service",
                "_test._udp",
                Collections.emptyList() /* subtypes */,
                12345,
                Map.of("key1", bytes(0x01, 0x02), "key2", bytes(0x03)));
        waitFor(
                () -> ftd.isSrpServiceRegistered("my-service", "_test._udp"),
                SERVICE_DISCOVERY_TIMEOUT);

        // Check the advertised service is discoverable and resolvable by NsdManager.
        NsdServiceInfo discoveredService = discoverService(mNsdManager, "_test._udp");
        assertThat(discoveredService).isNotNull();
        NsdServiceInfo resolvedService = resolveService(mNsdManager, discoveredService);
        assertThat(resolvedService).isNotNull();

        // Unregister the service and check that it's not discoverable any more.
        CompletableFuture<NsdServiceInfo> serviceLostFuture = new CompletableFuture<>();
        NsdManager.DiscoveryListener listener =
                discoverForServiceLost(mNsdManager, "_test._udp", serviceLostFuture);
        ftd.removeSrpService("my-service", "_test._udp", true /* notifyServer */);
        try {
            serviceLostFuture.get(SERVICE_DISCOVERY_TIMEOUT.toMillis(), MILLISECONDS);
        } finally {
            mNsdManager.stopServiceDiscovery(listener);
        }
        assertThrows(TimeoutException.class, () -> discoverService(mNsdManager, "_test._udp"));
    }

    private static byte[] bytes(int... byteInts) {
        byte[] bytes = new byte[byteInts.length];
        for (int i = 0; i < byteInts.length; ++i) {
            bytes[i] = (byte) byteInts[i];
        }
        return bytes;
    }

    private static class TestNetworkTracker {
        private static final Duration TIMEOUT = Duration.ofSeconds(2);
        private final Context mContext;
        private final Looper mLooper;
        private TestNetworkInterface mInterface;
        private TestableNetworkAgent mAgent;
        private final TestableNetworkCallback mNetworkCallback;
        private final ConnectivityManager mConnectivityManager;

        TestNetworkTracker(Context context, Looper looper) {
            mContext = context;
            mLooper = looper;
            mConnectivityManager = mContext.getSystemService(ConnectivityManager.class);
            mNetworkCallback = new TestableNetworkCallback();
            runAsShell(MANAGE_TEST_NETWORKS, this::setUpTestNetwork);
        }

        public void tearDown() {
            runAsShell(MANAGE_TEST_NETWORKS, this::tearDownTestNetwork);
        }

        private String getInterfaceName() {
            return mInterface.getInterfaceName();
        }

        private void setUpTestNetwork() throws Exception {
            mInterface = mContext.getSystemService(TestNetworkManager.class).createTapInterface();

            mConnectivityManager.requestNetwork(newNetworkRequest(), mNetworkCallback);

            LinkProperties lp = new LinkProperties();
            lp.setInterfaceName(getInterfaceName());
            mAgent =
                    new TestableNetworkAgent(
                            mContext,
                            mLooper,
                            newNetworkCapabilities(),
                            lp,
                            new NetworkAgentConfig.Builder().build());
            final Network network = mAgent.register();
            mAgent.markConnected();

            PollingCheck.check(
                    "No usable address on interface",
                    TIMEOUT.toMillis(),
                    () -> hasUsableAddress(network, getInterfaceName()));

            lp.setLinkAddresses(makeLinkAddresses());
            mAgent.sendLinkProperties(lp);
            mNetworkCallback.eventuallyExpect(
                    LINK_PROPERTIES_CHANGED,
                    TIMEOUT.toMillis(),
                    l -> !l.getLp().getAddresses().isEmpty());
        }

        private void tearDownTestNetwork() throws IOException {
            mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
            mAgent.unregister();
            mInterface.getFileDescriptor().close();
            mAgent.waitForIdle(TIMEOUT.toMillis());
        }

        private NetworkRequest newNetworkRequest() {
            return new NetworkRequest.Builder()
                    .removeCapability(NET_CAPABILITY_TRUSTED)
                    .addTransportType(TRANSPORT_TEST)
                    .setNetworkSpecifier(new TestNetworkSpecifier(getInterfaceName()))
                    .build();
        }

        private NetworkCapabilities newNetworkCapabilities() {
            return new NetworkCapabilities()
                    .removeCapability(NET_CAPABILITY_TRUSTED)
                    .addTransportType(TRANSPORT_TEST)
                    .setNetworkSpecifier(new TestNetworkSpecifier(getInterfaceName()));
        }

        private List<LinkAddress> makeLinkAddresses() {
            List<LinkAddress> linkAddresses = new ArrayList<>();
            List<InterfaceAddress> interfaceAddresses = Collections.emptyList();

            try {
                interfaceAddresses =
                        NetworkInterface.getByName(getInterfaceName()).getInterfaceAddresses();
            } catch (SocketException ignored) {
            }

            for (InterfaceAddress address : interfaceAddresses) {
                linkAddresses.add(
                        new LinkAddress(address.getAddress(), address.getNetworkPrefixLength()));
            }

            return linkAddresses;
        }

        private static boolean hasUsableAddress(Network network, String interfaceName) {
            try {
                if (NetworkInterface.getByName(interfaceName).getInterfaceAddresses().isEmpty()) {
                    return false;
                }
            } catch (SocketException e) {
                return false;
            }
            try {
                FileDescriptor sock = Os.socket(AF_INET6, SOCK_DGRAM, IPPROTO_UDP);
                network.bindSocket(sock);
                Os.connect(sock, parseNumericAddress("ff02::fb%" + interfaceName), 12345);
                Os.close(sock);
            } catch (ErrnoException | IOException e) {
                return false;
            }
            return true;
        }
    }
}
