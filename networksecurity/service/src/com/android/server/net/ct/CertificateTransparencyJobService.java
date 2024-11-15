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

import android.annotation.RequiresApi;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.provider.DeviceConfig.Properties;
import android.util.Log;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;

/** Implementation of the Certificate Transparency job service. */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
public class CertificateTransparencyJobService extends JobService {

    private static final String TAG = "CertificateTransparencyJobService";
    private static final String CERTIFICATE_TRANSPARENCY_JOB_NAMESPACE = "network_security";
    // TODO: replace with CL number.
    private static final int CERTIFICATE_TRANSPARENCY_JOB_ID = 666;

    private final Context mContext;
    private final DataStore mDataStore;
    private final CertificateTransparencyDownloader mCertificateTransparencyDownloader;
    // TODO: remove dependency to flags.
    private final CertificateTransparencyFlagsListener mFlagsListener;
    private final JobInfo mJobInfo;

    /**
     * Creates a new {@link CertificateTransparencyService} object.
     *
     * @param mDataStore
     */
    public CertificateTransparencyJobService(
            Context context,
            DataStore dataStore,
            CertificateTransparencyDownloader certificateTransparencyDownloader, //
            CertificateTransparencyFlagsListener flagsListener) {
        mContext = context;
        mFlagsListener = flagsListener;
        mDataStore = dataStore;
        mCertificateTransparencyDownloader = certificateTransparencyDownloader;

        mJobInfo =
                new JobInfo.Builder(
                                CERTIFICATE_TRANSPARENCY_JOB_ID,
                                new ComponentName(
                                        // "android",
                                        // CertificateTransparencyJobService.class.getName()
                                        context, CertificateTransparencyJobService.class))
                        .setPeriodic(TimeUnit.HOURS.toMillis(24))
                        .setRequiresDeviceIdle(true)
                        .setRequiresBatteryNotLow(true)
                        .build();
    }

    /** Schedule jobs with the {@link JobScheduler}. */
    void initialize() {
        mDataStore.load();
        mCertificateTransparencyDownloader.initialize();

        JobScheduler scheduler =
                mContext.getSystemService(JobScheduler.class)
                        .forNamespace(CERTIFICATE_TRANSPARENCY_JOB_NAMESPACE);
        if (scheduler.schedule(mJobInfo) == JobScheduler.RESULT_FAILURE) {
            Log.e(TAG, "CertificateTransparencyJobService could not be scheduled.");
        } else if (Config.DEBUG) {
            Log.d(TAG, "CertificateTransparencyJobService scheduled successfully.");
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (params.getJobId() != CERTIFICATE_TRANSPARENCY_JOB_ID) {
            Log.e(TAG, "The job id does not match the expected job id.");
            return false;
        }

        Properties properties = new Properties(Config.NAMESPACE_NETWORK_SECURITY, new HashMap<>());
        mFlagsListener.onPropertiesChanged(properties);

        return false; // the download is scheduled, the job can terminate.
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // Nothing to do here, DownloadManager is handling the download on its own.
        return false;
    }
}
