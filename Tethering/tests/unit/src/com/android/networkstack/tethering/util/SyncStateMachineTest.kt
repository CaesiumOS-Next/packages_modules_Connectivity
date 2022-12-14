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
package com.android.networkstack.tethering.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest

import com.android.networkstack.tethering.util.SyncStateMachine.SyncState

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.spy
import org.mockito.Mockito.verifyNoMoreInteractions

private const val ERROR = 0
private const val SUCCESSFUL = 1
private const val MSG_RESET = 0
private const val MSG_1 = 1
private const val MSG_2 = 2
private const val MSG_3 = 3
private const val MSG_4 = 4
private const val MSG_5 = 5
private const val MSG_6 = 6
private const val ARG_1 = 100
private const val ARG_2 = 200

@RunWith(AndroidJUnit4::class)
@SmallTest
class SynStateMachineTest {
    private val mState1 = spy(TestState(MSG_1))
    private val mState2 = spy(TestState(MSG_2))
    private val mState3 = spy(TestState(MSG_3))
    private val mState4 = spy(TestState(MSG_4))
    private val mState5 = spy(TestState(MSG_5))
    private val mState6 = spy(TestState(MSG_6))
    private val mInOrder = inOrder(mState1, mState2, mState3, mState4, mState5, mState6)
    private val mSM by lazy {
        SyncStateMachine("TestSyncStateMachine", Thread.currentThread(), SUCCESSFUL,
                true /* debug */)
    }

    inner class TestState(
        val expected: Int,
        val error: Boolean = false
    ) : SyncState() {
        override fun enter(lastResult: Int): Int {
            return if (error) ERROR else lastResult
        }

        override fun exit(lastResult: Int): Int {
            return if (error) ERROR else lastResult
        }

        // Control destination state in obj field for testing.
        override fun processMessage(what: Int, arg1: Int, arg2: Int, obj: Any?): Boolean {

            if (what == expected) {
                obj?.let { mSM.transitionTo(it as? SyncState) }
                return true
            }

            return false
        }
    }

    private fun verifyNoMoreInteractions() {
        verifyNoMoreInteractions(mState1, mState2, mState3, mState4, mState5, mState6)
    }

    private fun assertProcessMessageReturns(result: Int, what: Int, toState: SyncState? = null) {
        assertEquals(result, mSM.processMessage(what, ARG_1, ARG_2, toState))
    }

    private fun verifyProcessMessageBy(state: SyncState, what: Int) {
        mInOrder.verify(state).processMessage(eq(what), eq(ARG_1), eq(ARG_2), any())
    }

    @Test
    fun testAddState() {
        mSM.addState(mState1)
        try {
            mSM.addState(mState1)
            fail("Can add duplicated state")
        } catch (e: IllegalStateException) { }

        try {
            mSM.addState(mState3, mState2)
            fail("child state can add before parent state")
        } catch (e: IllegalStateException) { }
        mSM.addState(mState2, mState1)

        mSM.addState(mState3)
        try {
            mSM.addState(mState1, mState3)
            fail("Can add duplicated state")
        } catch (e: IllegalStateException) { }
    }

    private fun verifyStart() {
        mSM.start(mState1)
        mInOrder.verify(mState1).enter(SUCCESSFUL)
        verifyNoMoreInteractions()
    }

    @Test
    fun testInitialState() {
        // mState1
        //    |
        // mState2
        mSM.addState(mState1)
        mSM.addState(mState2, mState1)
        try {
            mSM.start(mState2)
            fail("Initial state must be root state")
        } catch (e: IllegalStateException) { }

        mSM.start(mState1)
        mInOrder.verify(mState1).enter(SUCCESSFUL)
        verifyNoMoreInteractions()
    }

    @Test
    fun testProcessMessage() {
        // mState1
        //    |
        // mState2
        mSM.addState(mState1)
        mSM.addState(mState2, mState1)
        verifyStart()

        assertProcessMessageReturns(SUCCESSFUL, MSG_1)
        verifyProcessMessageBy(mState1, MSG_1)
        verifyNoMoreInteractions()
    }

    @Test
    fun testTwoStates() {
        // mState1 <-initial, mState2
        mSM.addState(mState1)
        mSM.addState(mState2)
        verifyStart()

        // Test transition to mState2
        assertProcessMessageReturns(SUCCESSFUL, MSG_1, mState2)
        verifyProcessMessageBy(mState1, MSG_1)
        mInOrder.verify(mState1).exit(SUCCESSFUL)
        mInOrder.verify(mState2).enter(SUCCESSFUL)
        verifyNoMoreInteractions()

        // If set destState to mState2 (current state), no state transition.
        assertProcessMessageReturns(SUCCESSFUL, MSG_2, mState2)
        verifyProcessMessageBy(mState2, MSG_2)
        verifyNoMoreInteractions()
    }

    @Test
    fun testTwoStateTrees() {
        //    mState1 -> initial  mState4
        //    /     \             /     \
        // mState2 mState3     mState5 mState6
        mSM.addState(mState1)
        mSM.addState(mState2, mState1)
        mSM.addState(mState3, mState1)
        mSM.addState(mState4)
        mSM.addState(mState5, mState4)
        mSM.addState(mState6, mState4)
        verifyStart()

        //    mState1 -> current     mState4
        //    /     \                /     \
        // mState2 mState3 -> dest mState5 mState6
        assertProcessMessageReturns(SUCCESSFUL, MSG_1, mState3)
        verifyProcessMessageBy(mState1, MSG_1)
        mInOrder.verify(mState3).enter(SUCCESSFUL)
        verifyNoMoreInteractions()

        //           mState1                     mState4
        //           /     \                     /     \
        // dest <- mState2 mState3 -> current mState5 mState6
        assertProcessMessageReturns(SUCCESSFUL, MSG_1, mState2)
        verifyProcessMessageBy(mState3, MSG_1)
        verifyProcessMessageBy(mState1, MSG_1)
        mInOrder.verify(mState3).exit(SUCCESSFUL)
        mInOrder.verify(mState2).enter(SUCCESSFUL)
        verifyNoMoreInteractions()

        //               mState1          mState4
        //               /     \          /     \
        // current <- mState2 mState3 mState5 mState6 -> dest
        assertProcessMessageReturns(SUCCESSFUL, MSG_2, mState6)
        verifyProcessMessageBy(mState2, MSG_2)
        mInOrder.verify(mState2).exit(SUCCESSFUL)
        mInOrder.verify(mState1).exit(SUCCESSFUL)
        mInOrder.verify(mState4).enter(SUCCESSFUL)
        mInOrder.verify(mState6).enter(SUCCESSFUL)
        verifyNoMoreInteractions()
    }
}
