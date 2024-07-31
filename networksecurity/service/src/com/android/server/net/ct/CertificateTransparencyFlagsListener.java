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
import android.content.SharedPreferences;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.Properties;
import android.text.TextUtils;
import android.util.Log;

/** Listener class for the Certificate Transparency Phenotype flags. */
class CertificateTransparencyFlagsListener implements DeviceConfig.OnPropertiesChangedListener {

    private final Context mContext;
    private final SharedPreferences mSharedPreferences;
    private final CertificateTransparencyDownloader mCertificateTransparencyDownloader;

    CertificateTransparencyFlagsListener(Context context) {
        mContext = context;
        mSharedPreferences = Config.getSharedPreferences(context);
        mCertificateTransparencyDownloader = new CertificateTransparencyDownloader(context);
    }

    void initialize() {
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
        int newVersion = DeviceConfig.getInt(DeviceConfig.NAMESPACE_TETHERING, Config.VERSION, -1);
        if (Config.DEBUG) {
            Log.d(TAG, "newContentUrl=" + newContentUrl);
            Log.d(TAG, "newMetadataUrl=" + newMetadataUrl);
            Log.d(TAG, "newVersion=" + newVersion);
        }

        if (newVersion == -1
                || TextUtils.isEmpty(newContentUrl)
                || TextUtils.isEmpty(newMetadataUrl)) {
            return;
        }

        String oldContentUrl = mSharedPreferences.getString(Config.CONTENT_URL, null);
        String oldMetadataUrl = mSharedPreferences.getString(Config.METADATA_URL, null);
        int oldVersion = mSharedPreferences.getInt(Config.VERSION, -1);

        if (newVersion == oldVersion
                && newContentUrl.equals(oldContentUrl)
                && newMetadataUrl.equals(oldMetadataUrl)) {
            Log.i(TAG, "No flag changed, ignoring update");
            return;
        }

        mSharedPreferences
                .edit()
                .putString(Config.CONTENT_URL, newContentUrl)
                .putString(Config.METADATA_URL, newMetadataUrl)
                .putInt(Config.VERSION, newVersion)
                .apply();

        mCertificateTransparencyDownloader.startMetadataDownload(newMetadataUrl);
    }
}
