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

package com.android.testutils

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.notification.RunListener
import org.junit.runner.notification.RunNotifier
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.argThat
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

class DevSdkIgnoreRunnerTest {
    private val cm = mock(ConnectivityManager::class.java)
    private val pm = mock(PackageManager::class.java).also {
        doReturn(true).`when`(it).hasSystemFeature(anyString())
    }
    private val ctx = mock(Context::class.java).also {
        doReturn(cm).`when`(it).getSystemService(ConnectivityManager::class.java)
        doReturn(pm).`when`(it).getPackageManager()
    }
    private val notifier = mock(RunNotifier::class.java)
    private val defaultNetworkMonitor = DevSdkIgnoreRunner.DefaultNetworkRestoreMonitor(
        ctx,
        notifier,
        timeoutMs = 0
    )

    private fun getRunListener(): RunListener {
        val captor = ArgumentCaptor.forClass(RunListener::class.java)
        verify(notifier).addListener(captor.capture())
        return captor.value
    }

    @Test
    fun testDefaultNetworkRestoreMonitor_defaultNetworkRestored() {
        val cap = NetworkCapabilities.Builder()
                .addTransportType(TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()
        doReturn(cap).`when`(cm).getNetworkCapabilities(any())
        defaultNetworkMonitor.init(mock(ConnectUtil::class.java))

        doAnswer {
            val callback = it.getArgument(0) as NetworkCallback
            callback.onCapabilitiesChanged(Network(100), cap)
        }.`when`(cm).registerDefaultNetworkCallback(any())
        val listener = getRunListener()
        listener.testFinished(Description.createTestDescription("testClass", "testMethod"))

        val desc = Description.createSuiteDescription("RestoreDefaultNetwork")
        defaultNetworkMonitor.reportResultAndCleanUp(desc)
        verify(notifier).fireTestStarted(desc)
        verify(notifier, never()).fireTestFailure(any())
        verify(notifier).fireTestFinished(desc)
        verify(notifier).removeListener(listener)
    }

    @Test
    fun testDefaultNetworkRestoreMonitor_testStartWithoutDefaultNetwork() {
        // There is no default network when the tests start
        doReturn(null).`when`(cm).getNetworkCapabilities(any())
        defaultNetworkMonitor.init(mock(ConnectUtil::class.java))

        val listener = getRunListener()
        listener.testFinished(Description.createTestDescription("testClass", "testMethod"))

        val desc = Description.createSuiteDescription("RestoreDefaultNetwork")
        defaultNetworkMonitor.reportResultAndCleanUp(desc)
        verify(notifier).fireTestStarted(desc)
        // fireTestFailure is called
        verify(notifier).fireTestFailure(any())
        verify(notifier).fireTestFinished(desc)
        verify(notifier).removeListener(listener)
    }

    @Test
    fun testDefaultNetworkRestoreMonitor_defaultNetworkNotRestored() {
        val wifiCap = NetworkCapabilities.Builder()
                .addTransportType(TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()
        doReturn(wifiCap).`when`(cm).getNetworkCapabilities(any())
        defaultNetworkMonitor.init(mock(ConnectUtil::class.java))

        val cellCap = NetworkCapabilities.Builder()
                .addTransportType(TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()
        doAnswer {
            val callback = it.getArgument(0) as NetworkCallback
            callback.onCapabilitiesChanged(Network(100), cellCap)
        }.`when`(cm).registerDefaultNetworkCallback(any())
        val listener = getRunListener()
        listener.testFinished(Description.createTestDescription("testClass", "testMethod"))

        val desc = Description.createSuiteDescription("RestoreDefaultNetwork")
        defaultNetworkMonitor.reportResultAndCleanUp(desc)
        verify(notifier).fireTestStarted(desc)
        // fireTestFailure is called with method name
        verify(
            notifier
        ).fireTestFailure(argThat{failure -> failure.exception.message!!.contains("testMethod")})
        verify(notifier).fireTestFinished(desc)
        verify(notifier).removeListener(listener)
    }
}
