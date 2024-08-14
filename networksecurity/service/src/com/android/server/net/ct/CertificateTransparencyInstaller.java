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

import android.content.Context;
import android.net.Uri;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import libcore.io.Streams;

import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Installer of CT log lists. */
class CertificateTransparencyInstaller {

    private static final String LOGS_DIR_PREFIX = "logs-";
    private static final String LOG_LIST_FILE_NAME = "log_list.json";

    private final File mCTDir = new File("/data/misc/keychain/ct/");
    private final File mCurrentDirSymlink = new File(mCTDir, "current");

    private final Context mContext;

    CertificateTransparencyInstaller(Context context) {
        mContext = context;
    }

    void install(Uri newContentUri, String version) throws IOException {
        // To support atomically replacing the old configuration directory with the new there's a
        // bunch of steps. We create a new directory with the logs and then do an atomic update of
        // the current symlink to point to the new directory.
        // 1. Ensure that the update dir exists and is readable
        mCTDir.mkdir();
        if (!mCTDir.isDirectory()) {
            throw new IOException("Unable to make directory " + mCTDir.getCanonicalPath());
        }
        if (!mCTDir.setReadable(true, false)) {
            throw new IOException("Unable to set permissions on " + mCTDir.getCanonicalPath());
        }
        File newLogsDir = new File(mCTDir, LOGS_DIR_PREFIX + version);
        // 2. Handle the corner case where the new directory already exists.
        if (newLogsDir.exists()) {
            // If the symlink has already been updated then the update died between steps 7 and 8
            // and so we cannot delete the directory since it is in use.
            if (newLogsDir.getCanonicalPath().equals(mCurrentDirSymlink.getCanonicalPath())) {
                deleteOldLogDirectories();
                return;
            } else {
                deleteContentsAndDir(newLogsDir);
            }
        }
        try {
            // 3. Create /data/misc/keychain/ct/logs-<new_version>/ .
            newLogsDir.mkdir();
            if (!newLogsDir.isDirectory()) {
                throw new IOException("Unable to make directory " + newLogsDir.getCanonicalPath());
            }
            if (!newLogsDir.setReadable(true, false)) {
                throw new IOException(
                        "Failed to set " + newLogsDir.getCanonicalPath() + " readable");
            }

            // 4. Move the log list json file in logs-<new_version>/ .
            File logListFile = new File(newLogsDir, LOG_LIST_FILE_NAME);
            try (InputStream inputStream =
                            mContext.getContentResolver().openInputStream(newContentUri);
                    FileOutputStream outputStream = new FileOutputStream(logListFile)) {
                Streams.copy(inputStream, outputStream);
            }
            if (!logListFile.setReadable(true, false)) {
                throw new IOException(
                        "Failed to set permissions on " + logListFile.getCanonicalPath());
            }

            // 5. Create temp symlink. We rename this to the target symlink to get an atomic update.
            File tempSymlink = new File(mCTDir, "new_symlink");
            try {
                Os.symlink(newLogsDir.getCanonicalPath(), tempSymlink.getCanonicalPath());
            } catch (ErrnoException e) {
                throw new IOException("Failed to create symlink", e);
            }

            // 6. Update the symlink target, this is the actual update step.
            tempSymlink.renameTo(mCurrentDirSymlink.getAbsoluteFile());
        } catch (IOException | RuntimeException e) {
            deleteContentsAndDir(newLogsDir);
            throw e;
        }
        Log.i(TAG, "CT log directory updated to " + newLogsDir.getAbsolutePath());
        // 7. Cleanup
        deleteOldLogDirectories();
    }

    private void deleteOldLogDirectories() throws IOException {
        if (!mCTDir.exists()) {
            return;
        }
        File currentTarget = mCurrentDirSymlink.getCanonicalFile();
        for (File f :
                mCTDir.listFiles(
                        new FileFilter() {
                            @Override
                            public boolean accept(File file) {
                                return !currentTarget.equals(file)
                                        && file.getName().startsWith(LOGS_DIR_PREFIX);
                            }
                        })) {
            deleteContentsAndDir(f);
        }
    }

    private static boolean deleteContentsAndDir(File dir) {
        if (deleteContents(dir)) {
            return dir.delete();
        } else {
            return false;
        }
    }

    private static boolean deleteContents(File dir) {
        File[] files = dir.listFiles();
        boolean success = true;
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    success &= deleteContents(file);
                }
                if (!file.delete()) {
                    Log.w(TAG, "Failed to delete " + file);
                    success = false;
                }
            }
        }
        return success;
    }
}
