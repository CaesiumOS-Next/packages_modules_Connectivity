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
import android.net.TetheringCallbackStartedParcel
import android.net.TetheringConfigurationParcel
import android.net.TetheringInterface
import android.net.TetheringManager.TETHER_ERROR_INTERNAL_ERROR
import android.net.TetheringManager.TETHER_HARDWARE_OFFLOAD_STARTED
import android.net.TetheringManager.TETHER_HARDWARE_OFFLOAD_STOPPED
import android.os.IBinder

import androidx.test.filters.SmallTest
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verifyNoMoreInteractions

@RunWith(AndroidJUnit4::class)
@SmallTest
public class EventCallbacksTrackerTest {
    val inOrder1 by lazy { inOrder(callback1) }
    val inOrder2 by lazy { inOrder(callback2) }
    val callback1 = mock(ITetheringEventCallback::class.java)
    val callback2 = mock(ITetheringEventCallback::class.java)
    val binder1 = mock(IBinder::class.java)
    val binder2 = mock(IBinder::class.java)
    val callbacksTracker = EventCallbacksTracker()
    var network: Network? = null
    var clients: List<TetheredClient> = emptyList()
    var config = TetheringConfigurationParcel()
    var supportedBitMap = 0L
    var offloadStatus = TETHER_HARDWARE_OFFLOAD_STOPPED
    var available = arrayOf<TetheringInterface>()
    var localOnly = arrayOf<TetheringInterface>()
    var tethered = arrayOf<TetheringInterface>()
    var errored = arrayOf<TetheringInterface>()
    var lastError = IntArray(0)

    fun eventsSetup(
        newNetwork: Network = Network(1),
        newClients: List<TetheredClient> = listOf(mock(TetheredClient::class.java)),
        newConfig: TetheringConfigurationParcel = TetheringConfigurationParcel(),
        newSupportedBitMap: Long = 8L,
        newOffloadStatus: Int = TETHER_HARDWARE_OFFLOAD_STARTED,
        newAvailable: Array<TetheringInterface> = arrayOf(
                mock(TetheringInterface::class.java), mock(TetheringInterface::class.java)),
        newLocalOnly: Array<TetheringInterface> = arrayOf<TetheringInterface>(),
        newTethered: Array<TetheringInterface> = arrayOf(mock(TetheringInterface::class.java)),
        newErrored: Array<TetheringInterface> = arrayOf(mock(TetheringInterface::class.java)),
        newLastError: IntArray = intArrayOf(TETHER_ERROR_INTERNAL_ERROR)
    ) {
        network = newNetwork
        clients = newClients
        config = newConfig
        supportedBitMap = newSupportedBitMap
        offloadStatus = newOffloadStatus
        available = newAvailable
        localOnly = newLocalOnly
        tethered = newTethered
        errored = newErrored
        lastError = newLastError
    }

    @Before
    fun setUp() {
        doReturn(binder1).`when`(callback1).asBinder()
        doReturn(binder2).`when`(callback2).asBinder()
        // Update initial configuration, tethering would update configuration from constructor.
        callbacksTracker.updateConfigurationChanged(config)
    }

    fun getVerifier(callback: ITetheringEventCallback, num: Int = 1): ITetheringEventCallback {
        if (callback == callback1) return inOrder1.verify(callback1, times(num))

        return inOrder2.verify(callback2, times(num))
    }

    fun toIntList(array: IntArray): List<Int> {
        return array.toList()
    }

    fun <T> toList(array: Array<T>): List<T> {
        return array.toList()
    }

    fun verifyStatesChanged(callback: ITetheringEventCallback) {
        val captor = ArgumentCaptor.forClass(TetherStatesParcel::class.java)
        callback.onTetherStatesChanged(captor.capture())

        val parcel = captor.getValue()
        assertArrayEquals(available, parcel.availableList)
        assertArrayEquals(localOnly, parcel.localOnlyList)
        assertArrayEquals(tethered, parcel.tetheredList)
        assertArrayEquals(errored, parcel.erroredIfaceList)
        assertArrayEquals(lastError, parcel.lastErrorList)
    }

