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
import android.app.DownloadManager.Query;
import android.app.DownloadManager.Request;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

/** Class to handle downloads for Certificate Transparency. */
public class DownloadHelper {

    private final DownloadManager mDownloadManager;

    @VisibleForTesting
    DownloadHelper(DownloadManager downloadManager) {
        mDownloadManager = downloadManager;
    }

    DownloadHelper(Context context) {
        this(context.getSystemService(DownloadManager.class));
    }

    /**
     * Sends a request to start the download of a provided url.
     *
     * @param url the url to download
     * @return a downloadId if the request was created successfully, -1 otherwise.
     */
    public long startDownload(String url) {
        try {
            return mDownloadManager.enqueue(
                    new Request(Uri.parse(url))
                            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
                            .setRequiresCharging(true));
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Malformed URL", e);
        }
        return -1;
    }

    /**
     * @return true if {@param downloadId} completed successfully.
     */
    public boolean isSuccessful(long downloadId) {
        try (Cursor cursor = mDownloadManager.query(new Query().setFilterById(downloadId))) {
            if (cursor == null) {
                return false;
            }
            if (cursor.moveToFirst()) {
                int status =
                        cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                if (DownloadManager.STATUS_SUCCESSFUL == status) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @return the {@link Uri} of the completed {@param downloadId}, or null if the downloadId did
     *     not complete successfully.
     */
    public Uri getUri(long downloadId) {
        if (downloadId == -1) {
            return null;
        }
        return mDownloadManager.getUriForDownloadedFile(downloadId);
    }
}
