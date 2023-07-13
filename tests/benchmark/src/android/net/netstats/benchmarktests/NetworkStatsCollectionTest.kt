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
import java.util.Arrays
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.test.assertTrue
import libcore.io.IoUtils
import libcore.testing.io.TestIoUtils
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class NetworkStatsCollectionTest {
    private val DEFAULT_BUFFER_SIZE = 8192
    private val UID_COLLECTION_BUCKECT_DURATION_MS = TimeUnit.HOURS.toMillis(2)
    @Test
    fun testReadCollection_manyUids() {
        val inputStream = getInputStreamForResource(R.raw.netstats_many_uids_zip)
        val zipInputStream = ZipInputStream(inputStream)
        val statsDir = unzipToTempDir(zipInputStream)
        val testFiles = getListForPrefix(statsDir, "uid")
        val collection = NetworkStatsCollection(UID_COLLECTION_BUCKECT_DURATION_MS)
        for (file in testFiles) {
            readFile(file, collection)
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
    private fun getListForPrefix(statsDir: File, prefix: String): ArrayList<File> {
        val list: ArrayList<File> = ArrayList()
        assertTrue(statsDir.exists())
        val files = statsDir.list()
        // TODO: Try sort in reverse to see what is the impact.
        Arrays.sort(files)
        for (name in files) {
            // Skip when prefix doesn't match.
            if (!name.startsWith("$prefix.")) continue
            list.add(File(statsDir, name))
        }
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
