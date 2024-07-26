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

import android.content.Context;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.Properties;
import android.util.Log;

/** Listener class for the Certificate Transparency Phenotype flags. */
class CertificateTransparencyFlagsListener implements DeviceConfig.OnPropertiesChangedListener {

    private static final String TAG = "CertificateTransparency";

    private static final String LOG_LIST_DATA_URL = "log_list_data_url";

    private final Context mContext;

    CertificateTransparencyFlagsListener(Context context) {
        mContext = context;
    }

    void startListeners() {
        DeviceConfig.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_TETHERING, mContext.getMainExecutor(), this);
    }

    @Override
    public void onPropertiesChanged(Properties properties) {
        if (!DeviceConfig.NAMESPACE_TETHERING.equals(properties.getNamespace())) {
            return;
        }
        for (String property : properties.getKeyset()) {
            switch (property) {
                case LOG_LIST_DATA_URL:
                    String url =
                            DeviceConfig.getString(
                                    DeviceConfig.NAMESPACE_TETHERING, LOG_LIST_DATA_URL, "");
                    startDownloadService(url);
                    break;
                default:
                    break;
            }
        }
    }

    private void startDownloadService(String url) {
        if (url == null || "".equals(url)) {
            Log.w(TAG, "Received null or empty intent");
            return;
        }
        Log.d(TAG, "received new log list url: " + url);
        // TODO: start download of list url.
    }
}
