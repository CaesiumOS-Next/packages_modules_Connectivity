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

import android.app.Instrumentation
import android.os.Build
import android.platform.test.annotations.AppModeFull
import android.system.OsConstants
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.compatibility.common.util.SystemUtil
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.NetworkStackModuleTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@AppModeFull(reason = "ApfTest needs to access to tun/tap interface")
@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@NetworkStackModuleTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
class ApfTest {
    @get:Rule
    val ignoreRule = DevSdkIgnoreRule()

    val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    @Test
    fun testGetApfCapabilities() {
        val capabilities = SystemUtil
                .runShellCommand("cmd network_stack apf wlan0 capabilities").trim()
        val (version, maxLen, packetFormat) = capabilities.split(",").map { it.toInt() }
        assertEquals(4, version)
        assertTrue(maxLen >= 1024)
        assertEquals(OsConstants.ARPHRD_ETHER, packetFormat)
    }
}
