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

package android.net.cts

import android.net.L2capNetworkSpecifier.COMPRESSION_6LOWPAN
import android.net.L2capNetworkSpecifier
import android.net.MacAddress
import android.os.Build
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.assertParcelingIsLossless
import com.android.testutils.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
class L2capNetworkSpecifierTest {
    @IgnoreUpTo(
    @Test
    fun testParcelUnparcel() {
        val remoteMac = MacAddress.fromString("01:02:03:04:05:06")
        val spec = L2capNetworkSpecifier.Builder.withClientRole()
                .setCompression(COMPRESSION_6LOWPAN)
                .setPsm(42)
                .setRemoteAddress(remoteMac)
                .build()
        assertParcelingIsLossless(spec)
    }
}
