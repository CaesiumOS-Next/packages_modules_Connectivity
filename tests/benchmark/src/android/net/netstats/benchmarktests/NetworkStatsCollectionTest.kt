/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.net.benchmarktests

import android.net.NetworkStatsCollection
import androidx.test.InstrumentationRegistry
import com.android.internal.util.FileRotator.Reader
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.test.assertTrue
import libcore.io.IoUtils
import libcore.testing.io.TestIoUtils
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class NetworkStatsCollectionTest {
    private val DEFAULT_BUFFER_SIZE = 8192
    private val UID_COLLECTION_BUCKECT_DURATION_MS = TimeUnit.HOURS.toMillis(2)

    private lateinit var mUidTestFiles: List<File>

    @Before
    fun setUp() {
        val zipInputStream = ZipInputStream(getInputStreamForResource(R.raw.netstats_many_uids_zip))
        mUidTestFiles = getSortedListForPrefix(unzipToTempDir(zipInputStream), "uid")
    }

    // Test results shows the test cases who read the file first will take longer time to execute,
    // and reading time getting shorter each time. Add dummy codes to read file several
    // times to minimize the impact, this cannot live in setUp() since the time spent on the
    // file reading will be attributed to the time spent on the individual test case.
    // Note that this test case MUST be the first one to run, otherwise the time spend on
    // each test case might be not accurate.
    @Test
    fun testDummy() {
        for (i in 1..10) {
            val collection = NetworkStatsCollection(UID_COLLECTION_BUCKECT_DURATION_MS)
            for (file in mUidTestFiles) {
                readFile(file, collection)
            }
        }
    }

    @Test
    fun testReadCollection_manyUids() {
        for (i in 1..10) {
            val collection = NetworkStatsCollection(UID_COLLECTION_BUCKECT_DURATION_MS)
            for (file in mUidTestFiles) {
                readFile(file, collection)
            }
        }
    }

    @Test
    fun testReadCollection_manyUidsInReverseOrder() {
        for (i in 1..10) {
            val collection = NetworkStatsCollection(UID_COLLECTION_BUCKECT_DURATION_MS)
            for (file in mUidTestFiles.asReversed()) {
                readFile(file, collection)
            }
        }
    }

    private fun getInputStreamForResource(resourceId: Int): DataInputStream {
        return DataInputStream(
            InstrumentationRegistry.getContext()
                .getResources().openRawResource(resourceId)
        )
    }

    private fun unzipToTempDir(zis: ZipInputStream): File {
        val statsDir =
            TestIoUtils.createTemporaryDirectory(NetworkStatsCollectionTest::class.simpleName)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var entry: ZipEntry?
        while (zis.nextEntry.also { entry = it } != null) {
            val file = File(statsDir, entry!!.name)
            val fos = FileOutputStream(file)
            val bos = BufferedOutputStream(fos, buffer.size)
            var len: Int
            while (zis.read(buffer).also { len = it } > 0) {
                bos.write(buffer, 0, len)
            }
            bos.close()
        }
        return statsDir
    }

    // List [xt|uid|uid_tag].<start>-<end> files under the given directory.
    private fun getSortedListForPrefix(statsDir: File, prefix: String): ArrayList<File> {
        val list: ArrayList<File> = ArrayList()
        assertTrue(statsDir.exists())
        val files = statsDir.list() ?: arrayOf()
        for (name in files) {
            // Skip when prefix doesn't match.
            if (!name.startsWith("$prefix.")) continue
            list.add(File(statsDir, name))
        }
        list.sort()
        return list
    }

    private fun readFile(file: File, reader: Reader) {
        val fis = FileInputStream(file)
        val bis = BufferedInputStream(fis)
        try {
            reader.read(bis)
        } finally {
            IoUtils.closeQuietly(bis)
        }
    }
}
