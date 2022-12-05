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

private const val MSG_1 = 1
private const val MSG_2 = 2
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
        inOrder(mState1, mState2)
    }
    private val mState1 = spy(TestState(MSG_1))
    private val mState2 = spy(TestState(MSG_2))

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
}
