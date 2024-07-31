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
package com.android.server.net.ct;

import static com.android.server.net.ct.Config.TAG;

import android.content.Context;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.Properties;
import android.text.TextUtils;
import android.util.Log;

/** Listener class for the Certificate Transparency Phenotype flags. */
class CertificateTransparencyFlagsListener implements DeviceConfig.OnPropertiesChangedListener {

    private final Context mContext;
    private final DataStore mDataStore;
    private final CertificateTransparencyDownloader mCertificateTransparencyDownloader;

    CertificateTransparencyFlagsListener(Context context) {
        mContext = context;
        mDataStore = new DataStore(Config.PREFERENCES_FILE);
        mCertificateTransparencyDownloader =
                new CertificateTransparencyDownloader(context, mDataStore);
    }

    void initialize() {
        mDataStore.load();
        mCertificateTransparencyDownloader.initialize();
        DeviceConfig.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_TETHERING, mContext.getMainExecutor(), this);
        if (Config.DEBUG) {
            Log.d(TAG, "CertificateTransparencyFlagsListener initialized successfully");
        }
    }

    @Override
    public void onPropertiesChanged(Properties properties) {
        if (!DeviceConfig.NAMESPACE_TETHERING.equals(properties.getNamespace())) {
            return;
        }

        String newContentUrl =
                DeviceConfig.getString(DeviceConfig.NAMESPACE_TETHERING, Config.CONTENT_URL, "");
        String newMetadataUrl =
                DeviceConfig.getString(DeviceConfig.NAMESPACE_TETHERING, Config.METADATA_URL, "");
        String newVersion =
                DeviceConfig.getString(DeviceConfig.NAMESPACE_TETHERING, Config.VERSION, "");
        if (Config.DEBUG) {
            Log.d(TAG, "newContentUrl=" + newContentUrl);
            Log.d(TAG, "newMetadataUrl=" + newMetadataUrl);
            Log.d(TAG, "newVersion=" + newVersion);
        }

        if (TextUtils.isEmpty(newVersion)
                || TextUtils.isEmpty(newContentUrl)
                || TextUtils.isEmpty(newMetadataUrl)) {
            return;
        }

        String oldContentUrl = mDataStore.getProperty(Config.CONTENT_URL);
        String oldMetadataUrl = mDataStore.getProperty(Config.METADATA_URL);
        String oldVersion = mDataStore.getProperty(Config.VERSION);

        if (TextUtils.equals(newVersion, oldVersion)
                && TextUtils.equals(newContentUrl, oldContentUrl)
                && TextUtils.equals(newMetadataUrl, oldMetadataUrl)) {
            Log.i(TAG, "No flag changed, ignoring update");
            return;
        }

        mDataStore.setProperty(Config.CONTENT_URL, newContentUrl);
        mDataStore.setProperty(Config.METADATA_URL, newMetadataUrl);
        mDataStore.setProperty(Config.VERSION, newVersion);
        mDataStore.store();

        mCertificateTransparencyDownloader.startMetadataDownload(newMetadataUrl);
    }
}
