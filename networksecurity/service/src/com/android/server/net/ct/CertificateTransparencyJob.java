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

import android.provider.DeviceConfig.Properties;
import android.util.Log;

import java.time.Duration;
import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Implementation of the Certificate Transparency job */
public class CertificateTransparencyJob {

    private static final String TAG = "CertificateTransparencyJob";

    private static final Duration INITIAL_DELAY = Duration.ofSeconds(10);
    private static final Duration PERIOD = Duration.ofDays(1);
    private static final ScheduledExecutorService EXECUTOR_SERVICE =
            Executors.newSingleThreadScheduledExecutor();

    private final DataStore mDataStore;
    private final CertificateTransparencyDownloader mCertificateTransparencyDownloader;
    // TODO(b/374692404): remove dependency to flags.
    private final CertificateTransparencyFlagsListener mFlagsListener;

    /** Creates a new {@link CertificateTransparencyJob} object. */
    public CertificateTransparencyJob(
            DataStore dataStore,
            CertificateTransparencyDownloader certificateTransparencyDownloader,
            CertificateTransparencyFlagsListener flagsListener) {
        mFlagsListener = flagsListener;
        mDataStore = dataStore;
        mCertificateTransparencyDownloader = certificateTransparencyDownloader;
    }

    void initialize() {
        mDataStore.load();
        mCertificateTransparencyDownloader.initialize();

        EXECUTOR_SERVICE.scheduleAtFixedRate(
                this::startJob,
                INITIAL_DELAY.toMillis(),
                PERIOD.toMillis(),
                /* unit= */ TimeUnit.MILLISECONDS);
        if (Config.DEBUG) {
            Log.d(TAG, "CertificateTransparencyJob scheduled successfully.");
        }
    }

    private void startJob() {
        Properties properties = new Properties(Config.NAMESPACE_NETWORK_SECURITY, new HashMap<>());
        mFlagsListener.onPropertiesChanged(properties);
    }
}
