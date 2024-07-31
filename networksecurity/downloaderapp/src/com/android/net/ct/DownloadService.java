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
package com.android.net.ct;

import android.R;
import android.app.DownloadManager;
import android.app.DownloadManager.Request;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.IBinder;
import android.util.Log;

/** Service class to download CT log list data. */
public class DownloadService extends Service {

    private static final String TAG = "CertificateTransparency";

    private static final String NOTIFICATION_CHANNEL_ID = "CTLogs";
    private static final int NOTIFICATION_ID = 12345;

    private static final NotificationChannel NOTIFICATION_CHANNEL =
            new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Certificate Transparency Logs",
                    NotificationManager.IMPORTANCE_DEFAULT);

    @Override
    public void onCreate() {
        getSystemService(NotificationManager.class).createNotificationChannel(NOTIFICATION_CHANNEL);

        Log.e(TAG, "initialize broadcast receiver");
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        intentFilter.addAction(DownloadManager.ACTION_DOWNLOAD_COMPLETED);

        getApplicationContext()
                .registerReceiver(new DownloadReceiver(), intentFilter, Context.RECEIVER_EXPORTED);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Received intent " + intent);

        startForeground(
                NOTIFICATION_ID,
                new Notification.Builder(this, "CT download service")
                        .setChannelId(NOTIFICATION_CHANNEL_ID)
                        .setSmallIcon(R.drawable.star_on)
                        .build());

        String url = intent.getStringExtra("log_list_data_url");
        if (url == null || "".equals(url)) {
            Log.w(TAG, "received empty url");
            return Service.START_NOT_STICKY;
        }

        long downloadId =
                getSystemService(DownloadManager.class).enqueue(new Request(Uri.parse(url)));
        Log.d(TAG, "download enqueued with id " + downloadId);

        return Service.START_NOT_STICKY;
    }
}
