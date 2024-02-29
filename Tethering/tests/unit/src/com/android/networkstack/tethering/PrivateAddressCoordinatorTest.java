/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.networkstack.tethering;

import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.TetheringManager.CONNECTIVITY_SCOPE_GLOBAL;
import static android.net.TetheringManager.CONNECTIVITY_SCOPE_LOCAL;
import static android.net.TetheringManager.TETHERING_ETHERNET;
import static android.net.TetheringManager.TETHERING_USB;
import static android.net.TetheringManager.TETHERING_WIFI;
import static android.net.TetheringManager.TETHERING_WIFI_P2P;
import static android.net.ip.IpServer.CMD_NOTIFY_PREFIX_CONFLICT;

import static com.android.net.module.util.PrivateAddressCoordinator.TETHER_FORCE_RANDOM_PREFIX_BASE_SELECTION;
import static com.android.networkstack.tethering.util.PrefixUtils.asIpPrefix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.ip.IpServer;
import android.os.IBinder;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.net.module.util.IIpv4PrefixRequest;
import com.android.net.module.util.PrivateAddressCoordinator;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
@SmallTest
public final class PrivateAddressCoordinatorTest {
    private static final String TEST_IFNAME = "test0";

    @Mock private IpServer mHotspotIpServer;
    @Mock private IpServer mLocalHotspotIpServer;
    @Mock private IpServer mUsbIpServer;
    @Mock private IpServer mEthernetIpServer;
    @Mock private IpServer mWifiP2pIpServer;
    @Mock private IpServer mWifiP2pIpServer2;
    @Mock private Context mContext;
    @Mock private ConnectivityManager mConnectivityMgr;
    @Mock private PrivateAddressCoordinator.Dependencies mDeps;

    private PrivateAddressCoordinator mPrivateAddressCoordinator;
    private final LinkAddress mBluetoothAddress = new LinkAddress("192.168.44.1/24");
    private final LinkAddress mLegacyWifiP2pAddress = new LinkAddress("192.168.49.1/24");
    private final Network mWifiNetwork = new Network(1);
    private final Network mMobileNetwork = new Network(2);
    private final Network mVpnNetwork = new Network(3);
    private final Network mMobileNetwork2 = new Network(4);
    private final Network mMobileNetwork3 = new Network(5);
    private final Network mMobileNetwork4 = new Network(6);
    private final Network mMobileNetwork5 = new Network(7);
    private final Network mMobileNetwork6 = new Network(8);
    private final Network[] mAllNetworks = {mMobileNetwork, mWifiNetwork, mVpnNetwork,
            mMobileNetwork2, mMobileNetwork3, mMobileNetwork4, mMobileNetwork5, mMobileNetwork6};
    private final ArrayList<IpPrefix> mTetheringPrefixes = new ArrayList<>(Arrays.asList(
            new IpPrefix("192.168.0.0/16"),
            new IpPrefix("172.16.0.0/12"),
            new IpPrefix("10.0.0.0/8")));

    private void setUpIpServer(IpServer ipServer, int interfaceType) throws Exception {
        when(ipServer.interfaceType()).thenReturn(interfaceType);
        final IIpv4PrefixRequest request = mock(IIpv4PrefixRequest.class);
        when(ipServer.getIpv4PrefixRequest()).thenReturn(request);
        when(request.asBinder()).thenReturn(mock(IBinder.class));
        doAnswer(
                        invocation -> {
                            ipServer.sendMessage(CMD_NOTIFY_PREFIX_CONFLICT);
                            return null;
                        })
                .when(request)
                .onIpv4PrefixConflict(any());
    }