    fun verifyCallbacks(verifyCallback1: Boolean = false, verifyCallback2: Boolean = false) {
        callbacksTracker.updateTetheringSupportedChange(supportedBitMap)
        if (verifyCallback1) getVerifier(callback1).onSupportedTetheringTypes(supportedBitMap)
        if (verifyCallback2) getVerifier(callback2).onSupportedTetheringTypes(supportedBitMap)
        assertEquals(supportedBitMap, callbacksTracker.getSupportedTypeBitmap())
        verifyNoMoreInteractions(callback1, callback2)

        callbacksTracker.updateUpstreamChanged(network)
        if (verifyCallback1) getVerifier(callback1).onUpstreamChanged(network)
        if (verifyCallback2) getVerifier(callback2).onUpstreamChanged(network)
        assertEquals(network, callbacksTracker.getTetherUpstream())
        verifyNoMoreInteractions(callback1, callback2)

        callbacksTracker.updateConfigurationChanged(config)
        if (verifyCallback1) getVerifier(callback1).onConfigurationChanged(config)
        if (verifyCallback2) getVerifier(callback2).onConfigurationChanged(config)
        verifyNoMoreInteractions(callback1, callback2)

        callbacksTracker.updateTetherStateChanged(toList(available), toList(localOnly),
                toList(tethered), toList(errored), toIntList(lastError))
        if (verifyCallback1) verifyStatesChanged(getVerifier(callback1))
        if (verifyCallback2) verifyStatesChanged(getVerifier(callback2))
        verifyNoMoreInteractions(callback1, callback2)

        callbacksTracker.updateTetherClientsChanged(clients)
        if (verifyCallback1) getVerifier(callback1).onTetherClientsChanged(clients)
        // callback2 doesn't have list permission.
        verifyNoMoreInteractions(callback1, callback2)

        callbacksTracker.updateOffloadStatusChanged(offloadStatus)
        if (verifyCallback1) getVerifier(callback1).onOffloadStatusChanged(offloadStatus)
        if (verifyCallback2) getVerifier(callback2).onOffloadStatusChanged(offloadStatus)
        assertEquals(offloadStatus, callbacksTracker.getOffloadStatus())
        verifyNoMoreInteractions(callback1, callback2)
    }

    fun addCallback(callback: ITetheringEventCallback, hasListPermission: Boolean) {
        callbacksTracker.add(callback, hasListPermission)
        getVerifier(callback, 2).asBinder()
        val captor = ArgumentCaptor.forClass(TetheringCallbackStartedParcel::class.java)

        // Verify callback last result for new added callback.
        getVerifier(callback).onCallbackStarted(captor.capture())
        val result = captor.getValue()
        assertEquals(supportedBitMap, result.supportedTypes)
        assertEquals(network, result.upstreamNetwork)
        assertEquals(config, result.config)
        if (hasListPermission) {
            assertEquals(clients, result.tetheredClients)
        } else {
            assertEquals(emptyList<TetheredClient>(), result.tetheredClients)
        }
        assertEquals(offloadStatus, result.offloadStatus)
        assertArrayEquals(available, result.states.availableList)
        assertArrayEquals(localOnly, result.states.localOnlyList)
        assertArrayEquals(tethered, result.states.tetheredList)
        assertArrayEquals(errored, result.states.erroredIfaceList)
        assertArrayEquals(lastError, result.states.lastErrorList)

        verifyNoMoreInteractions(callback)
    }

    fun removeCallback(callback: ITetheringEventCallback) {
        callbacksTracker.remove(callback)
        getVerifier(callback, 2).asBinder()
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
        eventsSetup()
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
        eventsSetup()
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
