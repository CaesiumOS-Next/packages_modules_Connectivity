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

package com.android.server.connectivity

import android.content.Context
import android.content.res.Resources
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.android.connectivity.resources.R
import com.android.server.CSTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
class SatelliteDataUsageControllerTest : CSTest() {
    private val mContext = mock(Context::class.java)
    private val mDeps = mock(SatelliteDataUsageController.Dependencies::class.java)
    private val mHandler = Handler(Looper.getMainLooper())
    private var mSatelliteDataUsageController =
        SatelliteDataUsageController(mContext, mDeps, mHandler)
    private val resources = mock(Resources::class.java).also {
        doReturn(0).`when`(it).getInteger(
            R.integer.config_satelliteDataUsageLimitAllowedPerUidBytes
        )
        doReturn(0).`when`(it).getInteger(
            R.integer.config_satelliteAllowedDataUsageTimeMeasuredIntervalSecs
        )
    }
    private lateinit var connectivityManager: ConnectivityManager

    @Before
    fun setup() {
        connectivityManager = mock(ConnectivityManager::class.java)
        `when`(context.getSystemService(Context.CONNECTIVITY_SERVICE))
            .thenReturn(connectivityManager)
    }

    @Test
    fun testRegisterSatelliteNetwork() {
        val handlerThread = HandlerThread("SatelliteDataUsageThread")
        handlerThread.start()
        val handler = Handler(handlerThread.looper)
        mSatelliteDataUsageController.registerForSatelliteNetwork()
        val callbackCaptor: ArgumentCaptor<ConnectivityManager.NetworkCallback> =
            ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback::class.java)
        verify(connectivityManager, times(1))
            .registerBestMatchingNetworkCallback(
                any(NetworkRequest::class.java), callbackCaptor.capture(), handler
            )
        val callback: ConnectivityManager.NetworkCallback = callbackCaptor.getValue()
        val testNetwork: Network = mock(Network::class.java)
        // Simulate network available.
        callback.onAvailable(testNetwork)
    }
}