    private void setUpIpServers() throws Exception {
<<<<<<< PATCH SET (203668 To support multiple tethering request of same type)
        when(mUsbIpServer.interfaceType()).thenReturn(TETHERING_USB);
        when(mEthernetIpServer.interfaceType()).thenReturn(TETHERING_ETHERNET);
        when(mHotspotIpServer.interfaceType()).thenReturn(TETHERING_WIFI);
        when(mLocalHotspotIpServer.interfaceType()).thenReturn(TETHERING_WIFI);
        when(mWifiP2pIpServer.interfaceType()).thenReturn(TETHERING_WIFI_P2P);
        when(mWifiP2pIpServer2.interfaceType()).thenReturn(TETHERING_WIFI_P2P);
||||||| BASE
        when(mUsbIpServer.interfaceType()).thenReturn(TETHERING_USB);
        when(mEthernetIpServer.interfaceType()).thenReturn(TETHERING_ETHERNET);
        when(mHotspotIpServer.interfaceType()).thenReturn(TETHERING_WIFI);
        when(mLocalHotspotIpServer.interfaceType()).thenReturn(TETHERING_WIFI);
        when(mWifiP2pIpServer.interfaceType()).thenReturn(TETHERING_WIFI_P2P);
=======
        setUpIpServer(mUsbIpServer, TETHERING_USB);
        setUpIpServer(mEthernetIpServer, TETHERING_ETHERNET);
        setUpIpServer(mHotspotIpServer, TETHERING_WIFI);
        setUpIpServer(mLocalHotspotIpServer, TETHERING_WIFI);
        setUpIpServer(mWifiP2pIpServer, TETHERING_WIFI_P2P);
>>>>>>> BASE      (729914 Merge "Skip NetworkStatsBinderTest if the binder interface i)
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mContext.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(mConnectivityMgr);
        when(mContext.getSystemService(ConnectivityManager.class)).thenReturn(mConnectivityMgr);
        when(mConnectivityMgr.getAllNetworks()).thenReturn(mAllNetworks);
        setUpIpServers();
        mPrivateAddressCoordinator =
                spy(new PrivateAddressCoordinator(mConnectivityMgr::getAllNetworks, mDeps));
    }

    private LinkAddress requestStickyDownstreamAddress(final IpServer ipServer, int scope)
            throws Exception {
        final LinkAddress address =
                mPrivateAddressCoordinator.requestStickyDownstreamAddress(
                        ipServer.interfaceType(), scope, ipServer.getIpv4PrefixRequest());
        when(ipServer.getAddress()).thenReturn(address);
        return address;
    }

    private LinkAddress requestDownstreamAddress(final IpServer ipServer) throws Exception {
        final LinkAddress address =
                mPrivateAddressCoordinator.requestDownstreamAddress(
                        ipServer.getIpv4PrefixRequest());
        when(ipServer.getAddress()).thenReturn(address);
        return address;
    }

    private void releaseDownstream(final IpServer ipServer) {
        mPrivateAddressCoordinator.releaseDownstream(ipServer.getIpv4PrefixRequest());
    }

    private void updateUpstreamPrefix(UpstreamNetworkState ns) {
        mPrivateAddressCoordinator.updateUpstreamPrefix(
                ns.linkProperties, ns.networkCapabilities, ns.network);
    }

    @Test
    public void testRequestDownstreamAddressWithoutUsingLastAddress() throws Exception {
        final IpPrefix bluetoothPrefix = asIpPrefix(mBluetoothAddress);
        final LinkAddress address = requestDownstreamAddress(mHotspotIpServer);
        final IpPrefix hotspotPrefix = asIpPrefix(address);
        assertNotEquals(hotspotPrefix, bluetoothPrefix);

        final LinkAddress newAddress = requestDownstreamAddress(mHotspotIpServer);
        final IpPrefix newHotspotPrefix = asIpPrefix(newAddress);
        assertNotEquals(hotspotPrefix, newHotspotPrefix);
        assertNotEquals(bluetoothPrefix, newHotspotPrefix);

        final LinkAddress usbAddress = requestDownstreamAddress(mUsbIpServer);
        final IpPrefix usbPrefix = asIpPrefix(usbAddress);
        assertNotEquals(usbPrefix, bluetoothPrefix);
        assertNotEquals(usbPrefix, newHotspotPrefix);

        releaseDownstream(mHotspotIpServer);
        releaseDownstream(mUsbIpServer);
    }

    @Test
    public void testReservedPrefix() throws Exception {
        // - Test bluetooth prefix is reserved.
        when(mPrivateAddressCoordinator.getRandomInt()).thenReturn(
                getSubAddress(mBluetoothAddress.getAddress().getAddress()));
        final LinkAddress hotspotAddress = requestDownstreamAddress(mHotspotIpServer);
        final IpPrefix hotspotPrefix = asIpPrefix(hotspotAddress);
        assertNotEquals(asIpPrefix(mBluetoothAddress), hotspotPrefix);
        releaseDownstream(mHotspotIpServer);

        // - Test previous enabled hotspot prefix(cached prefix) is reserved.
        when(mPrivateAddressCoordinator.getRandomInt()).thenReturn(
                getSubAddress(hotspotAddress.getAddress().getAddress()));
        final LinkAddress usbAddress = requestDownstreamAddress(mUsbIpServer);
        final IpPrefix usbPrefix = asIpPrefix(usbAddress);
        assertNotEquals(asIpPrefix(mBluetoothAddress), usbPrefix);
        assertNotEquals(hotspotPrefix, usbPrefix);
        releaseDownstream(mUsbIpServer);

        // - Test wifi p2p prefix is reserved.
        when(mPrivateAddressCoordinator.getRandomInt()).thenReturn(
                getSubAddress(mLegacyWifiP2pAddress.getAddress().getAddress()));
        final LinkAddress etherAddress = requestDownstreamAddress(mEthernetIpServer);
        final IpPrefix etherPrefix = asIpPrefix(etherAddress);
        assertNotEquals(asIpPrefix(mLegacyWifiP2pAddress), etherPrefix);
        assertNotEquals(asIpPrefix(mBluetoothAddress), etherPrefix);
        assertNotEquals(hotspotPrefix, etherPrefix);
        releaseDownstream(mEthernetIpServer);
    }

    @Test
    public void testMultipleRequestWithSameType() throws Exception {

        //check for the reserved IP address for the P2P
        final LinkAddress address = requestDownstreamAddress(mWifiP2pIpServer,
                CONNECTIVITY_SCOPE_LOCAL, true /* useLastAddress */);
        assertEquals(mLegacyWifiP2pAddress, address);
        mPrivateAddressCoordinator.releaseDownstream(mWifiP2pIpServer);

        //check if the same cached  IP is generated for new P2P request
        final LinkAddress address2 = requestDownstreamAddress(mWifiP2pIpServer,
                CONNECTIVITY_SCOPE_LOCAL, true /* useLastAddress */);
        assertEquals(mLegacyWifiP2pAddress, address2);
        //check if the IP is dynamically generated for the second P2P request
        final LinkAddress address3 = requestDownstreamAddress(mWifiP2pIpServer2,
                CONNECTIVITY_SCOPE_LOCAL, true /* useLastAddress */);
        assertNotEquals(mLegacyWifiP2pAddress, address3);
        mPrivateAddressCoordinator.releaseDownstream(mWifiP2pIpServer);
        mPrivateAddressCoordinator.releaseDownstream(mWifiP2pIpServer2);

        //start again to check the reserved address is coming back
        final LinkAddress address4 = requestDownstreamAddress(mWifiP2pIpServer2,
                CONNECTIVITY_SCOPE_LOCAL, true /* useLastAddress */);
        assertEquals(mLegacyWifiP2pAddress, address4);
        final LinkAddress address5 = requestDownstreamAddress(mWifiP2pIpServer,
                CONNECTIVITY_SCOPE_LOCAL, true /* useLastAddress */);
        assertNotEquals(address3, address5);

        mPrivateAddressCoordinator.releaseDownstream(mWifiP2pIpServer);
        mPrivateAddressCoordinator.releaseDownstream(mWifiP2pIpServer2);

    }

    @Test
    public void testRequestLastDownstreamAddress() throws Exception {
        final LinkAddress hotspotAddress =
                requestStickyDownstreamAddress(mHotspotIpServer, CONNECTIVITY_SCOPE_GLOBAL);

        final LinkAddress usbAddress =
                requestStickyDownstreamAddress(mUsbIpServer, CONNECTIVITY_SCOPE_GLOBAL);

        releaseDownstream(mHotspotIpServer);
        releaseDownstream(mUsbIpServer);

        final LinkAddress newHotspotAddress =
                requestStickyDownstreamAddress(mHotspotIpServer, CONNECTIVITY_SCOPE_GLOBAL);
        assertEquals(hotspotAddress, newHotspotAddress);
        final LinkAddress newUsbAddress =
                requestStickyDownstreamAddress(mUsbIpServer, CONNECTIVITY_SCOPE_GLOBAL);
        assertEquals(usbAddress, newUsbAddress);

        final UpstreamNetworkState wifiUpstream = buildUpstreamNetworkState(mWifiNetwork,
                new LinkAddress("192.168.88.23/16"), null,
                makeNetworkCapabilities(TRANSPORT_WIFI));
        updateUpstreamPrefix(wifiUpstream);
        verify(mHotspotIpServer).sendMessage(IpServer.CMD_NOTIFY_PREFIX_CONFLICT);
        verify(mUsbIpServer).sendMessage(IpServer.CMD_NOTIFY_PREFIX_CONFLICT);
    }

    private UpstreamNetworkState buildUpstreamNetworkState(final Network network,
            final LinkAddress v4Addr, final LinkAddress v6Addr, final NetworkCapabilities cap) {
        final LinkProperties prop = new LinkProperties();
        prop.setInterfaceName(TEST_IFNAME);
        if (v4Addr != null) prop.addLinkAddress(v4Addr);

        if (v6Addr != null) prop.addLinkAddress(v6Addr);

        return new UpstreamNetworkState(prop, cap, network);
    }

    private NetworkCapabilities makeNetworkCapabilities(final int transportType) {
        final NetworkCapabilities cap = new NetworkCapabilities();
        cap.addTransportType(transportType);
        if (transportType == TRANSPORT_VPN) {
            cap.removeCapability(NET_CAPABILITY_NOT_VPN);
        }

        return cap;
    }

    @Test
    public void testChooseDownstreamAddress_noUpstreamConflicts() throws Exception {
        LinkAddress address = new LinkAddress("192.168.42.42/24");
        UpstreamNetworkState ns = buildUpstreamNetworkState(mMobileNetwork, address, null, null);
        updateUpstreamPrefix(ns);
        // try to look for a /24 in upstream that does not conflict with upstream -> impossible.
        assertNull(mPrivateAddressCoordinator.chooseDownstreamAddress(asIpPrefix(address)));

        IpPrefix prefix = new IpPrefix("192.168.0.0/16");
        LinkAddress chosenAddress = mPrivateAddressCoordinator.chooseDownstreamAddress(prefix);
        assertNotNull(chosenAddress);
        assertTrue(prefix.containsPrefix(asIpPrefix(chosenAddress)));
    }

    @Test
<<<<<<< PATCH SET (203668 To support multiple tethering request of same type)
    public void testChooseAvailablePrefix() throws Exception {
        final int randomAddress = 0x8605; // 134.5
        when(mPrivateAddressCoordinator.getRandomInt()).thenReturn(randomAddress);
        final LinkAddress addr0 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        // Check whether return address is prefix 192.168.0.0/16 + subAddress 0.0.134.5.
        assertEquals("Wrong prefix: ", new LinkAddress("192.168.134.5/24"), addr0);
        final UpstreamNetworkState wifiUpstream = buildUpstreamNetworkState(mWifiNetwork,
                new LinkAddress("192.168.134.13/26"), null,
                makeNetworkCapabilities(TRANSPORT_WIFI));
        mPrivateAddressCoordinator.updateUpstreamPrefix(wifiUpstream);

        // Check whether return address is next prefix of 192.168.134.0/24.
        final LinkAddress addr1 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("192.168.135.5/24"), addr1);
        final UpstreamNetworkState wifiUpstream2 = buildUpstreamNetworkState(mWifiNetwork,
                new LinkAddress("192.168.149.16/19"), null,
                makeNetworkCapabilities(TRANSPORT_WIFI));
        mPrivateAddressCoordinator.updateUpstreamPrefix(wifiUpstream2);


        // The conflict range is 128 ~ 159, so the address is 192.168.160.5/24.
        final LinkAddress addr2 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("192.168.160.5/24"), addr2);
        final UpstreamNetworkState mobileUpstream = buildUpstreamNetworkState(mMobileNetwork,
                new LinkAddress("192.168.129.53/18"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        // Update another conflict upstream which is covered by the previous one (but not the first
        // one) and verify whether this would affect the result.
        final UpstreamNetworkState mobileUpstream2 = buildUpstreamNetworkState(mMobileNetwork2,
                new LinkAddress("192.168.170.7/19"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream);
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream2);

        // The conflict range are 128 ~ 159 and 159 ~ 191, so the address is 192.168.192.5/24.
        final LinkAddress addr3 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("192.168.192.5/24"), addr3);
        final UpstreamNetworkState mobileUpstream3 = buildUpstreamNetworkState(mMobileNetwork3,
                new LinkAddress("192.168.188.133/17"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream3);

        // Conflict range: 128 ~ 255. The next available address is 192.168.0.5 because
        // 192.168.134/24 ~ 192.168.255.255/24 is not available.
        final LinkAddress addr4 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("192.168.0.5/24"), addr4);
        final UpstreamNetworkState mobileUpstream4 = buildUpstreamNetworkState(mMobileNetwork4,
                new LinkAddress("192.168.3.59/21"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream4);

        // Conflict ranges: 128 ~ 255 and 0 ~ 7, so the address is 192.168.8.5/24.
        final LinkAddress addr5 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("192.168.8.5/24"), addr5);
        final UpstreamNetworkState mobileUpstream5 = buildUpstreamNetworkState(mMobileNetwork5,
                new LinkAddress("192.168.68.43/21"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream5);

        // Update an upstream that does *not* conflict, check whether return the same address
        // 192.168.5/24.
        final LinkAddress addr6 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertNotEquals("Wrong prefix: ", new LinkAddress("192.168.8.5/24"), addr6);

        final UpstreamNetworkState mobileUpstream6 = buildUpstreamNetworkState(mMobileNetwork6,
                new LinkAddress("192.168.10.97/21"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream6);

        // Conflict ranges: 0 ~ 15 and 128 ~ 255, so the address is 192.168.16.5/24.
        final LinkAddress addr7 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("192.168.16.5/24"), addr7);
        final UpstreamNetworkState mobileUpstream7 = buildUpstreamNetworkState(mMobileNetwork6,
                new LinkAddress("192.168.0.0/17"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream7);

        // Choose prefix from next range(172.16.0.0/12) when no available prefix in 192.168.0.0/16.
        final LinkAddress addr8 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("172.16.134.5/24"), addr8);
    }

    @Test
    public void testChoosePrefixFromDifferentRanges() throws Exception {
        final int randomAddress = 0x1f2b2a; // 31.43.42
        when(mPrivateAddressCoordinator.getRandomInt()).thenReturn(randomAddress);
        final LinkAddress classC1 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        // Check whether return address is prefix 192.168.0.0/16 + subAddress 0.0.43.42.
        assertEquals("Wrong prefix: ", new LinkAddress("192.168.43.42/24"), classC1);
        final UpstreamNetworkState wifiUpstream = buildUpstreamNetworkState(mWifiNetwork,
                new LinkAddress("192.168.88.23/17"), null,
                makeNetworkCapabilities(TRANSPORT_WIFI));
        mPrivateAddressCoordinator.updateUpstreamPrefix(wifiUpstream);
        verifyNotifyConflictAndRelease(mHotspotIpServer);

        // Check whether return address is next address of prefix 192.168.128.0/17.
        final LinkAddress classC2 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("192.168.128.42/24"), classC2);
        final UpstreamNetworkState mobileUpstream = buildUpstreamNetworkState(mMobileNetwork,
                new LinkAddress("192.1.2.3/8"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream);
        verifyNotifyConflictAndRelease(mHotspotIpServer);

        // Check whether return address is under prefix 172.16.0.0/12.
        final LinkAddress classB1 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("172.31.43.42/24"), classB1);
        final UpstreamNetworkState mobileUpstream2 = buildUpstreamNetworkState(mMobileNetwork2,
                new LinkAddress("172.28.123.100/14"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream2);
        verifyNotifyConflictAndRelease(mHotspotIpServer);

        // 172.28.0.0 ~ 172.31.255.255 is not available.
        // Check whether return address is next address of prefix 172.16.0.0/14.
        final LinkAddress classB2 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("172.16.0.42/24"), classB2);

        // Check whether new downstream is next address of address 172.16.0.42/24.
        final LinkAddress classB3 = requestDownstreamAddress(mUsbIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("172.16.1.42/24"), classB3);
        final UpstreamNetworkState mobileUpstream3 = buildUpstreamNetworkState(mMobileNetwork3,
                new LinkAddress("172.16.0.1/24"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream3);
        verifyNotifyConflictAndRelease(mHotspotIpServer);
        verify(mUsbIpServer, never()).sendMessage(IpServer.CMD_NOTIFY_PREFIX_CONFLICT);

        // Check whether return address is next address of prefix 172.16.1.42/24.
        final LinkAddress classB4 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("172.16.2.42/24"), classB4);
        final UpstreamNetworkState mobileUpstream4 = buildUpstreamNetworkState(mMobileNetwork4,
                new LinkAddress("172.16.0.1/13"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream4);
        verifyNotifyConflictAndRelease(mHotspotIpServer);
        verifyNotifyConflictAndRelease(mUsbIpServer);

        // Check whether return address is next address of prefix 172.16.0.1/13.
        final LinkAddress classB5 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("172.24.0.42/24"), classB5);
        // Check whether return address is next address of prefix 172.24.0.42/24.
        final LinkAddress classB6 = requestDownstreamAddress(mUsbIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("172.24.1.42/24"), classB6);
        final UpstreamNetworkState mobileUpstream5 = buildUpstreamNetworkState(mMobileNetwork5,
                new LinkAddress("172.24.0.1/12"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream5);
        verifyNotifyConflictAndRelease(mHotspotIpServer);
        verifyNotifyConflictAndRelease(mUsbIpServer);

        // Check whether return address is prefix 10.0.0.0/8 + subAddress 0.31.43.42.
        final LinkAddress classA1 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("10.31.43.42/24"), classA1);
        // Check whether new downstream is next address of address 10.31.43.42/24.
        final LinkAddress classA2 = requestDownstreamAddress(mUsbIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("10.31.44.42/24"), classA2);
||||||| BASE
    public void testChooseAvailablePrefix() throws Exception {
        final int randomAddress = 0x8605; // 134.5
        when(mPrivateAddressCoordinator.getRandomInt()).thenReturn(randomAddress);
        final LinkAddress addr0 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        // Check whether return address is prefix 192.168.0.0/16 + subAddress 0.0.134.5.
        assertEquals("Wrong prefix: ", new LinkAddress("192.168.134.5/24"), addr0);
        final UpstreamNetworkState wifiUpstream = buildUpstreamNetworkState(mWifiNetwork,
                new LinkAddress("192.168.134.13/26"), null,
                makeNetworkCapabilities(TRANSPORT_WIFI));
        mPrivateAddressCoordinator.updateUpstreamPrefix(wifiUpstream);

        // Check whether return address is next prefix of 192.168.134.0/24.
        final LinkAddress addr1 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("192.168.135.5/24"), addr1);
        final UpstreamNetworkState wifiUpstream2 = buildUpstreamNetworkState(mWifiNetwork,
                new LinkAddress("192.168.149.16/19"), null,
                makeNetworkCapabilities(TRANSPORT_WIFI));
        mPrivateAddressCoordinator.updateUpstreamPrefix(wifiUpstream2);


        // The conflict range is 128 ~ 159, so the address is 192.168.160.5/24.
        final LinkAddress addr2 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("192.168.160.5/24"), addr2);
        final UpstreamNetworkState mobileUpstream = buildUpstreamNetworkState(mMobileNetwork,
                new LinkAddress("192.168.129.53/18"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        // Update another conflict upstream which is covered by the previous one (but not the first
        // one) and verify whether this would affect the result.
        final UpstreamNetworkState mobileUpstream2 = buildUpstreamNetworkState(mMobileNetwork2,
                new LinkAddress("192.168.170.7/19"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream);
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream2);

        // The conflict range are 128 ~ 159 and 159 ~ 191, so the address is 192.168.192.5/24.
        final LinkAddress addr3 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("192.168.192.5/24"), addr3);
        final UpstreamNetworkState mobileUpstream3 = buildUpstreamNetworkState(mMobileNetwork3,
                new LinkAddress("192.168.188.133/17"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream3);

        // Conflict range: 128 ~ 255. The next available address is 192.168.0.5 because
        // 192.168.134/24 ~ 192.168.255.255/24 is not available.
        final LinkAddress addr4 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("192.168.0.5/24"), addr4);
        final UpstreamNetworkState mobileUpstream4 = buildUpstreamNetworkState(mMobileNetwork4,
                new LinkAddress("192.168.3.59/21"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream4);

        // Conflict ranges: 128 ~ 255 and 0 ~ 7, so the address is 192.168.8.5/24.
        final LinkAddress addr5 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("192.168.8.5/24"), addr5);
        final UpstreamNetworkState mobileUpstream5 = buildUpstreamNetworkState(mMobileNetwork5,
                new LinkAddress("192.168.68.43/21"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream5);

        // Update an upstream that does *not* conflict, check whether return the same address
        // 192.168.5/24.
        final LinkAddress addr6 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("192.168.8.5/24"), addr6);
        final UpstreamNetworkState mobileUpstream6 = buildUpstreamNetworkState(mMobileNetwork6,
                new LinkAddress("192.168.10.97/21"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream6);

        // Conflict ranges: 0 ~ 15 and 128 ~ 255, so the address is 192.168.16.5/24.
        final LinkAddress addr7 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("192.168.16.5/24"), addr7);
        final UpstreamNetworkState mobileUpstream7 = buildUpstreamNetworkState(mMobileNetwork6,
                new LinkAddress("192.168.0.0/17"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream7);

        // Choose prefix from next range(172.16.0.0/12) when no available prefix in 192.168.0.0/16.
        final LinkAddress addr8 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("172.16.134.5/24"), addr8);
    }

