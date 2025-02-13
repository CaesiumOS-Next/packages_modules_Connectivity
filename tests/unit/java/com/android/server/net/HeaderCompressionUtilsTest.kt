/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.net

import android.os.Build
import com.android.testutils.ConnectivityModuleTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.internal.util.HexDump
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlin.intArrayOf

import org.junit.Test
import org.junit.runner.RunWith

private const val TIMEOUT = 1000L

@ConnectivityModuleTest
@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
class HeaderCompressionUtilsTest {
    private fun hexToBuffer(hex: String): Pair<ByteArray, Int> {
        val arr = HexDump.hexStringToByteArray(hex)
        // Decompression happens in place and needs room.
        return Pair(arr.copyOf(1500), arr.size)
    }

    fun assertBufferEquals(buf: ByteArray, len: Int, expected: String) {
        val cappedBuf = buf.copyOf(len)
        assertThat(cappedBuf).isEqualTo(HexDump.hexStringToByteArray(expected))
    }

    @Test
    fun testHeaderDecompression() {
        val (buf, len) = hexToBuffer("60000102030411fffe800000000000000000000000000001fe800000000000000000000000000002")
        val newLen = HeaderCompressionUtils.decompress6lowpan(buf, len)
        assertBufferEquals(buf, newLen, "60120304000011fffe800000000000000000000000000001fe800000000000000000000000000002")
    }

    @Test
    fun testHeaderCompression() {
        val (buf, len) = hexToBuffer("60120304000011fffe800000000000000000000000000001fe800000000000000000000000000002")
        val newLen = HeaderCompressionUtils.compress6lowpan(buf, len)
        assertBufferEquals(buf, newLen, "60000102030411fffe800000000000000000000000000001fe800000000000000000000000000002")
    }
}
