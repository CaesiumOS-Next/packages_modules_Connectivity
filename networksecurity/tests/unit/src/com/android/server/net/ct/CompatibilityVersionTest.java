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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import android.app.DownloadManager;
import android.app.DownloadManager.Request;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Tests for the {@link CompatibilityVersion}. */
@RunWith(JUnit4.class)
public class CompatibilityVersionTest {

    private static final String TEST_VERSION = "v123";

    @Mock private DownloadManager mDownloadManager;

    private final File mTestDir =
            new File(
                    InstrumentationRegistry.getInstrumentation().getContext().getFilesDir(),
                    "test-dir");

    private File mPropertyFile;
    private CompatibilityVersion mCompatibilityVersion;

    private long mNextDownloadId = 666;

    @Before
    public void setUp() throws IOException {
        MockitoAnnotations.initMocks(this);
        mPropertyFile = File.createTempFile("datastore-test", ".properties");
        mCompatibilityVersion =
                new CompatibilityVersion(
                        TEST_VERSION,
                        Config.URL_SIGNATURE,
                        Config.URL_LOG_LIST,
                        new DownloadHelper(mDownloadManager),
                        new DataStore(mPropertyFile),
                        mTestDir);
        when(mDownloadManager.enqueue(any(Request.class)))
                .thenAnswer(invocation -> mNextDownloadId++);
    }

    @After
    public void tearDown() {
        assertThat(mPropertyFile.delete()).isTrue();
        mCompatibilityVersion.delete();
    }

    // @Test
    // public void testDownloader_startMetadataDownload() {
    //     assertThat(mCompatibilityVersion.hasMetadataDownloadId()).isFalse();
    //     long downloadId = mCompatibilityVersion.startMetadataDownload();

    //     assertThat(mCompatibilityVersion.hasMetadataDownloadId()).isTrue();
    //     assertThat(mCompatibilityVersion.isMetadataDownloadId(downloadId)).isTrue();
    // }

    // @Test
    // public void testDownloader_startContentDownload() {
    //     assertThat(mCompatibilityVersion.hasContentDownloadId()).isFalse();
    //     long downloadId = mCompatibilityVersion.startContentDownload();

    //     assertThat(mCompatibilityVersion.hasContentDownloadId()).isTrue();
    //     assertThat(mCompatibilityVersion.isContentDownloadId(downloadId)).isTrue();
    // }

    @Test
    public void testCompatibilityVersion_directoryStructure() throws IOException {
        File versionDir = mCompatibilityVersion.getVersionDir();
        assertThat(versionDir.exists()).isFalse();
        assertThat(versionDir.getAbsolutePath()).startsWith(mTestDir.getAbsolutePath());
        assertThat(versionDir.getAbsolutePath()).endsWith(TEST_VERSION);
        File dirSymlink = mCompatibilityVersion.getLogsDirSymlink();
        assertThat(dirSymlink.exists()).isFalse();
        assertThat(dirSymlink.getAbsolutePath()).startsWith(versionDir.getAbsolutePath());
        File logsFile = mCompatibilityVersion.getLogsFile();
        assertThat(logsFile.exists()).isFalse();
        assertThat(logsFile.getAbsolutePath()).startsWith(dirSymlink.getAbsolutePath());
    }

    @Test
    public void testCompatibilityVersion_installSuccessful() throws IOException {
        String content = "i_am_compatible";
        String version = "i_am_version";

        try (InputStream inputStream = asStream(content)) {
            assertThat(mCompatibilityVersion.install(inputStream, version)).isTrue();
        }

        File versionDir = mCompatibilityVersion.getVersionDir();
        assertThat(versionDir.exists()).isTrue();
        assertThat(versionDir.isDirectory()).isTrue();
        File logsDir = new File(versionDir, CompatibilityVersion.LOGS_DIR_PREFIX + version);
        assertThat(logsDir.exists()).isTrue();
        assertThat(logsDir.isDirectory()).isTrue();
        File logsFile = new File(logsDir, CompatibilityVersion.LOGS_LIST_FILE_NAME);
        assertThat(logsFile.exists()).isTrue();
        assertThat(logsFile.getAbsolutePath()).startsWith(logsDir.getAbsolutePath());
        assertThat(readAsString(logsFile)).isEqualTo(content);
        File logsSymlink = mCompatibilityVersion.getLogsDirSymlink();
        assertThat(logsSymlink.exists()).isTrue();
        assertThat(logsSymlink.isDirectory()).isTrue();
        assertThat(logsSymlink.getAbsolutePath())
                .startsWith(versionDir.getAbsolutePath() + "/current");
        assertThat(logsSymlink.getCanonicalPath()).isEqualTo(logsDir.getCanonicalPath());
    }

    @Test
    public void testCompatibilityVersion_deleteVersion() throws IOException {
        String version = "123";
        try (InputStream inputStream = asStream("content")) {
            assertThat(mCompatibilityVersion.install(inputStream, version)).isTrue();
        }

        assertThat(mCompatibilityVersion.delete()).isTrue();

        File versionDir = mCompatibilityVersion.getVersionDir();
        assertThat(versionDir.exists()).isFalse();
        File logsDir = new File(versionDir, CompatibilityVersion.LOGS_DIR_PREFIX + version);
        assertThat(logsDir.exists()).isFalse();
        File logsFile = new File(logsDir, CompatibilityVersion.LOGS_LIST_FILE_NAME);
        assertThat(logsFile.exists()).isFalse();
        assertThat(mCompatibilityVersion.getLogsDirSymlink().exists()).isFalse();
    }

    @Test
    public void testCompatibilityVersion_incompleteVersionExists_replacesOldVersion()
            throws IOException {
        String existingVersion = "666";
        File existingLogDir =
                new File(
                        mCompatibilityVersion.getVersionDir(),
                        CompatibilityVersion.LOGS_DIR_PREFIX + existingVersion);
        assertThat(existingLogDir.mkdirs()).isTrue();
        File logsListFile = new File(existingLogDir, CompatibilityVersion.LOGS_LIST_FILE_NAME);
        assertThat(logsListFile.createNewFile()).isTrue();
        writeToFile(logsListFile, "old_stuff");

        String newContent = "i_am_the_real_content";
        try (InputStream inputStream = asStream(newContent)) {
            assertThat(mCompatibilityVersion.install(inputStream, existingVersion)).isTrue();
        }

        assertThat(readAsString(logsListFile)).isEqualTo(newContent);
    }

    @Test
    public void testCompatibilityVersion_versionAlreadyExists_installFails() throws IOException {
        String existingVersion = "666";
        String existingContent = "i_was_already_installed_successfully";
        try (InputStream inputStream = asStream(existingContent)) {
            assertThat(mCompatibilityVersion.install(inputStream, existingVersion)).isTrue();
        }

        try (InputStream inputStream = asStream("i_will_be_ignored")) {
            assertThat(mCompatibilityVersion.install(inputStream, existingVersion)).isFalse();
        }

        assertThat(readAsString(mCompatibilityVersion.getLogsFile())).isEqualTo(existingContent);
    }

    private static InputStream asStream(String string) throws IOException {
        return new ByteArrayInputStream(string.getBytes());
    }

    private static String readAsString(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return new String(in.readAllBytes());
        }
    }

    private static void writeToFile(File file, String string) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(string.getBytes());
        }
    }
}