    @Test
    public void testChoosePrefixFromDifferentRanges() throws Exception {
        final int randomAddress = 0x1f2b2a; // 31.43.42
        when(mPrivateAddressCoordinator.getRandomInt()).thenReturn(randomAddress);
        final LinkAddress classC1 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        // Check whether return address is prefix 192.168.0.0/16 + subAddress 0.0.43.42.
        assertEquals("Wrong prefix: ", new LinkAddress("192.168.43.42/24"), classC1);
        final UpstreamNetworkState wifiUpstream = buildUpstreamNetworkState(mWifiNetwork,
                new LinkAddress("192.168.88.23/17"), null,
                makeNetworkCapabilities(TRANSPORT_WIFI));
        mPrivateAddressCoordinator.updateUpstreamPrefix(wifiUpstream);
        verifyNotifyConflictAndRelease(mHotspotIpServer);

        // Check whether return address is next address of prefix 192.168.128.0/17.
        final LinkAddress classC2 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("192.168.128.42/24"), classC2);
        final UpstreamNetworkState mobileUpstream = buildUpstreamNetworkState(mMobileNetwork,
                new LinkAddress("192.1.2.3/8"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream);
        verifyNotifyConflictAndRelease(mHotspotIpServer);

        // Check whether return address is under prefix 172.16.0.0/12.
        final LinkAddress classB1 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("172.31.43.42/24"), classB1);
        final UpstreamNetworkState mobileUpstream2 = buildUpstreamNetworkState(mMobileNetwork2,
                new LinkAddress("172.28.123.100/14"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream2);
        verifyNotifyConflictAndRelease(mHotspotIpServer);

        // 172.28.0.0 ~ 172.31.255.255 is not available.
        // Check whether return address is next address of prefix 172.16.0.0/14.
        final LinkAddress classB2 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("172.16.0.42/24"), classB2);

