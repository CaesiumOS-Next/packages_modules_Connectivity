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

package com.android.server

import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK
import android.os.Build
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFailsWith

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
class CSLocalAgentTests : CSTest() {
    @Test
    fun testBadAgents() {
        assertFailsWith<IllegalArgumentException> {
            Agent(nc = NetworkCapabilities.Builder()
                    .addCapability(NET_CAPABILITY_LOCAL_NETWORK)
                    .build(),
                    lnc = null)
        }
        assertFailsWith<IllegalArgumentException> {
            Agent(nc = NetworkCapabilities.Builder()
                    .addCapability(NET_CAPABILITY_LOCAL_NETWORK)
                    .build(),
                    lnc = null)
        }
    }
}
