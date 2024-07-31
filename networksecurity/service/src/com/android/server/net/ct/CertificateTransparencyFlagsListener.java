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
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.Properties;
import android.text.TextUtils;
import android.util.Log;

import com.android.modules.utils.build.SdkLevel;

/** Listener class for the Certificate Transparency Phenotype flags. */
class CertificateTransparencyFlagsListener implements DeviceConfig.OnPropertiesChangedListener {

    private final HandlerThread mHandlerThread = new HandlerThread(TAG);

    private final Context mContext;
    private final Handler mHandler;
    private final DataStore mDataStore;
    private final CertificateTransparencyDownloader mCertificateTransparencyDownloader;

    CertificateTransparencyFlagsListener(Context context) {
        mContext = context;
        mHandler = new Handler(mHandlerThread.getLooper());
        mDataStore = new DataStore(Config.PREFERENCES_FILE);
        mCertificateTransparencyDownloader =
                new CertificateTransparencyDownloader(context, mDataStore);
    }

    void initialize() {
        mHandlerThread.start();
        mDataStore.load();
        mCertificateTransparencyDownloader.initialize();
        DeviceConfig.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_TETHERING, mContext.getMainExecutor(), this);
        if (Config.DEBUG) {
            Log.d(TAG, "CertificateTransparencyFlagsListener initialized successfully");
        }
        // TODO: handle property changes triggering on boot before registering this listener.
    }

    @Override
    public void onPropertiesChanged(Properties properties) {
        if (!SdkLevel.isAtLeastV()
                || properties == null
                || !DeviceConfig.NAMESPACE_TETHERING.equals(properties.getNamespace())) {
            return;
        }
        mHandler.post(
                () ->
                        handlePropertiesChanged(
                                properties.getString(Config.VERSION, ""),
                                properties.getString(Config.CONTENT_URL, ""),
                                properties.getString(Config.METADATA_URL, "")));
    }

    private void handlePropertiesChanged(
            String newVersion, String newContentUrl, String newMetadataUrl) {
        if (TextUtils.isEmpty(newVersion)
                || TextUtils.isEmpty(newContentUrl)
                || TextUtils.isEmpty(newMetadataUrl)) {
            return;
        }

        if (Config.DEBUG) {
            Log.d(TAG, "newVersion=" + newVersion);
            Log.d(TAG, "newContentUrl=" + newContentUrl);
            Log.d(TAG, "newMetadataUrl=" + newMetadataUrl);
        }

        String oldVersion = mDataStore.getProperty(Config.VERSION);
        String oldContentUrl = mDataStore.getProperty(Config.CONTENT_URL);
        String oldMetadataUrl = mDataStore.getProperty(Config.METADATA_URL);

        if (TextUtils.equals(newVersion, oldVersion)
                && TextUtils.equals(newContentUrl, oldContentUrl)
                && TextUtils.equals(newMetadataUrl, oldMetadataUrl)) {
            Log.i(TAG, "No flag changed, ignoring update");
            return;
        }

        mDataStore.setProperty(Config.VERSION, newVersion);
        mDataStore.setProperty(Config.CONTENT_URL, newContentUrl);
        mDataStore.setProperty(Config.METADATA_URL, newMetadataUrl);
        mDataStore.store();

        mCertificateTransparencyDownloader.startMetadataDownload(newMetadataUrl);
    }
}
