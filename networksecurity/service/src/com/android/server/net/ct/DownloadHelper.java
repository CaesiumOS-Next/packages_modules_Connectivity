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
import android.text.TextUtils;
import android.util.Log;

/** Class to handle downloads for Certificate Transparency. */
final class DownloadHelper {

    private final DownloadManager mDownloadManager;
    private final DataStore mDataStore;

    DownloadHelper(Context context, DataStore dataStore) {
        mDownloadManager = context.getSystemService(DownloadManager.class);
        mDataStore = dataStore;
    }

    void startDownload(String urlKey, String url) {
        if (TextUtils.isEmpty(urlKey) || TextUtils.isEmpty(url)) {
            Log.w(TAG, "Received invalid url or urlKey");
            return;
        }

        long downloadId =
                mDownloadManager.enqueue(
                        new Request(Uri.parse(url))
                                .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
                                .setRequiresCharging(true));
        if (downloadId == -1) {
            Log.e(TAG, "Download request failed for " + url);
            return;
        }

        mDataStore.setPropertyLong(urlKey, downloadId);
        mDataStore.store();
        if (Config.DEBUG) {
            Log.d(TAG, "Download of " + url + " with id " + downloadId);
        }
    }

    boolean isSuccessful(long downloadId) {
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

    boolean isMetadataDownloadId(long downloadId) {
        return mDataStore.getPropertyLong(Config.METADATA_URL_KEY, -1) == downloadId;
    }

    Uri getMetadataDownloadUri() {
        return getUri(mDataStore.getPropertyLong(Config.METADATA_URL_KEY, -1));
    }

    boolean isContentDownloadId(long downloadId) {
        return mDataStore.getPropertyLong(Config.CONTENT_URL_KEY, -1) == downloadId;
    }

    Uri getContentDownloadUri() {
        return getUri(mDataStore.getPropertyLong(Config.CONTENT_URL_KEY, -1));
    }

    private Uri getUri(long downloadId) {
        if (downloadId == -1) {
            return null;
        }
        return mDownloadManager.getUriForDownloadedFile(downloadId);
    }
}
