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

import com.android.networkstack.tethering.util.SyncStateMachine.ERROR
import com.android.networkstack.tethering.util.SyncStateMachine.SUCCESSFUL
import com.android.networkstack.tethering.util.SyncStateMachine.SyncState
import com.android.networkstack.tethering.util.SyncStateMachine.MSG_UNHANDLED

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.spy

private const val MSG_1 = 1
private const val MSG_2 = 2
private const val MSG_3 = 3
private const val ARG_1 = 100
private const val ARG_2 = 200
private val OBJ = Object()

@RunWith(AndroidJUnit4::class)
@SmallTest
class SynStateMachineTest {
    private val mState1 = spy(TestState(MSG_1))
    private val mState2 = spy(TestState(MSG_2))
    private val mState3 = spy(TestState(MSG_3))
    private val mInOrder = inOrder(mState1, mState2, mState3)

    class TestState(
        val expected: Int,
        val errorInEnter: Boolean = false,
        val errorInExit: Boolean = false
    ) : SyncState() {
        override fun syncEnter(): Int {
            return if (errorInEnter) ERROR else SUCCESSFUL
        }

        override fun syncExit(): Int {
            return if (errorInExit) ERROR else SUCCESSFUL
        }

        override fun processSyncMessage(what: Int, arg1: Int, arg2: Int, obj: Any): Boolean {
            return what == expected
        }
    }

    @Test
    fun testAddState() {
        val sm = SyncStateMachine("TestSyncStateMachine", mState1, true /* debug */)
        // mState1 is initial state which is alrady added by sm's construtor.
        try {
            sm.addState(mState1)
            fail("Can add duplicated state")
        } catch (e: IllegalStateException) { }

        try {
            sm.addState(mState3, mState2)
            fail("child state can add before parent state")
        } catch (e: IllegalStateException) { }
        sm.addState(mState2, mState1)

        sm.addState(mState3)
        try {
            sm.addState(mState1, mState3)
            fail("Can add duplicated state")
        } catch (e: IllegalStateException) { }
    }
}
