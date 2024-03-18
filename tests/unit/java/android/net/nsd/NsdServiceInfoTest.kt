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

package android.net.nsd

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.testutils.ConnectivityModuleTest
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Unit tests for {@link NsdServiceInfo}. */
@SmallTest
@ConnectivityModuleTest
@RunWith(AndroidJUnit4::class)
class NsdServiceInfoTest {
    @Test
    fun testToString_txtRecord() {
        val info = NsdServiceInfo().apply {
            this.setAttribute("abc", byteArrayOf(0xff.toByte(), 0xfe.toByte()))
            this.setAttribute("def", null as String?)
            this.setAttribute("ghi", "猫")
            this.setAttribute("jkl", byteArrayOf(0, 0x21))
        }

        val infoStr = info.toString()

        assertTrue(
                infoStr.contains("txtRecord: {abc=0xFFFE, def=(null), ghi=0xE78CAB, jkl=0x0021}"),
                infoStr)
    }
}
