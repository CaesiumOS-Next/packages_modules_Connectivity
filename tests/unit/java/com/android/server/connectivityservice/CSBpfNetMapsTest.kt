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

import android.content.Intent
import android.net.ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED
import android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED
import android.os.Build
import androidx.test.filters.SmallTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRule.IgnoreAfter
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@IgnoreUpTo(Build.VERSION_CODES.S_V2) // Bpf only supports in T+.
class CSBpfNetMapsTest : CSTest() {
    @get:Rule
    val ignoreRule = DevSdkIgnoreRule()

    @IgnoreAfter(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Test
    fun testCSTrackDataSaverBeforeV() {
        doReturn(RESTRICT_BACKGROUND_STATUS_WHITELISTED).`when`(context.networkPolicyManager)
            .getRestrictBackgroundStatus(anyInt())
        context.sendBroadcast(Intent(ACTION_RESTRICT_BACKGROUND_CHANGED))
        waitForIdle()
        verify(bpfNetMaps).setDataSaverEnabled(true)
    }

    // Data Saver Status will be updated from platform code in V+.
    @IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Test
    fun testCSTrackDataSaverAboveU() {
        doReturn(RESTRICT_BACKGROUND_STATUS_WHITELISTED).`when`(context.networkPolicyManager)
            .getRestrictBackgroundStatus(anyInt())
        context.sendBroadcast(Intent(ACTION_RESTRICT_BACKGROUND_CHANGED))
        waitForIdle()
        verify(bpfNetMaps, never()).setDataSaverEnabled(anyBoolean())
    }
}
