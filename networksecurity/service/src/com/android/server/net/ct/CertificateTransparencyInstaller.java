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

import libcore.io.IoUtils;
import libcore.io.Streams;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Installer of CT log lists. */
class CertificateTransparencyInstaller {

    private static final String LOGS_DIR = "/data/misc/keychain/ct/";
    private static final String LOGS_DIR_PREFIX = "logs-";

    private final File mLogsDir = new File(LOGS_DIR);
    private final File mCurrentDirSymlink = new File(mLogsDir, "current");
    private final File mMetadataDir = new File(mLogsDir, "metadata/");
    private final File mVersionFile = new File(mMetadataDir, "version");

    private final Context mContext;

    CertificateTransparencyInstaller(Context context) {
        mContext = context;
    }

    void install(Uri newContentUri, String version) throws IOException {
        // To support atomically replacing the old configuration directory with the new there's a
        // bunch of steps. We create a new directory with the logs and then do an atomic update of
        // the current symlink to point to the new directory.
        // 1. Ensure that the update dir exists and is readable
        mLogsDir.mkdir();
        if (!mLogsDir.isDirectory()) {
            throw new IOException("Unable to make directory " + mLogsDir.getCanonicalPath());
        }
        if (!mLogsDir.setReadable(true, false)) {
            throw new IOException("Unable to set permissions on " + mLogsDir.getCanonicalPath());
        }
        File newVersion = new File(mLogsDir, LOGS_DIR_PREFIX + version);
        // 2. Handle the corner case where the new directory already exists.
        if (newVersion.exists()) {
            // If the symlink has already been updated then the update died between steps 7 and 8
            // and so we cannot delete the directory since it is in use. Instead just bump the
            // version and return.
            if (newVersion.getCanonicalPath().equals(mCurrentDirSymlink.getCanonicalPath())) {
                writeUpdate(mVersionFile, new ByteArrayInputStream(version.getBytes()));
                deleteOldLogDirectories();
                return;
            } else {
                deleteContentsAndDir(newVersion);
            }
        }
        try {
            // 3. Create /data/misc/keychain/ct/logs-<new_version>/ .
            newVersion.mkdir();
            if (!newVersion.isDirectory()) {
                throw new IOException("Unable to make directory " + newVersion.getCanonicalPath());
            }
            if (!newVersion.setReadable(true, false)) {
                throw new IOException(
                        "Failed to set " + newVersion.getCanonicalPath() + " readable");
            }

            // 4. Move the log list json file in logs-<new_version>/ .
            File file = new File(newVersion, "log_list.json");
            try (InputStream inputStream =
                            mContext.getContentResolver().openInputStream(newContentUri);
                    FileOutputStream outputStream = new FileOutputStream(file)) {
                Streams.copy(inputStream, outputStream);
            }
            if (!file.setReadable(true, false)) {
                throw new IOException("Failed to set permissions on " + file.getCanonicalPath());
            }

            // 5. Create the temp symlink. We'll rename this to the target symlink to get an atomic
            // update.
            File tempSymlink = new File(mLogsDir, "new_symlink");
            try {
                Os.symlink(newVersion.getCanonicalPath(), tempSymlink.getCanonicalPath());
            } catch (ErrnoException e) {
                throw new IOException("Failed to create symlink", e);
            }

            // 6. Update the symlink target, this is the actual update step.
            tempSymlink.renameTo(mCurrentDirSymlink.getAbsoluteFile());
        } catch (IOException | RuntimeException e) {
            deleteContentsAndDir(newVersion);
            throw e;
        }
        Log.i(TAG, "CT log directory updated to " + newVersion.getAbsolutePath());
        // 7. Update the current version information
        writeUpdate(mVersionFile, new ByteArrayInputStream(version.getBytes()));
        // 8. Cleanup
        deleteOldLogDirectories();
    }

    private void writeUpdate(File file, InputStream inputStream) throws IOException {
        FileOutputStream out = null;
        File tmp = null;
        try {
            // create the parents for the destination file
            File parent = file.getParentFile();
            parent.mkdirs();
            // check that they were created correctly
            if (!parent.exists()) {
                throw new IOException("Failed to create directory " + parent.getCanonicalPath());
            }

            // Give executable permissions to parent folders.
            while (!(parent.equals(mLogsDir))) {
                parent.setExecutable(true, false);
                parent = parent.getParentFile();
            }

            // create the temporary file
            tmp = File.createTempFile("journal", "", mLogsDir);
            // mark tmp -rw-r--r--
            tmp.setReadable(true, false);
            // write to it
            out = new FileOutputStream(tmp);
            Streams.copy(inputStream, out);
            // sync to disk
            out.getFD().sync();
            // atomic rename
            if (!tmp.renameTo(file)) {
                throw new IOException("Failed to atomically rename " + file.getCanonicalPath());
            }
        } finally {
            if (tmp != null) {
                tmp.delete();
            }
            IoUtils.closeQuietly(out);
        }
    }

    private void deleteOldLogDirectories() throws IOException {
        if (!mLogsDir.exists()) {
            return;
        }
        File currentTarget = mCurrentDirSymlink.getCanonicalFile();
        for (File f :
                mLogsDir.listFiles(
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
