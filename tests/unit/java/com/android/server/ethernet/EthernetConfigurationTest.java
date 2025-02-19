/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static com.android.testutils.ParcelUtils.assertParcelingIsLossless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.net.InetAddresses;
import android.net.IpConfiguration;
import android.net.LinkAddress;
import android.net.NetworkCapabilities;
import android.net.ProxyInfo;
import android.net.StaticIpConfiguration;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.InetAddress;
import java.util.ArrayList;

@RunWith(AndroidJUnit4.class)
public class EthernetConfigurationTest {
    private IpConfiguration mIpConfiguration;
    private NetworkCapabilities mNetworkCapabilities;

    @Before
    public void setUp() {
        LinkAddress linkAddr = new LinkAddress("192.168.1.100/25");
        InetAddress gateway = InetAddresses.parseNumericAddress("192.168.1.1");
        InetAddress dns1 = InetAddresses.parseNumericAddress("8.8.8.8");
        InetAddress dns2 = InetAddresses.parseNumericAddress("8.8.4.4");
        ArrayList<InetAddress> dnsServers = new ArrayList<>();
        dnsServers.add(dns1);
        dnsServers.add(dns2);
        StaticIpConfiguration staticIpConfig = new StaticIpConfiguration.Builder()
                .setIpAddress(linkAddr)
                .setGateway(gateway)
                .setDnsServers(dnsServers)
                .build();
        ProxyInfo proxy = ProxyInfo.buildDirectProxy("test", 8888);

        mIpConfiguration = new IpConfiguration.Builder()
                .setStaticIpConfiguration(staticIpConfig)
                .setHttpProxy(proxy)
                .build();

        mNetworkCapabilities = new NetworkCapabilities.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build();
    }

    private void assertIpConfigurationEqual(IpConfiguration source, IpConfiguration target) {
        assertEquals(source.getIpAssignment(), target.getIpAssignment());
        assertEquals(source.getProxySettings(), target.getProxySettings());
        assertEquals(source.getHttpProxy(), target.getHttpProxy());
        assertEquals(source.getStaticIpConfiguration(), target.getStaticIpConfiguration());
    }

    private void assertNetworkCapabilitiesEqual(
            NetworkCapabilities source, NetworkCapabilities target) {
        assertTrue(source.equalsNetCapabilities(target));
    }

    @Test
    public void testDefaultConstructor() {
        EthernetConfiguration config = new EthernetConfiguration();
        assertNotNull(config.getIpConfiguration());
        assertNotNull(config.getNetworkCapabilities());
    }

    @Test
    public void testConstructor() {
        EthernetConfiguration config =
                new EthernetConfiguration(mIpConfiguration, mNetworkCapabilities);
        assertIpConfigurationEqual(config.getIpConfiguration(), mIpConfiguration);
        assertNetworkCapabilitiesEqual(config.getNetworkCapabilities(), mNetworkCapabilities);
    }

    @Test
    public void testConstructWithBuilder() {
        EthernetConfiguration config =
                new EthernetConfiguration.Builder().setIpConfiguration(mIpConfiguration)
                .setNetworkCapabilities(mNetworkCapabilities).build();
        assertIpConfigurationEqual(config.getIpConfiguration(), mIpConfiguration);
        assertNetworkCapabilitiesEqual(config.getNetworkCapabilities(), mNetworkCapabilities);
    }

    @Test
    public void testCopyConstructor() {
        EthernetConfiguration config1 =
                new EthernetConfiguration.Builder().setIpConfiguration(mIpConfiguration)
                        .setNetworkCapabilities(mNetworkCapabilities).build();
        EthernetConfiguration config2 = new EthernetConfiguration(config1);
        assertTrue(config1.equals(config2));
    }

    @Test
    public void testParcel() {
        final EthernetConfiguration config = new EthernetConfiguration();
        assertParcelingIsLossless(config);
    }
}
