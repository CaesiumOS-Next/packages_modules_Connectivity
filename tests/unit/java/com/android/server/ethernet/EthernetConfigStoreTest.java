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

package com.android.server.ethernet;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.net.IpConfiguration;
import android.util.ArrayMap;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.net.IpConfigStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class EthernetConfigStoreTest {
    EthernetConfigStore mEthernetConfigStore;
    @Mock
    EthernetConfigStore.Dependencies mDeps;
    @Mock
    IpConfigStore mIpConfigStore;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mDeps.getIpConfigStore()).thenReturn(mIpConfigStore);
        when(mDeps.readIpConfigurations(any())).thenReturn(new ArrayMap<String, IpConfiguration>());

        mEthernetConfigStore = new EthernetConfigStore(mDeps);
    }

    private void mockIpConfigRead(String configFilePath,
            ArrayMap<String, IpConfiguration> configs) {
        when(mDeps.readIpConfigurations(configFilePath)).thenReturn(configs);
    }

    private void mockLegacyIpConfigRead(ArrayMap<String, IpConfiguration> configs) {
        mockIpConfigRead(EthernetConfigStore.IP_CONFIG_FILE_LEGACY, configs);
    }

    private void mockApexIpConfigRead(ArrayMap<String, IpConfiguration> configs) {
        mockIpConfigRead(EthernetConfigStore.IP_CONFIG_FILE_APEX, configs);
    }

    @Test
    public void testRead_apexOverwritesLegacyConfig() {
        ArrayMap<String, IpConfiguration> legacyConfig = new ArrayMap<>();
        IpConfiguration legacyIpConfiguration1 = new IpConfiguration();
        IpConfiguration legacyIpConfiguration2 = new IpConfiguration();
        legacyConfig.put("eth0", legacyIpConfiguration1);
        legacyConfig.put("eth1", legacyIpConfiguration2);

        ArrayMap<String, IpConfiguration> apexConfig = new ArrayMap<>();
        IpConfiguration apexIpConfiguration = new IpConfiguration();
        apexConfig.put("eth0", apexIpConfiguration);

        mockLegacyIpConfigRead(legacyConfig);
        mockApexIpConfigRead(apexConfig);

        mEthernetConfigStore.read();

        ArrayMap<String, IpConfiguration> mergedConfig = mEthernetConfigStore.getIpConfigurations();
        assertSame(mergedConfig.get("eth0"), apexIpConfiguration);
        assertNotSame(mergedConfig.get("eth0"), legacyIpConfiguration1);
        assertSame(mergedConfig.get("eth1"), legacyIpConfiguration2);
    }

    @Test
    public void testWrite_writesOnlyToApexConfig() {
        mEthernetConfigStore.write("eth0", new IpConfiguration());
        verify(mIpConfigStore).writeIpConfigurations(eq(EthernetConfigStore.IP_CONFIG_FILE_APEX),
                any());

        verify(mIpConfigStore, never()).writeIpConfigurations(
                eq(EthernetConfigStore.IP_CONFIG_FILE_LEGACY), any());
    }
}
