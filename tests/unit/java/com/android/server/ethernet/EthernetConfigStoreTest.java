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

import static com.android.server.ethernet.EthernetConfigStore.IP_CONFIG_FILE_APEX;
import static com.android.server.ethernet.EthernetConfigStore.IP_CONFIG_FILE_LEGACY;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.mockito.ArgumentCaptor;
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
    public void testReadIpConfiguration_fromApexConfigFile() {
        when(mDeps.doesConfigFileExist(eq(IP_CONFIG_FILE_APEX))).thenReturn(true);

        ArrayMap<String, IpConfiguration> apexConfig = new ArrayMap<>();
        IpConfiguration apexIpConfiguration = new IpConfiguration();
        apexConfig.put("eth0", apexIpConfiguration);

        mockApexIpConfigRead(apexConfig);
        mEthernetConfigStore.read();

        verify(mDeps).readIpConfigurations(eq(IP_CONFIG_FILE_APEX));
        verify(mDeps, never()).readIpConfigurations(eq(IP_CONFIG_FILE_LEGACY));
        verify(mIpConfigStore, never()).writeIpConfigurations(eq(IP_CONFIG_FILE_LEGACY), any());

        final ArrayMap<String, IpConfiguration> config = mEthernetConfigStore.getIpConfigurations();
        assertEquals(config.get("eth0"), apexIpConfiguration);
    }

    @Test
    public void testReadIpConfiguration_fromLegacyConfigFile() {
        when(mDeps.doesConfigFileExist(eq(IP_CONFIG_FILE_APEX))).thenReturn(false);
        when(mDeps.doesConfigFileExist(eq(IP_CONFIG_FILE_LEGACY))).thenReturn(true);

        ArrayMap<String, IpConfiguration> legacyConfig = new ArrayMap<>();
        IpConfiguration legacyIpConfiguration1 = new IpConfiguration();
        IpConfiguration legacyIpConfiguration2 = new IpConfiguration();
        legacyConfig.put("eth0", legacyIpConfiguration1);
        legacyConfig.put("eth1", legacyIpConfiguration2);

        mockLegacyIpConfigRead(legacyConfig);
        mEthernetConfigStore.read();

        final ArgumentCaptor<ArrayMap<String, IpConfiguration>> captor =
                ArgumentCaptor.forClass(ArrayMap.class);
        verify(mDeps).readIpConfigurations(eq(IP_CONFIG_FILE_LEGACY));
        verify(mDeps, never()).readIpConfigurations(eq(IP_CONFIG_FILE_APEX));
        verify(mIpConfigStore).writeIpConfigurations(eq(IP_CONFIG_FILE_APEX), captor.capture());

        final ArrayMap<String, IpConfiguration> config = captor.getValue();
        assertEquals(config.get("eth0"), legacyIpConfiguration1);
        assertEquals(config.get("eth1"), legacyIpConfiguration2);
    }

    @Test
    public void testReadIpConfiguration_fromNonExistingLegacyConfigFile() {
        when(mDeps.doesConfigFileExist(eq(IP_CONFIG_FILE_APEX))).thenReturn(false);
        when(mDeps.doesConfigFileExist(eq(IP_CONFIG_FILE_LEGACY))).thenReturn(false);

        mEthernetConfigStore.read();
        verify(mDeps, never()).readIpConfigurations(anyString());
        verify(mIpConfigStore, never()).writeIpConfigurations(anyString(), any());
    }

    @Test
    public void testWriteIpConfiguration_writesOnlyToApexConfig() {
        mEthernetConfigStore.write("eth0", new IpConfiguration());
        verify(mIpConfigStore).writeIpConfigurations(eq(IP_CONFIG_FILE_APEX), any());
        verify(mIpConfigStore, never()).writeIpConfigurations(eq(IP_CONFIG_FILE_LEGACY), any());
    }
}
