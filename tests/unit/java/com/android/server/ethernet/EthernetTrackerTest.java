/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.ethernet;

import static android.net.TestNetworkManager.TEST_TAP_PREFIX;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.app.test.MockAnswerUtil.AnswerWithArguments;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.EthernetNetworkSpecifier;
import android.net.INetd;
import android.net.InetAddresses;
import android.net.IpConfiguration;
import android.net.IpConfiguration.IpAssignment;
import android.net.IpConfiguration.ProxySettings;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.NetworkProvider;
import android.net.NetworkProvider.NetworkOfferCallback;
import android.net.NetworkRequest;
import android.net.StaticIpConfiguration;
import android.net.ip.IpClientCallbacks;
import android.net.ip.IpClientManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.test.TestLooper;

import androidx.test.filters.SmallTest;

import com.android.net.module.util.InterfaceParams;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;
import com.android.testutils.HandlerUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Objects;

@SmallTest
@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
public class EthernetTrackerTest {
    private static final String TEST_IFACE = "test123";
    private static final int TIMEOUT_MS = 1_000;
    private static final String THREAD_NAME = "EthernetServiceThread";
    private static final EthernetCallback NULL_CB = new EthernetCallback(null);
    private static final String IP_ADDR = "192.0.2.2/25";
    private static final LinkAddress LINK_ADDR = new LinkAddress(IP_ADDR);
    private static final String HW_ADDR = "01:02:03:04:05:06";
    private EthernetTracker tracker;
    private HandlerThread mHandlerThread;
    private TestLooper mLooper;
    private Handler mHandler;
    private EthernetNetworkFactory mNetFactory = null;
    private IpClientCallbacks mIpClientCallbacks;
    private NetworkOfferCallback mNetworkOfferCallback;
    private NetworkRequest mRequestToKeepNetworkUp;
    @Mock private Context mContext;
    @Mock private EthernetNetworkFactory mFactory;
    @Mock private INetd mNetd;
    @Mock private EthernetTracker.Dependencies mDeps;
    @Mock private NetworkProvider mProvider;
    @Mock private IpClientManager mIpClient;
    @Mock private EthernetNetworkAgent mNetworkAgent;
    @Mock private InterfaceParams mInterfaceParams;
    @Mock private Network mMockNetwork;
    @Mock private NetworkProvider mNetworkProvider;

    private void initEthernetNetworkFactory() {
        mLooper = new TestLooper();
        mHandler = new Handler(mLooper.getLooper());
        mNetFactory = new EthernetNetworkFactory(mHandler, mContext, mNetworkProvider, mDeps);
    }

