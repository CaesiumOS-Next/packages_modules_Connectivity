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
import android.app.DownloadManager.Query;
import android.app.DownloadManager.Request;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.util.Arrays;

/** Helper class to download certificate transparency log files. */
class CertificateTransparencyDownloader extends BroadcastReceiver {

    private static final String TAG = "CertificateTransparency";

    private final Context mContext;
    // private final File mDownloadDir;
    private final DownloadManager mDownloadManager;

    CertificateTransparencyDownloader(Context context) {
        mContext = context;
        // mDownloadDir = new File(context.getFilesDir(), "ctdownloads");
        mDownloadManager = context.getSystemService(DownloadManager.class);
    }

    void initialize() {
        Log.e(TAG, "initialize broadcast receiver");
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        intentFilter.addAction(DownloadManager.ACTION_DOWNLOAD_COMPLETED);

        mContext.registerReceiver(this, intentFilter, Context.RECEIVER_EXPORTED);
    }

    void startDownload(String url) {
        if (url == null || "".equals(url)) {
            Log.w(TAG, "Received null or empty url");
            return;
        }
        Log.d(TAG, "received new log list url: " + url);

        Uri requestUri = Uri.parse(url);
        Log.d(TAG, "requestUri " + requestUri);
        long downloadId = mDownloadManager.enqueue(new Request(requestUri));
        Log.d(TAG, "download enqueued with id " + downloadId);

        try (Cursor cursor = mDownloadManager.query(new Query().setFilterById(downloadId))) {

            cursor.moveToFirst();
            while (!cursor.isAfterLast()) {

                String[] columnNames = cursor.getColumnNames();
                for (String column : columnNames) {
                    Log.e(TAG, column + "=" + getColumnAsString(cursor, column));
                }
                cursor.moveToNext();
            }
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Received broadcast with action " + intent.getAction());
    }

    private String getColumnAsString(Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        switch (cursor.getType(index)) {
            case Cursor.FIELD_TYPE_STRING:
                return cursor.getString(index);
            case Cursor.FIELD_TYPE_BLOB:
                return Arrays.toString(cursor.getBlob(index));
            case Cursor.FIELD_TYPE_INTEGER:
                return Integer.toString(cursor.getInt(index));
            case Cursor.FIELD_TYPE_FLOAT:
                return Float.toString(cursor.getFloat(index));
            case Cursor.FIELD_TYPE_NULL:
                return "null";
            default:
        }
        return "";
    }
}
