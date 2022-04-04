/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.annotation.Nullable;
import android.content.ApexEnvironment;
import android.net.IpConfiguration;
import android.os.Environment;
import android.util.ArrayMap;

import com.android.server.net.IpConfigStore;

import java.io.File;

/**
 * This class provides an API to store and manage Ethernet network configuration.
 */
public class EthernetConfigStore {
    private static final String FILE_PATH = "/misc/ethernet/ipconfig.txt";
    private static final String IP_CONFIG_FILE_LEGACY = Environment.getDataDirectory() + FILE_PATH;
    private static final String IP_CONFIG_FILE_APEX = ApexEnvironment.getApexEnvironment(
            "com.android.tethering").getDeviceProtectedDataDir() + FILE_PATH;

    private IpConfigStore mStore = new IpConfigStore();
    private final ArrayMap<String, IpConfiguration> mIpConfigurations;
    private IpConfiguration mIpConfigurationForDefaultInterface;
    private final Object mSync = new Object();

    public EthernetConfigStore() {
        mIpConfigurations = new ArrayMap<>(0);
    }

    private static boolean doesConfigFileExist(final String configFilePath) {
        final File config = new File(configFilePath);
        return config.exists();
    }

    public void read() {
        synchronized (mSync) {
            // Attempt to read the IP configuration from apex file path first.
            if (doesConfigFileExist(IP_CONFIG_FILE_APEX)) {
                loadConfigFileLocked(IP_CONFIG_FILE_APEX);
                return;
            }

            // If the config file doesn't exist in the apex file path, attempt to read it from
            // the legacy file path, if config file exists, write the IP configuration to apex
            // config file path, this should just happen on the first boot. New or updated config
            // entries are only written to the apex config file later.
            if (!doesConfigFileExist(IP_CONFIG_FILE_LEGACY)) return;
            loadConfigFileLocked(IP_CONFIG_FILE_LEGACY);
            mStore.writeIpConfigurations(IP_CONFIG_FILE_APEX, mIpConfigurations);
        }
    }

    private void loadConfigFileLocked(final String configFilePath) {
        final ArrayMap<String, IpConfiguration> configs = IpConfigStore.readIpConfigurations(
                configFilePath);

        // This configuration may exist in old file versions when there was only a single active
        // Ethernet interface.
        if (configs.containsKey("0")) {
            mIpConfigurationForDefaultInterface = configs.remove("0");
        }

        mIpConfigurations.putAll(configs);
    }

    public void write(String iface, IpConfiguration config) {
        boolean modified;

        synchronized (mSync) {
            if (config == null) {
                modified = mIpConfigurations.remove(iface) != null;
            } else {
                IpConfiguration oldConfig = mIpConfigurations.put(iface, config);
                modified = !config.equals(oldConfig);
            }

            if (modified) {
                mStore.writeIpConfigurations(IP_CONFIG_FILE_APEX, mIpConfigurations);
            }
        }
    }

    public ArrayMap<String, IpConfiguration> getIpConfigurations() {
        synchronized (mSync) {
            return new ArrayMap<>(mIpConfigurations);
        }
    }

    @Nullable
    public IpConfiguration getIpConfigurationForDefaultInterface() {
        synchronized (mSync) {
            return mIpConfigurationForDefaultInterface == null
                    ? null : new IpConfiguration(mIpConfigurationForDefaultInterface);
        }
    }
}
