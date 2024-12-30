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

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Tests for the {@link CompatibilityVersion}. */
@RunWith(JUnit4.class)
public class CompatibilityVersionTest {

    private static final String TEST_VERSION = "v123";

    private final File mTestDir =
            InstrumentationRegistry.getInstrumentation().getContext().getFilesDir();
    private final CompatibilityVersion mCompatVersion =
            new CompatibilityVersion(
                    TEST_VERSION, Config.URL_SIGNATURE, Config.URL_LOG_LIST, mTestDir);

    @After
    public void tearDown() {
        mCompatVersion.delete();
    }

    @Test
    public void testCompatibilityVersion_directoryStructure() throws IOException {
        File versionDir = mCompatVersion.getVersionDir();
        assertThat(versionDir.exists()).isFalse();
        assertThat(versionDir.getAbsolutePath()).startsWith(mTestDir.getAbsolutePath());
        assertThat(versionDir.getAbsolutePath()).endsWith(TEST_VERSION);
        File dirSymlink = mCompatVersion.getLogsDirSymlink();
        assertThat(dirSymlink.exists()).isFalse();
        assertThat(dirSymlink.getAbsolutePath()).startsWith(versionDir.getAbsolutePath());
        File logsFile = mCompatVersion.getLogsFile();
        assertThat(logsFile.exists()).isFalse();
        assertThat(logsFile.getAbsolutePath()).startsWith(dirSymlink.getAbsolutePath());
    }

    @Test
    public void testCompatibilityVersion_installSuccessful() throws Exception {
        String version = "i_am_version";
        JSONObject logList = makeLogList(version, "i_am_content");

        try (InputStream inputStream = asStream(logList)) {
            assertThat(mCompatVersion.install(inputStream)).isTrue();
        }

        File versionDir = mCompatVersion.getVersionDir();
        assertThat(versionDir.exists()).isTrue();
        assertThat(versionDir.isDirectory()).isTrue();
        File logsDir = new File(versionDir, CompatibilityVersion.LOGS_DIR_PREFIX + version);
        assertThat(logsDir.exists()).isTrue();
        assertThat(logsDir.isDirectory()).isTrue();
        File logsFile = new File(logsDir, CompatibilityVersion.LOGS_LIST_FILE_NAME);
        assertThat(logsFile.exists()).isTrue();
        assertThat(logsFile.getAbsolutePath()).startsWith(logsDir.getAbsolutePath());
        assertThat(readAsString(logsFile)).isEqualTo(logList.toString());
        File logsSymlink = mCompatVersion.getLogsDirSymlink();
        assertThat(logsSymlink.exists()).isTrue();
        assertThat(logsSymlink.isDirectory()).isTrue();
        assertThat(logsSymlink.getAbsolutePath())
                .startsWith(
                        versionDir.getAbsolutePath()
                                + "/"
                                + CompatibilityVersion.CURRENT_LOGS_DIR_SYMLINK_NAME);
        assertThat(logsSymlink.getCanonicalPath()).isEqualTo(logsDir.getCanonicalPath());
    }

    @Test
    public void testCompatibilityVersion_deleteSuccessfully() throws Exception {
        try (InputStream inputStream = asStream(makeLogList(/* version= */ "123"))) {
            assertThat(mCompatVersion.install(inputStream)).isTrue();
        }

        assertThat(mCompatVersion.delete()).isTrue();

        assertThat(mCompatVersion.getVersionDir().exists()).isFalse();
    }

    @Test
    public void testCompatibilityVersion_invalidLogList() throws Exception {
        try (InputStream inputStream = new ByteArrayInputStream(("not_a_valid_list".getBytes()))) {
            assertThat(mCompatVersion.install(inputStream)).isFalse();
        }

        assertThat(mCompatVersion.getVersionDir().exists()).isFalse();
    }

    @Test
    public void testCompatibilityVersion_incompleteVersionExists_replacesOldVersion()
            throws Exception {
        String existingVersion = "666";
        File existingLogDir =
                new File(
                        mCompatVersion.getVersionDir(),
                        CompatibilityVersion.LOGS_DIR_PREFIX + existingVersion);
        assertThat(existingLogDir.mkdirs()).isTrue();
        File logsListFile = new File(existingLogDir, CompatibilityVersion.LOGS_LIST_FILE_NAME);
        assertThat(logsListFile.createNewFile()).isTrue();

        JSONObject newLogList = makeLogList(existingVersion, "i_am_the_real_content");
        try (InputStream inputStream = asStream(newLogList)) {
            assertThat(mCompatVersion.install(inputStream)).isTrue();
        }

        assertThat(readAsString(logsListFile)).isEqualTo(newLogList.toString());
    }

    @Test
    public void testCompatibilityVersion_versionAlreadyExists_installFails() throws Exception {
        String existingVersion = "666";
        JSONObject existingLogList = makeLogList(existingVersion, "i_was_installed_successfully");
        try (InputStream inputStream = asStream(existingLogList)) {
            assertThat(mCompatVersion.install(inputStream)).isTrue();
        }

        try (InputStream inputStream = asStream(makeLogList(existingVersion, "i_am_ignored"))) {
            assertThat(mCompatVersion.install(inputStream)).isFalse();
        }

        assertThat(readAsString(mCompatVersion.getLogsFile()))
                .isEqualTo(existingLogList.toString());
    }

    private static InputStream asStream(JSONObject logList) throws IOException {
        return new ByteArrayInputStream(logList.toString().getBytes());
    }

    private static JSONObject makeLogList(String version) throws JSONException {
        return new JSONObject().put("version", version);
    }

    private static JSONObject makeLogList(String version, String content) throws JSONException {
        return makeLogList(version).put("content", content);
    }

    private static String readAsString(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return new String(in.readAllBytes());
        }
    }
}
