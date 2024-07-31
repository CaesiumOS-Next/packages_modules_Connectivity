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

import android.app.DownloadManager;
import android.app.DownloadManager.Request;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.util.Log;

/** Helper class to download certificate transparency log files. */
class CertificateTransparencyDownloader extends BroadcastReceiver {

    private static final String TAG = "CertificateTransparency";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final Context mContext;
    private final DownloadManager mDownloadManager;

    CertificateTransparencyDownloader(Context context) {
        mContext = context;
        mDownloadManager = context.getSystemService(DownloadManager.class);
    }

    void initialize() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        mContext.registerReceiver(this, intentFilter, Context.RECEIVER_EXPORTED);
        if (DEBUG) {
            Log.d(TAG, "CertificateTransparencyDownloader initialized successfully");
        }
    }

    void startDownload(String url) {
        if (url == null || "".equals(url)) {
            Log.w(TAG, "Received null or empty url");
            return;
        }

        long downloadId = mDownloadManager.enqueue(new Request(Uri.parse(url)));
        if (downloadId == -1) {
            Log.e(TAG, "Failure requesting download of " + url);
        } else if (DEBUG) {
            Log.d(TAG, "Download of " + url + " with id " + downloadId);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (DEBUG) {
            Log.d(TAG, "Received broadcast with action " + action);
        }

        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) {
            return;
        }

        long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        if (downloadId == -1) {
            Log.e(TAG, "Received ACTION_DOWNLOAD_COMPLETE with invalid download Id");
            return;
        }

        Log.e(TAG, "uriForDownloadedFile=" + mDownloadManager.getUriForDownloadedFile(downloadId));
    }
}