        // Check whether new downstream is next address of address 172.16.0.42/24.
        final LinkAddress classB3 = requestDownstreamAddress(mUsbIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("172.16.1.42/24"), classB3);
        final UpstreamNetworkState mobileUpstream3 = buildUpstreamNetworkState(mMobileNetwork3,
                new LinkAddress("172.16.0.1/24"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream3);
        verifyNotifyConflictAndRelease(mHotspotIpServer);
        verify(mUsbIpServer, never()).sendMessage(IpServer.CMD_NOTIFY_PREFIX_CONFLICT);

        // Check whether return address is next address of prefix 172.16.1.42/24.
        final LinkAddress classB4 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("172.16.2.42/24"), classB4);
        final UpstreamNetworkState mobileUpstream4 = buildUpstreamNetworkState(mMobileNetwork4,
                new LinkAddress("172.16.0.1/13"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream4);
        verifyNotifyConflictAndRelease(mHotspotIpServer);
        verifyNotifyConflictAndRelease(mUsbIpServer);

        // Check whether return address is next address of prefix 172.16.0.1/13.
        final LinkAddress classB5 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("172.24.0.42/24"), classB5);
        // Check whether return address is next address of prefix 172.24.0.42/24.
        final LinkAddress classB6 = requestDownstreamAddress(mUsbIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("172.24.1.42/24"), classB6);
        final UpstreamNetworkState mobileUpstream5 = buildUpstreamNetworkState(mMobileNetwork5,
                new LinkAddress("172.24.0.1/12"), null,
                makeNetworkCapabilities(TRANSPORT_CELLULAR));
        mPrivateAddressCoordinator.updateUpstreamPrefix(mobileUpstream5);
        verifyNotifyConflictAndRelease(mHotspotIpServer);
        verifyNotifyConflictAndRelease(mUsbIpServer);

