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

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;

/** Class holding the constants used by the CT feature. */
final class Config {

    static final String TAG = "CertificateTransparency";
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // paths and files names
    static final String PREFERENCES_FILE = "ct-prefs";
    static final String CT_LOGS_DIR = "/data/misc/keychain/ct/";
    static final String CT_LOGS_TEMP_FILE = "log_list_temp-";

    // flags and properties names
    static final String CONTENT_URL = "content_url";
    static final String CONTENT_URL_KEY = "content_url_key";
    static final String METADATA_URL = "metadata_url";
    static final String METADATA_URL_KEY = "metadata_url_key";
    static final String VERSION = "version";

    static SharedPreferences getSharedPreferences(Context context) {
        // File cacheDir = new File(context.getFilesDir(), PREFERENCES_FILE);
        File cacheDir = new File(CT_LOGS_DIR, PREFERENCES_FILE);
        return context.getSharedPreferences(cacheDir.getAbsolutePath(), Context.MODE_PRIVATE);
    }
}
