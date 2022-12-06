/**
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.networkstack.tethering

import android.net.ITetheringEventCallback
import android.net.Network
import android.net.TetherStatesParcel
import android.net.TetheredClient
import android.net.TetheringConfigurationParcel
import android.net.TetheringManager.TETHER_HARDWARE_OFFLOAD_STARTED
import android.os.IBinder

import androidx.test.filters.SmallTest
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verifyNoMoreInteractions

import java.util.ArrayList

@RunWith(AndroidJUnit4::class)
@SmallTest
public class EventCallbacksTrackerTest {
    private val mInOrder by lazy { inOrder(callback1, callback2) }

    val callback1 = mock(ITetheringEventCallback::class.java)
    val callback2 = mock(ITetheringEventCallback::class.java)
    val binder1 = mock(IBinder::class.java)
    val binder2 = mock(IBinder::class.java)
    val callbacksTracker = EventCallbacksTracker()
    val network = Network(1)
    val states = TetherStatesParcel()
    val clients = ArrayList<TetheredClient>()
    val config = TetheringConfigurationParcel()
    val supportedBitMap = 8L

    @Before
    fun setUp() {
        doReturn(binder1).`when`(callback1).asBinder()
        doReturn(binder2).`when`(callback2).asBinder()
    }

    fun verifyCallbacks(verifyCallback1: Boolean = false, verifyCallback2: Boolean = false) {
        callbacksTracker.reportTetheringSupportedChange(supportedBitMap)
        if (verifyCallback1) mInOrder.verify(callback1).onSupportedTetheringTypes(supportedBitMap)
        if (verifyCallback2) mInOrder.verify(callback2).onSupportedTetheringTypes(supportedBitMap)

        callbacksTracker.reportUpstreamChanged(network)
        if (verifyCallback1) mInOrder.verify(callback1).onUpstreamChanged(network)
        if (verifyCallback2) mInOrder.verify(callback2).onUpstreamChanged(network)

        callbacksTracker.reportConfigurationChanged(config)
        if (verifyCallback1) mInOrder.verify(callback1).onConfigurationChanged(config)
        if (verifyCallback2) mInOrder.verify(callback2).onConfigurationChanged(config)

        callbacksTracker.reportTetherStateChanged(states)
        if (verifyCallback1) mInOrder.verify(callback1).onTetherStatesChanged(states)
        if (verifyCallback2) mInOrder.verify(callback2).onTetherStatesChanged(states)

        callbacksTracker.reportTetherClientsChanged(clients)
        if (verifyCallback1) mInOrder.verify(callback1).onTetherClientsChanged(clients)

        callbacksTracker.reportOffloadStatusChanged(TETHER_HARDWARE_OFFLOAD_STARTED)
        if (verifyCallback1) mInOrder.verify(callback1).onOffloadStatusChanged(
                TETHER_HARDWARE_OFFLOAD_STARTED)
        if (verifyCallback2) mInOrder.verify(callback2).onOffloadStatusChanged(
                TETHER_HARDWARE_OFFLOAD_STARTED)

        verifyNoMoreInteractions(callback1, callback2)
    }

    fun addCallback(callback: ITetheringEventCallback, hasListPermission: Boolean) {
        callbacksTracker.add(callback, hasListPermission)
        mInOrder.verify(callback, times(2)).asBinder()
        verifyNoMoreInteractions(callback)
    }

    fun removeCallback(callback: ITetheringEventCallback) {
        callbacksTracker.remove(callback)
        mInOrder.verify(callback, times(2)).asBinder()
        verifyNoMoreInteractions(callback)
    }

    @Test
    fun testEmptyCallback() {
        assertEquals(0, callbacksTracker.getCallbackCount())
        verifyCallbacks()
    }

    @Test
    fun testCallback() {
        addCallback(callback1, true /* hasListPermission*/)
        assertEquals(1, callbacksTracker.getCallbackCount())
        verifyCallbacks(true)

        removeCallback(callback1)
        assertEquals(0, callbacksTracker.getCallbackCount())
        verifyCallbacks()
    }

    @Test
    fun testMultipleCallbacks() {
        addCallback(callback1, true /* hasListPermission*/)
        assertEquals(1, callbacksTracker.getCallbackCount())
        verifyCallbacks(true)

        addCallback(callback2, false /* hasListPermission*/)
        assertEquals(2, callbacksTracker.getCallbackCount())
        verifyCallbacks(true, true)

        removeCallback(callback1)
        assertEquals(1, callbacksTracker.getCallbackCount())
        verifyCallbacks(false, true)

        removeCallback(callback2)
        assertEquals(0, callbacksTracker.getCallbackCount())
        verifyCallbacks()
    }
}
