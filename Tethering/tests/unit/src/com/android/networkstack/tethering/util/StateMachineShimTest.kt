/**
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
package com.android.networkstack.tethering.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.util.State
import com.android.networkstack.tethering.util.StateMachineShim.AsyncStateMachine
import com.android.networkstack.tethering.util.SyncStateMachine.StateInfo
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions

@RunWith(AndroidJUnit4::class)
@SmallTest
class StateMachineShimTest {
    private val mState1 = mock(State::class.java)
    private val mState2 = mock(State::class.java)

    @Test
    fun testUsingSyncStateMachine() {
        val syncSM = mock(SyncStateMachine::class.java)
        val shimUsingSyncSM = StateMachineShim(null, syncSM)
        shimUsingSyncSM.start(mState1)
        verify(syncSM).start(mState1)
        verifyNoMoreInteractions(syncSM)

        val allStates = ArrayList<StateInfo>()
        allStates.add(StateInfo(mState1, null))
        allStates.add(StateInfo(mState2, mState1))
        shimUsingSyncSM.addAllStates(allStates)
        verify(syncSM).addAllStates(allStates)
        verifyNoMoreInteractions(syncSM)

        shimUsingSyncSM.transitionTo(mState1)
        verify(syncSM).transitionTo(mState1)
        verifyNoMoreInteractions(syncSM)

        val what = 10
        shimUsingSyncSM.sendMessage(what)
        verify(syncSM).processMessage(what, 0, 0, null)
        verifyNoMoreInteractions(syncSM)
        val obj = Object()
        shimUsingSyncSM.sendMessage(what, obj)
        verify(syncSM).processMessage(what, 0, 0, obj)
        verifyNoMoreInteractions(syncSM)
        val arg1 = 11
        shimUsingSyncSM.sendMessage(what, arg1)
        verify(syncSM).processMessage(what, arg1, 0, null)
        verifyNoMoreInteractions(syncSM)
        val arg2 = 12
        shimUsingSyncSM.sendMessage(what, arg1, arg2, obj)
        verify(syncSM).processMessage(what, arg1, arg2, obj)
        verifyNoMoreInteractions(syncSM)

        shimUsingSyncSM.sendSelfMessage(what, arg1, arg2, obj)
        verify(syncSM).sendSelfMessage(what, arg1, arg2, obj)
        verifyNoMoreInteractions(syncSM)

        shimUsingSyncSM.sendMessageDelayedForAsyncSM(what, 1000 /* delayMillis */)
        verifyNoMoreInteractions(syncSM)
    }

    @Test
    fun testUsingAsyncStateMachine() {
        val asyncSM = mock(AsyncStateMachine::class.java)
        val shimUsingAsyncSM = StateMachineShim(asyncSM, null)
        shimUsingAsyncSM.start(mState1)
        verify(asyncSM).setInitialState(mState1)
        verify(asyncSM).start()
        verifyNoMoreInteractions(asyncSM)

        val allStates = ArrayList<StateInfo>()
        allStates.add(StateInfo(mState1, null))
        allStates.add(StateInfo(mState2, mState1))
        shimUsingAsyncSM.addAllStates(allStates)
        verify(asyncSM).addState(mState1, null)
        verify(asyncSM).addState(mState2, mState1)
        verifyNoMoreInteractions(asyncSM)

        shimUsingAsyncSM.transitionTo(mState1)
        verify(asyncSM).transitionTo(mState1)
        verifyNoMoreInteractions(asyncSM)

        val what = 10
        shimUsingAsyncSM.sendMessage(what)
        verify(asyncSM).sendMessage(what, 0, 0, null)
        verifyNoMoreInteractions(asyncSM)
        val obj = Object()
        shimUsingAsyncSM.sendMessage(what, obj)
        verify(asyncSM).sendMessage(what, 0, 0, obj)
        verifyNoMoreInteractions(asyncSM)
        val arg1 = 11
        shimUsingAsyncSM.sendMessage(what, arg1)
        verify(asyncSM).sendMessage(what, arg1, 0, null)
        verifyNoMoreInteractions(asyncSM)
        val arg2 = 12
        shimUsingAsyncSM.sendMessage(what, arg1, arg2, obj)
        verify(asyncSM).sendMessage(what, arg1, arg2, obj)
        verifyNoMoreInteractions(asyncSM)

        shimUsingAsyncSM.sendSelfMessage(what, arg1, arg2, obj)
        verifyNoMoreInteractions(asyncSM)

        shimUsingAsyncSM.sendMessageDelayedForAsyncSM(what, 1000 /* delayMillis */)
        verify(asyncSM).sendMessageDelayed(what, 1000)
        verifyNoMoreInteractions(asyncSM)
    }
}
