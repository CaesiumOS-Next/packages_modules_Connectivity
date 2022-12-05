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

import android.os.Looper

import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4

import com.android.networkstack.tethering.util.SyncStateMachine.FAILED
import com.android.networkstack.tethering.util.SyncStateMachine.SUCCESSFUL
import com.android.networkstack.tethering.util.SyncStateMachine.SyncState
import com.android.networkstack.tethering.util.SyncStateMachine.UNHANDLED

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.spy
import org.mockito.Mockito.verifyNoMoreInteractions

private const val MSG_RESET = 0
private const val MSG_1 = 1
private const val MSG_2 = 2
private const val MSG_3 = 3
private const val MSG_4 = 4
private const val ARG_1 = 100
private const val ARG_2 = 200
private val OBJ = Object()

@RunWith(AndroidJUnit4::class)
@SmallTest
class SynStateMachineTest {
    private val mSM by lazy {
        // InstrumentationTestRunner prepares a looper, but AndroidJUnitRunner does not.
        // http://b/25897652
        if (Looper.myLooper() == null) Looper.prepare()
        SyncStateMachine("TestSyncStateMachine", Looper.myLooper(), true /* debug */)
    }
    private val mInOrder by lazy {
        inOrder(mState1, mState2, mState3, mState4, mResetInEnter, mResetInEnter2, mResetInEnter3);
    }
    private val mState1 = spy(TestState(MSG_1))
    private val mState2 = spy(TestState(MSG_2))
    private val mState3 = spy(TestState(MSG_3))
    private val mState4 = spy(TestState(MSG_4))
    private val mResetInEnter = spy(TestState(MSG_RESET, true /* failInEnter */))
    private val mResetInEnter2 = spy(TestState(MSG_RESET, true /* failInEnter */))
    private val mResetInEnter3 = spy(TestState(MSG_RESET, true /* failInEnter */))

    class TestState(
        val expected: Int,
        val failInEnter: Boolean = false,
        val failInExit: Boolean = false,
        val failInProcessMsg: Boolean = false
    ) : SyncState() {
        override fun syncEnter(): Int {
            return if (failInEnter) FAILED else SUCCESSFUL
        }

        override fun syncExit(): Int {
            return if (failInExit) FAILED else SUCCESSFUL
        }

        override fun processSyncMessage(what: Int, arg1: Int, arg2: Int, obj: Any): Int {
            if (failInProcessMsg) return FAILED

            return if (what == expected) SUCCESSFUL else UNHANDLED
        }
    }

    private fun processMessage(what: Int, destState: SyncState, result: Int) {
        assertEquals(result, mSM.processMessage(what, ARG_1, ARG_2, OBJ, destState))
    }

    private fun processMessage(what: Int, result: Int) {
        assertEquals(result, mSM.processMessage(what, ARG_1, ARG_2, OBJ))
    }

    private fun noMoreInteractions() {
        verifyNoMoreInteractions(mState1, mState2, mState3, mState4, mResetInEnter, mResetInEnter2,
            mResetInEnter3);
    }

    @Test
    fun testAddState() {
        try {
            mSM.addState(mState2, mState1)
            fail("child state can add before parent state")
        } catch (e: IllegalStateException) { }

        mSM.addState(mState1)
        try {
            mSM.addState(mState1)
            fail("Can add duplicated state")
        } catch (e: IllegalStateException) { }

        mSM.addState(mState2)
        try {
            mSM.addState(mState2, mState1)
            fail("Can add duplicated state")
        } catch (e: IllegalStateException) { }
    }

    @Test
    fun testStart() {
        mSM.addState(mState1)
        try {
            mSM.start()
            fail("initial state should not be null")
        } catch (e: IllegalStateException) { }

        mSM.setInitialState(mState1)
        try {
            processMessage(MSG_1, SUCCESSFUL)
            fail("Don't allow processMessage before start()")
        } catch (e: IllegalStateException) { }

        try {
            processMessage(MSG_1, mState1, SUCCESSFUL)
            fail("Don't allow processMessage before start()")
        } catch (e: IllegalStateException) { }

        mSM.start()
        try {
            mSM.addState(mState2)
            fail("Don't allow addState after start()")
        } catch (e: IllegalStateException) { }

        try {
            mSM.addState(mState2, mState1)
            fail("Don't allow add parent state after start()")
        } catch (e: IllegalStateException) { }
    }

    @Test
    fun testInitialFromRootState() {
        // mState1 -> initial
        //    |
        // mState2
        //    |
        // mState3
        mSM.addState(mState1)
        mSM.addState(mState2, mState1)
        mSM.addState(mState3, mState2)
        mSM.setInitialState(mState1)

        mSM.start()
        mInOrder.verify(mState1).syncEnter()
        noMoreInteractions()
    }

    @Test
    fun testInitialFromLeaveState() {
        // mState1            mState4
        //    |
        // mState2
        //    |
        // mState3 -> initial
        mSM.addState(mState1)
        mSM.addState(mState2, mState1)
        mSM.addState(mState3, mState2)
        mSM.addState(mState4)
        mSM.setInitialState(mState3)

        mSM.start()
        mInOrder.verify(mState1).syncEnter()
        mInOrder.verify(mState2).syncEnter()
        mInOrder.verify(mState3).syncEnter()
        noMoreInteractions()
    }

    @Test
    fun testResetStateInParentOfInitialState() {
        // mState1
        //   |
        // mResetInEnter
        //   |
        // mState2
        //   |
        // mState3 -> initial
        mSM.addState(mState1)
        mSM.addState(mResetInEnter, mState1)
        mSM.addState(mState2, mResetInEnter)
        mSM.addState(mState3, mState2)
        mSM.setInitialState(mState3)
        mSM.start()

        mInOrder.verify(mState1).syncEnter()
        mInOrder.verify(mResetInEnter).syncEnter()
        mInOrder.verify(mState2).syncEnter()
        mInOrder.verify(mState3).syncEnter()
        noMoreInteractions()
    }

    @Test
    fun testMultiResetStateInStateEnter() {
        // mState1
        //   |
        // mResetInEnter
        //   |
        // mResetInEnter2
        //   |
        // mResetInEnter3
        //   |
        // mState2 -> initial
        mSM.addState(mState1)
        mSM.addState(mResetInEnter, mState1)
        mSM.addState(mResetInEnter2, mResetInEnter)
        mSM.addState(mResetInEnter3, mResetInEnter2)
        mSM.addState(mState2, mResetInEnter3)
        mSM.setInitialState(mState2)
        mSM.start()

        mInOrder.verify(mState1).syncEnter()
        mInOrder.verify(mResetInEnter).syncEnter()
        mInOrder.verify(mResetInEnter2).syncEnter()
        mInOrder.verify(mResetInEnter3).syncEnter()
        mInOrder.verify(mState2).syncEnter()
        noMoreInteractions()
    }
}
