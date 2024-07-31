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

import android.app.DownloadManager;
import android.app.DownloadManager.Query;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.util.Log;

public class DownloadReceiver extends BroadcastReceiver {

    private static final String TAG = "CertificateTransparency";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "Received broadcast with action " + action);

        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) {
            return;
        }

        long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        Log.d(TAG, "Received downloadId " + downloadId);
        if (downloadId == -1) {
            return;
        }

        DownloadManager downloadManager = context.getSystemService(DownloadManager.class);
        try (Cursor cursor = downloadManager.query(new Query().setFilterById(downloadId))) {

            cursor.moveToFirst();
            while (!cursor.isAfterLast()) {

                int status =
                        cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    Log.e(
                            TAG,
                            "successfulDownload at "
                                    + downloadManager.getUriForDownloadedFile(downloadId));
                } else {
                    Log.e(
                            TAG,
                            "download not successful: "
                                    + cursor.getInt(
                                            cursor.getColumnIndexOrThrow(
                                                    DownloadManager.COLUMN_REASON)));
                }

                cursor.moveToNext();
            }
        }
    }
}