    private void setupNetworkAgentMock() {
        when(mDeps.makeEthernetNetworkAgent(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(new AnswerWithArguments() {
                    public EthernetNetworkAgent answer(
                            Context context,
                            Looper looper,
                            NetworkCapabilities nc,
                            LinkProperties lp,
                            NetworkAgentConfig config,
                            NetworkProvider provider,
                            EthernetNetworkAgent.Callbacks cb) {
                        when(mNetworkAgent.getCallbacks()).thenReturn(cb);
                        when(mNetworkAgent.getNetwork()).thenReturn(mMockNetwork);
                        return mNetworkAgent;
                    }
                });
    }

    private void setupIpClientMock() throws Exception {
        doAnswer(inv -> {
            // these tests only support one concurrent IpClient, so make sure we do not accidentally
            // create a mess.
            assertNull("An IpClient has already been created.", mIpClientCallbacks);

            mIpClientCallbacks = inv.getArgument(2);
            mIpClientCallbacks.onIpClientCreated(null);
            mLooper.dispatchAll();
            return null;
        }).when(mDeps).makeIpClient(any(Context.class), anyString(), any());

        doAnswer(inv -> {
            mIpClientCallbacks.onQuit();
            mLooper.dispatchAll();
            mIpClientCallbacks = null;
            return null;
        }).when(mIpClient).shutdown();

        when(mDeps.makeIpClientManager(any())).thenReturn(mIpClient);
    }

    private void setupContext() {
        when(mDeps.getTcpBufferSizesFromResource(eq(mContext))).thenReturn("");
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        initMockResources();
        doReturn(false).when(mFactory).updateInterfaceLinkState(anyString(), anyBoolean());
        doReturn(new String[0]).when(mNetd).interfaceGetList();
        mHandlerThread = new HandlerThread(THREAD_NAME);
        mHandlerThread.start();

        when(mDeps.makeNetworkProvider(any(), any())).thenReturn(mProvider);
        when(mDeps.makeNetworkFactory(any(), any(), any())).thenReturn(mFactory);

        setupNetworkAgentMock();
        setupIpClientMock();
        setupContext();

        tracker = new EthernetTracker(mContext, mHandlerThread.getThreadHandler(), mNetd, mDeps);
    }

    @After
    public void cleanUp() {
        mHandlerThread.quitSafely();
    }

    private void initMockResources() {
        doReturn("").when(mDeps).getInterfaceRegexFromResource(eq(mContext));
        doReturn(new String[0]).when(mDeps).getInterfaceConfigFromResource(eq(mContext));
    }

    private void waitForIdle() {
        HandlerUtils.waitForIdle(mHandlerThread, TIMEOUT_MS);
    }

    /**
     * Test: Creation of various valid static IP configurations
     */
    @Test
    public void createStaticIpConfiguration() {
        // Empty gives default StaticIPConfiguration object
        assertStaticConfiguration(new StaticIpConfiguration(), "");

        // Setting only the IP address properly cascades and assumes defaults
        assertStaticConfiguration(new StaticIpConfiguration.Builder()
                .setIpAddress(new LinkAddress("192.0.2.10/24")).build(), "ip=192.0.2.10/24");

        final ArrayList<InetAddress> dnsAddresses = new ArrayList<>();
        dnsAddresses.add(InetAddresses.parseNumericAddress("4.4.4.4"));
        dnsAddresses.add(InetAddresses.parseNumericAddress("8.8.8.8"));
        // Setting other fields properly cascades them
        assertStaticConfiguration(new StaticIpConfiguration.Builder()
                .setIpAddress(new LinkAddress("192.0.2.10/24"))
                .setDnsServers(dnsAddresses)
                .setGateway(InetAddresses.parseNumericAddress("192.0.2.1"))
                .setDomains("android").build(),
                "ip=192.0.2.10/24 dns=4.4.4.4,8.8.8.8 gateway=192.0.2.1 domains=android");

        // Verify order doesn't matter
        assertStaticConfiguration(new StaticIpConfiguration.Builder()
                .setIpAddress(new LinkAddress("192.0.2.10/24"))
                .setDnsServers(dnsAddresses)
                .setGateway(InetAddresses.parseNumericAddress("192.0.2.1"))
                .setDomains("android").build(),
                "domains=android ip=192.0.2.10/24 gateway=192.0.2.1 dns=4.4.4.4,8.8.8.8 ");
    }

    /**
     * Test: Attempt creation of various bad static IP configurations
     */
    @Test
    public void createStaticIpConfiguration_Bad() {
        assertStaticConfigurationFails("ip=192.0.2.1/24 gateway= blah=20.20.20.20");  // Unknown key
        assertStaticConfigurationFails("ip=192.0.2.1");  // mask is missing
        assertStaticConfigurationFails("ip=a.b.c");  // not a valid ip address
        assertStaticConfigurationFails("dns=4.4.4.4,1.2.3.A");  // not valid ip address in dns
        assertStaticConfigurationFails("=");  // Key and value is empty
        assertStaticConfigurationFails("ip=");  // Value is empty
        assertStaticConfigurationFails("ip=192.0.2.1/24 gateway=");  // Gateway is empty
    }

    private void assertStaticConfigurationFails(String config) {
        try {
            EthernetTracker.parseStaticIpConfiguration(config);
            fail("Expected to fail: " + config);
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    private void assertStaticConfiguration(StaticIpConfiguration expectedStaticIpConfig,
                String configAsString) {
        final IpConfiguration expectedIpConfiguration = new IpConfiguration();
        expectedIpConfiguration.setIpAssignment(IpAssignment.STATIC);
        expectedIpConfiguration.setProxySettings(ProxySettings.NONE);
        expectedIpConfiguration.setStaticIpConfiguration(expectedStaticIpConfig);

        assertEquals(expectedIpConfiguration,
                EthernetTracker.parseStaticIpConfiguration(configAsString));
    }

    private NetworkCapabilities.Builder makeEthernetCapabilitiesBuilder(boolean clearAll) {
        final NetworkCapabilities.Builder builder =
                clearAll ? NetworkCapabilities.Builder.withoutDefaultCapabilities()
                        : new NetworkCapabilities.Builder();
        return builder.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED);
    }

    /**
     * Test: Attempt to create a capabilties with various valid sets of capabilities/transports
     */
    @Test
    public void createNetworkCapabilities() {

        // Particularly common expected results
        NetworkCapabilities defaultEthernetCleared =
                makeEthernetCapabilitiesBuilder(true /* clearAll */)
                        .setLinkUpstreamBandwidthKbps(100000)
                        .setLinkDownstreamBandwidthKbps(100000)
                        .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                        .build();

        NetworkCapabilities ethernetClearedWithCommonCaps =
                makeEthernetCapabilitiesBuilder(true /* clearAll */)
                        .setLinkUpstreamBandwidthKbps(100000)
                        .setLinkDownstreamBandwidthKbps(100000)
                        .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                        .addCapability(12)
                        .addCapability(13)
                        .addCapability(14)
                        .addCapability(15)
                        .build();

        // Empty capabilities and transports lists with a "please clear defaults" should
        // yield an empty capabilities set with TRANPORT_ETHERNET
        assertParsedNetworkCapabilities(defaultEthernetCleared, true, "", "");

        // Empty capabilities and transports without the clear defaults flag should return the
        // default capabilities set with TRANSPORT_ETHERNET
        assertParsedNetworkCapabilities(
                makeEthernetCapabilitiesBuilder(false /* clearAll */)
                        .setLinkUpstreamBandwidthKbps(100000)
                        .setLinkDownstreamBandwidthKbps(100000)
                        .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                        .build(),
                false, "", "");

        // A list of capabilities without the clear defaults flag should return the default
        // capabilities, mixed with the desired capabilities, and TRANSPORT_ETHERNET
        assertParsedNetworkCapabilities(
                makeEthernetCapabilitiesBuilder(false /* clearAll */)
                        .setLinkUpstreamBandwidthKbps(100000)
                        .setLinkDownstreamBandwidthKbps(100000)
                        .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                        .addCapability(11)
                        .addCapability(12)
                        .build(),
                false, "11,12", "");

        // Adding a list of capabilities with a clear defaults will leave exactly those capabilities
        // with a default TRANSPORT_ETHERNET since no overrides are specified
        assertParsedNetworkCapabilities(ethernetClearedWithCommonCaps, true, "12,13,14,15", "");

        // Adding any invalid capabilities to the list will cause them to be ignored
        assertParsedNetworkCapabilities(ethernetClearedWithCommonCaps, true, "12,13,14,15,65,73", "");
        assertParsedNetworkCapabilities(ethernetClearedWithCommonCaps, true, "12,13,14,15,abcdefg", "");

        // Adding a valid override transport will remove the default TRANSPORT_ETHERNET transport
        // and apply only the override to the capabiltities object
        assertParsedNetworkCapabilities(
                makeEthernetCapabilitiesBuilder(true /* clearAll */)
                        .setLinkUpstreamBandwidthKbps(100000)
                        .setLinkDownstreamBandwidthKbps(100000)
                        .addTransportType(0)
                        .build(),
                true, "", "0");
        assertParsedNetworkCapabilities(
                makeEthernetCapabilitiesBuilder(true /* clearAll */)
                        .setLinkUpstreamBandwidthKbps(100000)
                        .setLinkDownstreamBandwidthKbps(100000)
                        .addTransportType(1)
                        .build(),
                true, "", "1");
        assertParsedNetworkCapabilities(
                makeEthernetCapabilitiesBuilder(true /* clearAll */)
                        .setLinkUpstreamBandwidthKbps(100000)
                        .setLinkDownstreamBandwidthKbps(100000)
                        .addTransportType(2)
                        .build(),
                true, "", "2");
        assertParsedNetworkCapabilities(
                makeEthernetCapabilitiesBuilder(true /* clearAll */)
                        .setLinkUpstreamBandwidthKbps(100000)
                        .setLinkDownstreamBandwidthKbps(100000)
                        .addTransportType(3)
                        .build(),
                true, "", "3");

        // "4" is TRANSPORT_VPN, which is unsupported. Should default back to TRANPORT_ETHERNET
        assertParsedNetworkCapabilities(defaultEthernetCleared, true, "", "4");

        // "5" is TRANSPORT_WIFI_AWARE, which is currently supported due to no legacy TYPE_NONE
        // conversion. When that becomes available, this test must be updated
        assertParsedNetworkCapabilities(defaultEthernetCleared, true, "", "5");

        // "6" is TRANSPORT_LOWPAN, which is currently supported due to no legacy TYPE_NONE
        // conversion. When that becomes available, this test must be updated
        assertParsedNetworkCapabilities(defaultEthernetCleared, true, "", "6");

        // Adding an invalid override transport will leave the transport as TRANSPORT_ETHERNET
        assertParsedNetworkCapabilities(defaultEthernetCleared,true, "", "100");
        assertParsedNetworkCapabilities(defaultEthernetCleared, true, "", "abcdefg");

        // Ensure the adding of both capabilities and transports work
        assertParsedNetworkCapabilities(
                makeEthernetCapabilitiesBuilder(true /* clearAll */)
                        .setLinkUpstreamBandwidthKbps(100000)
                        .setLinkDownstreamBandwidthKbps(100000)
                        .addCapability(12)
                        .addCapability(13)
                        .addCapability(14)
                        .addCapability(15)
                        .addTransportType(3)
                        .build(),
                true, "12,13,14,15", "3");

        // Ensure order does not matter for capability list
        assertParsedNetworkCapabilities(ethernetClearedWithCommonCaps, true, "13,12,15,14", "");
    }

    private void assertParsedNetworkCapabilities(NetworkCapabilities expectedNetworkCapabilities,
            boolean clearCapabilties, String configCapabiltiies,String configTransports) {
        assertEquals(expectedNetworkCapabilities,
                EthernetTracker.createNetworkCapabilities(clearCapabilties, configCapabiltiies,
                        configTransports).build());
    }

    @Test
    public void testCreateEthernetTrackerConfigReturnsCorrectValue() {
        final String capabilities = "2";
        final String ipConfig = "3";
        final String transport = "4";
        final String configString = String.join(";", TEST_IFACE, capabilities, ipConfig, transport);

        final EthernetTracker.EthernetTrackerConfig config =
                EthernetTracker.createEthernetTrackerConfig(configString);

        assertEquals(TEST_IFACE, config.mIface);
        assertEquals(capabilities, config.mCapabilities);
        assertEquals(ipConfig, config.mIpConfig);
        assertEquals(transport, config.mTransport);
    }

    @Test
    public void testCreateEthernetTrackerConfigThrowsNpeWithNullInput() {
        assertThrows(NullPointerException.class,
                () -> EthernetTracker.createEthernetTrackerConfig(null));
    }

    @Test
    public void testUpdateConfiguration() {
        final NetworkCapabilities capabilities = new NetworkCapabilities.Builder().build();
        final LinkAddress linkAddr = new LinkAddress("192.0.2.2/25");
        final StaticIpConfiguration staticIpConfig =
                new StaticIpConfiguration.Builder().setIpAddress(linkAddr).build();
        final IpConfiguration ipConfig =
                new IpConfiguration.Builder().setStaticIpConfiguration(staticIpConfig).build();
        final EthernetCallback listener = new EthernetCallback(null);

        tracker.updateConfiguration(TEST_IFACE, ipConfig, capabilities, listener);
        waitForIdle();

        verify(mFactory).updateInterface(
                eq(TEST_IFACE), eq(ipConfig), eq(capabilities));
    }

    @Test
    public void testIsValidTestInterfaceIsFalseWhenTestInterfacesAreNotIncluded() {
        final String validIfaceName = TEST_TAP_PREFIX + "123";
        tracker.setIncludeTestInterfaces(false);
        waitForIdle();

        final boolean isValidTestInterface = tracker.isValidTestInterface(validIfaceName);

        assertFalse(isValidTestInterface);
    }

    @Test
    public void testIsValidTestInterfaceIsFalseWhenTestInterfaceNameIsInvalid() {
        final String invalidIfaceName = "123" + TEST_TAP_PREFIX;
        tracker.setIncludeTestInterfaces(true);
        waitForIdle();

        final boolean isValidTestInterface = tracker.isValidTestInterface(invalidIfaceName);

        assertFalse(isValidTestInterface);
    }

    @Test
    public void testIsValidTestInterfaceIsTrueWhenTestInterfacesIncludedAndValidName() {
        final String validIfaceName = TEST_TAP_PREFIX + "123";
        tracker.setIncludeTestInterfaces(true);
        waitForIdle();

        final boolean isValidTestInterface = tracker.isValidTestInterface(validIfaceName);

        assertTrue(isValidTestInterface);
    }

    // TODO: cleanup tests moved from EthernetNetworkFactoryTest
    private void triggerOnProvisioningSuccess() {
        mIpClientCallbacks.onProvisioningSuccess(new LinkProperties());
        mLooper.dispatchAll();
    }

    private void triggerOnProvisioningFailure() {
        mIpClientCallbacks.onProvisioningFailure(new LinkProperties());
        mLooper.dispatchAll();
    }

    private void triggerOnReachabilityLost() {
        mIpClientCallbacks.onReachabilityLost("ReachabilityLost");
        mLooper.dispatchAll();
    }

    private NetworkCapabilities createDefaultFilterCaps() {
        return NetworkCapabilities.Builder.withoutDefaultCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build();
    }

    private NetworkCapabilities.Builder createInterfaceCapsBuilder(final int transportType) {
        return new NetworkCapabilities.Builder()
                .addTransportType(transportType)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED);
    }

    private NetworkRequest.Builder createDefaultRequestBuilder() {
        return new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private NetworkRequest createDefaultRequest() {
        return createDefaultRequestBuilder().build();
    }

    private IpConfiguration createDefaultIpConfig() {
        IpConfiguration ipConfig = new IpConfiguration();
        ipConfig.setIpAssignment(IpConfiguration.IpAssignment.DHCP);
        ipConfig.setProxySettings(IpConfiguration.ProxySettings.NONE);
        return ipConfig;
    }

    /**
     * Create an {@link IpConfiguration} with an associated {@link StaticIpConfiguration}.
     *
     * @return {@link IpConfiguration} with its {@link StaticIpConfiguration} set.
     */
    private IpConfiguration createStaticIpConfig() {
        final IpConfiguration ipConfig = new IpConfiguration();
        ipConfig.setIpAssignment(IpConfiguration.IpAssignment.STATIC);
        ipConfig.setStaticIpConfiguration(
                new StaticIpConfiguration.Builder().setIpAddress(LINK_ADDR).build());
        return ipConfig;
    }

    // creates an interface with provisioning in progress (since updating the interface link state
    // automatically starts the provisioning process)
    private void createInterfaceUndergoingProvisioning(String iface) {
        // Default to the ethernet transport type.
        createInterfaceUndergoingProvisioning(iface, NetworkCapabilities.TRANSPORT_ETHERNET);
    }

    private void createInterfaceUndergoingProvisioning(
            @NonNull final String iface, final int transportType) {
        final IpConfiguration ipConfig = createDefaultIpConfig();
        mNetFactory.addInterface(iface, HW_ADDR, ipConfig,
                createInterfaceCapsBuilder(transportType).build());
        assertTrue(mNetFactory.updateInterfaceLinkState(iface, true));

        ArgumentCaptor<NetworkOfferCallback> captor = ArgumentCaptor.forClass(
                NetworkOfferCallback.class);
        verify(mNetworkProvider).registerNetworkOffer(any(), any(), any(), captor.capture());
        mRequestToKeepNetworkUp = createDefaultRequest();
        mNetworkOfferCallback = captor.getValue();
        mNetworkOfferCallback.onNetworkNeeded(mRequestToKeepNetworkUp);

        verifyStart(ipConfig);
        clearInvocations(mDeps);
        clearInvocations(mIpClient);
        clearInvocations(mNetworkProvider);
    }

    // creates a provisioned interface
    private void createAndVerifyProvisionedInterface(String iface) throws Exception {
        // Default to the ethernet transport type.
        createAndVerifyProvisionedInterface(iface, NetworkCapabilities.TRANSPORT_ETHERNET,
                ConnectivityManager.TYPE_ETHERNET);
    }

    private void createVerifyAndRemoveProvisionedInterface(final int transportType,
            final int expectedLegacyType) throws Exception {
        createAndVerifyProvisionedInterface(TEST_IFACE, transportType,
                expectedLegacyType);
        mNetFactory.removeInterface(TEST_IFACE);
    }

    private void createAndVerifyProvisionedInterface(
            @NonNull final String iface, final int transportType, final int expectedLegacyType)
            throws Exception {
        createInterfaceUndergoingProvisioning(iface, transportType);
        triggerOnProvisioningSuccess();
        // provisioning succeeded, verify that the network agent is created, registered, marked
        // as connected and legacy type are correctly set.
        final ArgumentCaptor<NetworkCapabilities> ncCaptor = ArgumentCaptor.forClass(
                NetworkCapabilities.class);
        verify(mDeps).makeEthernetNetworkAgent(any(), any(), ncCaptor.capture(), any(),
                argThat(x -> x.getLegacyType() == expectedLegacyType), any(), any());
        assertEquals(
                new EthernetNetworkSpecifier(iface), ncCaptor.getValue().getNetworkSpecifier());
        verifyNetworkAgentRegistersAndConnects();
        clearInvocations(mDeps);
        clearInvocations(mNetworkAgent);
    }

    // creates an unprovisioned interface
    private void createUnprovisionedInterface(String iface) throws Exception {
        // To create an unprovisioned interface, provision and then "stop" it, i.e. stop its
        // NetworkAgent and IpClient. One way this can be done is by provisioning an interface and
        // then calling onNetworkUnwanted.
        mNetFactory.addInterface(iface, HW_ADDR, createDefaultIpConfig(),
                createInterfaceCapsBuilder(NetworkCapabilities.TRANSPORT_ETHERNET).build());
        assertTrue(mNetFactory.updateInterfaceLinkState(iface, true));

        clearInvocations(mIpClient);
        clearInvocations(mNetworkAgent);
    }

    @Test
    public void testUpdateInterfaceLinkStateForActiveProvisioningInterface() throws Exception {
        initEthernetNetworkFactory();
        createInterfaceUndergoingProvisioning(TEST_IFACE);

        // verify that the IpClient gets shut down when interface state changes to down.
        final boolean ret = mNetFactory.updateInterfaceLinkState(TEST_IFACE, false /* up */);

        assertTrue(ret);
        verify(mIpClient).shutdown();
    }

    @Test
    public void testUpdateInterfaceLinkStateForNonExistingInterface() throws Exception {
        initEthernetNetworkFactory();

        // if interface was never added, link state cannot be updated.
        final boolean ret = mNetFactory.updateInterfaceLinkState(TEST_IFACE, true /* up */);

        assertFalse(ret);
        verifyNoStopOrStart();
    }

    @Test
    public void testProvisioningLoss() throws Exception {
        initEthernetNetworkFactory();
        when(mDeps.getNetworkInterfaceByName(TEST_IFACE)).thenReturn(mInterfaceParams);
        createAndVerifyProvisionedInterface(TEST_IFACE);

        triggerOnProvisioningFailure();
        verifyStop();
        // provisioning loss should trigger a retry, since the interface is still there
        verify(mIpClient).startProvisioning(any());
    }

    private void verifyNoStopOrStart() {
        verify(mNetworkAgent, never()).register();
        verify(mIpClient, never()).shutdown();
        verify(mNetworkAgent, never()).unregister();
        verify(mIpClient, never()).startProvisioning(any());
    }

    @Test
    public void testNetworkUnwanted() throws Exception {
        initEthernetNetworkFactory();
        createAndVerifyProvisionedInterface(TEST_IFACE);

        mNetworkAgent.getCallbacks().onNetworkUnwanted();
        mLooper.dispatchAll();
        verifyStop();
    }

    @Test
    public void testNetworkUnwantedWithStaleNetworkAgent() throws Exception {
        initEthernetNetworkFactory();
        // ensures provisioning is restarted after provisioning loss
        when(mDeps.getNetworkInterfaceByName(TEST_IFACE)).thenReturn(mInterfaceParams);
        createAndVerifyProvisionedInterface(TEST_IFACE);

        EthernetNetworkAgent.Callbacks oldCbs = mNetworkAgent.getCallbacks();
        // replace network agent in EthernetNetworkFactory
        // Loss of provisioning will restart the ip client and network agent.
        triggerOnProvisioningFailure();
        verify(mDeps).makeIpClient(any(), any(), any());

        triggerOnProvisioningSuccess();
        verify(mDeps).makeEthernetNetworkAgent(any(), any(), any(), any(), any(), any(), any());

        // verify that unwanted is ignored
        clearInvocations(mIpClient);
        clearInvocations(mNetworkAgent);
        oldCbs.onNetworkUnwanted();
        verify(mIpClient, never()).shutdown();
        verify(mNetworkAgent, never()).unregister();
    }

    @Test
    public void testTransportOverrideIsCorrectlySet() throws Exception {
        initEthernetNetworkFactory();
        // createProvisionedInterface() has verifications in place for transport override
        // functionality which for EthernetNetworkFactory is network score and legacy type mappings.
        createVerifyAndRemoveProvisionedInterface(NetworkCapabilities.TRANSPORT_ETHERNET,
                ConnectivityManager.TYPE_ETHERNET);
        createVerifyAndRemoveProvisionedInterface(NetworkCapabilities.TRANSPORT_BLUETOOTH,
                ConnectivityManager.TYPE_BLUETOOTH);
        createVerifyAndRemoveProvisionedInterface(NetworkCapabilities.TRANSPORT_WIFI,
                ConnectivityManager.TYPE_WIFI);
        createVerifyAndRemoveProvisionedInterface(NetworkCapabilities.TRANSPORT_CELLULAR,
                ConnectivityManager.TYPE_MOBILE);
        createVerifyAndRemoveProvisionedInterface(NetworkCapabilities.TRANSPORT_LOWPAN,
                ConnectivityManager.TYPE_NONE);
        createVerifyAndRemoveProvisionedInterface(NetworkCapabilities.TRANSPORT_WIFI_AWARE,
                ConnectivityManager.TYPE_NONE);
        createVerifyAndRemoveProvisionedInterface(NetworkCapabilities.TRANSPORT_TEST,
                ConnectivityManager.TYPE_NONE);
    }

    @Test
    public void testReachabilityLoss() throws Exception {
        initEthernetNetworkFactory();
        createAndVerifyProvisionedInterface(TEST_IFACE);

        triggerOnReachabilityLost();

        // Reachability loss should trigger a stop and start, since the interface is still there
        verifyRestart(createDefaultIpConfig());
    }

    private IpClientCallbacks getStaleIpClientCallbacks() throws Exception {
        createAndVerifyProvisionedInterface(TEST_IFACE);
        final IpClientCallbacks staleIpClientCallbacks = mIpClientCallbacks;
        mNetFactory.removeInterface(TEST_IFACE);
        verifyStop();
        assertNotSame(mIpClientCallbacks, staleIpClientCallbacks);
        return staleIpClientCallbacks;
    }

    @Test
    public void testIgnoreOnIpLayerStartedCallbackForStaleCallback() throws Exception {
        initEthernetNetworkFactory();
        final IpClientCallbacks staleIpClientCallbacks = getStaleIpClientCallbacks();

        staleIpClientCallbacks.onProvisioningSuccess(new LinkProperties());
        mLooper.dispatchAll();

        verify(mIpClient, never()).startProvisioning(any());
        verify(mNetworkAgent, never()).register();
    }

    @Test
    public void testIgnoreOnIpLayerStoppedCallbackForStaleCallback() throws Exception {
        initEthernetNetworkFactory();
        when(mDeps.getNetworkInterfaceByName(TEST_IFACE)).thenReturn(mInterfaceParams);
        final IpClientCallbacks staleIpClientCallbacks = getStaleIpClientCallbacks();

        staleIpClientCallbacks.onProvisioningFailure(new LinkProperties());
        mLooper.dispatchAll();

        verify(mIpClient, never()).startProvisioning(any());
    }

    @Test
    public void testIgnoreLinkPropertiesCallbackForStaleCallback() throws Exception {
        initEthernetNetworkFactory();
        final IpClientCallbacks staleIpClientCallbacks = getStaleIpClientCallbacks();
        final LinkProperties lp = new LinkProperties();

        staleIpClientCallbacks.onLinkPropertiesChange(lp);
        mLooper.dispatchAll();

        verify(mNetworkAgent, never()).sendLinkPropertiesImpl(eq(lp));
    }

    @Test
    public void testIgnoreNeighborLossCallbackForStaleCallback() throws Exception {
        initEthernetNetworkFactory();
        final IpClientCallbacks staleIpClientCallbacks = getStaleIpClientCallbacks();

        staleIpClientCallbacks.onReachabilityLost("Neighbor Lost");
        mLooper.dispatchAll();

        verify(mIpClient, never()).startProvisioning(any());
        verify(mNetworkAgent, never()).register();
    }

    private void verifyRestart(@NonNull final IpConfiguration ipConfig) {
        verifyStop();
        verifyStart(ipConfig);
    }

    private void verifyStart(@NonNull final IpConfiguration ipConfig) {
        verify(mDeps).makeIpClient(any(Context.class), anyString(), any());
        verify(mIpClient).startProvisioning(
                argThat(x -> Objects.equals(x.mStaticIpConfig, ipConfig.getStaticIpConfiguration()))
        );
    }

    private void verifyStop() {
        verify(mIpClient).shutdown();
        verify(mNetworkAgent).unregister();
    }

    private void verifyNetworkAgentRegistersAndConnects() {
        verify(mNetworkAgent).register();
        verify(mNetworkAgent).markConnected();
    }

    @Test
    public void testOnNetworkNeededOnStaleNetworkOffer() throws Exception {
        initEthernetNetworkFactory();
        createAndVerifyProvisionedInterface(TEST_IFACE);
        mNetFactory.updateInterfaceLinkState(TEST_IFACE, false);
        verify(mNetworkProvider).unregisterNetworkOffer(mNetworkOfferCallback);
        // It is possible that even after a network offer is unregistered, CS still sends it
        // onNetworkNeeded() callbacks.
        mNetworkOfferCallback.onNetworkNeeded(createDefaultRequest());
        verify(mIpClient, never()).startProvisioning(any());
    }
}
