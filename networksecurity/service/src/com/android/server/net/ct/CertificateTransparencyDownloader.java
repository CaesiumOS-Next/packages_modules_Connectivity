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

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

/** Helper class to download certificate transparency log files. */
class CertificateTransparencyDownloader extends BroadcastReceiver {

    private final Context mContext;
    private final DownloadHelper mDownloadHelper;
    private final SharedPreferences mSharedPreferences;

    CertificateTransparencyDownloader(Context context) {
        mContext = context;
        mDownloadHelper = new DownloadHelper(context);
        mSharedPreferences = Config.getSharedPreferences(context);
    }

    void initialize() {
        mDownloadHelper.initialize();

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        mContext.registerReceiver(this, intentFilter, Context.RECEIVER_EXPORTED);

        if (Config.DEBUG) {
            Log.d(TAG, "CertificateTransparencyDownloader initialized successfully");
        }
    }

    void startMetadataDownload(String metadataUrl) {
        mDownloadHelper.startDownload(Config.METADATA_URL_KEY, metadataUrl);
    }

    void startContentDownload(String contentUrl) {
        mDownloadHelper.startDownload(Config.CONTENT_URL_KEY, contentUrl);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) {
            Log.w(TAG, "Received unexpected broadcast with action " + action);
            return;
        }

        long completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        if (completedId == -1) {
            Log.e(TAG, "Invalid completed download Id");
            return;
        }

        if (mDownloadHelper.isMetadataDownloadId(completedId)) {
            startContentDownload(mSharedPreferences.getString(Config.CONTENT_URL, null));
            return;
        }

        if (!mDownloadHelper.isContentDownloadId(completedId)) {
            Log.e(TAG, "Download id " + completedId + " is neither metadata nor content.");
            return;
        }

        Uri contentUri = mDownloadHelper.getContentDownloadUri();
        Uri metadataUri = mDownloadHelper.getMetadataDownloadUri();
        if (contentUri == null || metadataUri == null) {
            Log.e(TAG, "Invalid URIs");
            return;
        }

        install(contentUri, mSharedPreferences.getInt(Config.VERSION, -1));
        // TODO: delete temp files.
    }

    // private verify(Uri fileUri) {
    //     try (InputStream input = mContext.getContentResolver().openInputStream(fileUri)) {

    //         byte[] content = Streams.readFully(input);
    //     }
    //     Log.i(TAG, "File written to " + destination);
    //     return destination;
    // }

    // private validate(Uri fileUri) {
    //     try (InputStream input = mContext.getContentResolver().openInputStream(uri)) {

    //         byte[] content = Streams.readFully(input);
    //     }
    //     Log.i(TAG, "File written to " + destination);
    //     return destination;
    // }

    private void install(Uri fileUri, int version) {
        Intent installIntent = new Intent();
        installIntent.setAction("android.intent.action.UPDATE_CT_LOGS");
        installIntent.setPackage("android");

        installIntent.putExtra("VERSION", Integer.toString(version));
        installIntent.putExtra("REQUIRED_HASH", "NONE");

        installIntent.setData(fileUri);
        mContext.grantUriPermission("android", fileUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);

        mContext.sendBroadcast(installIntent);
    }
}
