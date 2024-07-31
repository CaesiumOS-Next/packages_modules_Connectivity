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
import android.app.DownloadManager.Request;
import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/** Class to handle downloads for Certificate Transparency. */
final class DownloadHelper {

    private final DownloadManager mDownloadManager;
    private final DataStore mDataStore;

    private final File mDownloadDir = new File(Config.CT_LOGS_DIR);

    DownloadHelper(Context context, DataStore dataStore) {
        mDownloadManager = context.getSystemService(DownloadManager.class);
        mDataStore = dataStore;
    }

    void initialize() {
        // in case the directory did not exist
        mDownloadDir.mkdir();
    }

    void startDownload(String urlKey, String url) {
        if (TextUtils.isEmpty(urlKey) || TextUtils.isEmpty(url)) {
            Log.w(TAG, "Received invalid url or urlKey");
            return;
        }

        File tempFile;
        try {
            tempFile = File.createTempFile(Config.CT_LOGS_TEMP_FILE, null, mDownloadDir);
        } catch (IOException e) {
            Log.e(TAG, "Could not create temp file for download", e);
            return;
        }

        long downloadId =
                mDownloadManager.enqueue(
                        new Request(Uri.parse(url)).setDestinationUri(Uri.fromFile(tempFile)));
        if (downloadId == -1) {
            Log.e(TAG, "Download request failed for " + url);
            tempFile.delete();
            return;
        }

        mDataStore.setPropertyLong(urlKey, downloadId);
        mDataStore.store();
        if (Config.DEBUG) {
            Log.d(TAG, "Download of " + url + " with id " + downloadId);
        }
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