        // Check whether return address is prefix 10.0.0.0/8 + subAddress 0.31.43.42.
        final LinkAddress classA1 = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("10.31.43.42/24"), classA1);
        // Check whether new downstream is next address of address 10.31.43.42/24.
        final LinkAddress classA2 = requestDownstreamAddress(mUsbIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals("Wrong prefix: ", new LinkAddress("10.31.44.42/24"), classA2);
=======
    public void testChooseDownstreamAddress_excludesWellKnownPrefixes() throws Exception {
        IpPrefix prefix = new IpPrefix("192.168.0.0/24");
        assertNull(mPrivateAddressCoordinator.chooseDownstreamAddress(prefix));
        prefix = new IpPrefix("192.168.100.0/24");
        assertNull(mPrivateAddressCoordinator.chooseDownstreamAddress(prefix));
        prefix = new IpPrefix("10.3.0.0/16");
        assertNull(mPrivateAddressCoordinator.chooseDownstreamAddress(prefix));
>>>>>>> BASE      (729914 Merge "Skip NetworkStatsBinderTest if the binder interface i)
    }

    private void verifyNotifyConflictAndRelease(final IpServer ipServer) throws Exception {
        verify(ipServer).sendMessage(CMD_NOTIFY_PREFIX_CONFLICT);
        releaseDownstream(ipServer);
        final int interfaceType = ipServer.interfaceType();
        reset(ipServer);
        setUpIpServer(ipServer, interfaceType);
    }

    private int getSubAddress(final byte... ipv4Address) {
        assertEquals(4, ipv4Address.length);

        int subnet = Byte.toUnsignedInt(ipv4Address[2]);
        return (subnet << 8) + ipv4Address[3];
    }

    private void assertReseveredWifiP2pPrefix() throws Exception {
        LinkAddress address =
                requestStickyDownstreamAddress(mHotspotIpServer, CONNECTIVITY_SCOPE_GLOBAL);
        final IpPrefix hotspotPrefix = asIpPrefix(address);
        final IpPrefix legacyWifiP2pPrefix = asIpPrefix(mLegacyWifiP2pAddress);
        assertNotEquals(legacyWifiP2pPrefix, hotspotPrefix);
        releaseDownstream(mHotspotIpServer);
    }

    @Test
    public void testEnableSapAndLohsConcurrently() throws Exception {
        final LinkAddress hotspotAddress =
                requestStickyDownstreamAddress(mHotspotIpServer, CONNECTIVITY_SCOPE_GLOBAL);
        assertNotNull(hotspotAddress);

        final LinkAddress localHotspotAddress =
                requestStickyDownstreamAddress(mLocalHotspotIpServer, CONNECTIVITY_SCOPE_LOCAL);
        assertNotNull(localHotspotAddress);

        final IpPrefix hotspotPrefix = asIpPrefix(hotspotAddress);
        final IpPrefix localHotspotPrefix = asIpPrefix(localHotspotAddress);
        assertFalse(hotspotPrefix.containsPrefix(localHotspotPrefix));
        assertFalse(localHotspotPrefix.containsPrefix(hotspotPrefix));
    }

    @Test
    public void testStartedPrefixRange() throws Exception {
        when(mDeps.isFeatureEnabled(TETHER_FORCE_RANDOM_PREFIX_BASE_SELECTION)).thenReturn(true);

        startedPrefixBaseTest("192.168.0.0/16", 0);

        startedPrefixBaseTest("192.168.0.0/16", 1);

        startedPrefixBaseTest("192.168.0.0/16", 0xffff);

        startedPrefixBaseTest("172.16.0.0/12", 0x10000);

        startedPrefixBaseTest("172.16.0.0/12", 0x11111);

        startedPrefixBaseTest("172.16.0.0/12", 0xfffff);

        startedPrefixBaseTest("10.0.0.0/8", 0x100000);

        startedPrefixBaseTest("10.0.0.0/8", 0x1fffff);

        startedPrefixBaseTest("10.0.0.0/8", 0xffffff);

        startedPrefixBaseTest("192.168.0.0/16", 0x1000000);
    }

    private void startedPrefixBaseTest(final String expected, final int randomIntForPrefixBase)
            throws Exception {
        mPrivateAddressCoordinator =
                spy(new PrivateAddressCoordinator(mConnectivityMgr::getAllNetworks, mDeps));
        when(mPrivateAddressCoordinator.getRandomInt()).thenReturn(randomIntForPrefixBase);
        final LinkAddress address = requestDownstreamAddress(mHotspotIpServer);
        final IpPrefix prefixBase = new IpPrefix(expected);
        assertTrue(address + " is not part of " + prefixBase,
                prefixBase.containsPrefix(asIpPrefix(address)));

    }
}
