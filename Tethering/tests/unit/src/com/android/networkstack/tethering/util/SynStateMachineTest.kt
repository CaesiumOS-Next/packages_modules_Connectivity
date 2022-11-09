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

import android.os.Message

import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4

import com.android.internal.util.State

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions

private const val MSG_RESET = 0
private const val MSG_1 = 1
private const val MSG_2 = 2
private const val MSG_3 = 3
private const val MSG_4 = 4
private const val MSG_5 = 5
private const val MSG_6 = 6
private const val ARG_1 = 100
private const val ARG_2 = 200
private val OBJ = Object()

@RunWith(AndroidJUnit4::class)
@SmallTest
class SynStateMachineTest {
    private val mSM by lazy { SyncStateMachine("TestSyncStateMachine", true /* debug */) }
    private val mInOrder by lazy {
        inOrder(mState1, mState2, mState3, mState4, mState5, mState6, mResetInEnter, mResetInEnter2,
                mResetInEnter3, mResetInExit, mResetInExit2, mResetInExit3, mResetInProcessMsg)
    }
    private val mState1 = spy(TestState(MSG_1))
    private val mState2 = spy(TestState(MSG_2))
    private val mState3 = spy(TestState(MSG_3))
    private val mState4 = spy(TestState(MSG_4))
    private val mState5 = spy(TestState(MSG_5))
    private val mState6 = spy(TestState(MSG_6))
    private val mResetInEnter = spy(TestState(MSG_RESET, true /* resetInEnter */))
    private val mResetInEnter2 = spy(TestState(MSG_RESET, true /* resetInEnter */))
    private val mResetInEnter3 = spy(TestState(MSG_RESET, true /* resetInEnter */))
    private val mResetInExit = spy(TestState(MSG_RESET, false /* resetInEnter */,
            true /* resetInExit */))
    private val mResetInExit2 = spy(TestState(MSG_RESET, false /* resetInEnter */,
            true /* resetInExit */))
    private val mResetInExit3 = spy(TestState(MSG_RESET, false /* resetInEnter */,
            true /* resetInExit */))
    private val mResetInProcessMsg = spy(TestState(MSG_RESET, false /* resetInEnter */,
            false /* resetInExit */, true /* resetInProcessMsg */))

    inner class TestState(
        val processed: Int,
        val resetInEnter: Boolean = false,
        val resetInExit: Boolean = false,
        val resetInProcessMsg: Boolean = false
    ) : State() {
        override fun enter() {
            if (resetInEnter) mSM.resetState()
        }

        override fun exit() {
            if (resetInExit) mSM.resetState()
        }

        override fun processMessage(message: Message): Boolean {
            if (resetInProcessMsg) mSM.resetState()

            if (message.what == processed) return true

            return false
        }
    }

    inner class TestErrorProne(
        val testEnter: Boolean = false,
        val testExit: Boolean = false,
        val testProcessMsg: Boolean = false
    ) : State() {
        override fun enter() {
            if (testEnter) mSM.processMessage(MSG_1, ARG_1, ARG_2, OBJ)
        }

        override fun exit() {
            if (testExit) mSM.processMessage(MSG_1, ARG_1, ARG_2, OBJ)
        }

        override fun processMessage(message: Message): Boolean {
            if (testProcessMsg) mSM.processMessage(MSG_1, ARG_1, ARG_2, OBJ)

            return true
        }
    }

    private fun noMoreInteractions() {
        verifyNoMoreInteractions(mState1, mState2, mState3, mState4, mState5, mState6)
    }

    private fun processMessage(what: Int, destState: State, handled: Boolean) {
        assertEquals(handled, mSM.processMessage(what, ARG_1, ARG_2, OBJ, destState))
    }

    private fun processMessage(what: Int, handled: Boolean) {
        assertEquals(handled, mSM.processMessage(what, ARG_1, ARG_2, OBJ))
    }

    private fun verifyMessage(what: Int, state: State) {
        val msgCaptor = ArgumentCaptor.forClass(Message::class.java)
        mInOrder.verify(state).processMessage(msgCaptor.capture())
        val msg = msgCaptor.value

        assertEquals(what, msg.what)
        assertEquals(ARG_1, msg.arg1)
        assertEquals(ARG_2, msg.arg2)
        assertEquals(OBJ, msg.obj)
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
            processMessage(MSG_1, true)
            fail("Don't allow processMessage before start()")
        } catch (e: IllegalStateException) { }

        try {
            processMessage(MSG_1, mState1, true)
            fail("Don't allow processMessage before start()")
        } catch (e: IllegalStateException) { }

        try {
            mSM.resetState()
            fail("Don't allow resetState before start()")
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
    fun testThrowIfProcessMsgInStateProcessMsg() {
        val testError = TestErrorProne(false /* testEnter */, false /* testExit */,
                true /* testProcessMsg*/)
        mSM.addState(testError)
        mSM.setInitialState(testError)
        mSM.start()

        try {
            processMessage(MSG_1, true)
            fail("Don't support recursive processMessage")
        } catch (e: IllegalStateException) { }
    }

    @Test
    fun testThrowIfProcessMsgInStateEnter() {
        val testError = TestErrorProne(true /* testEnter */)
        mSM.addState(testError)
        mSM.setInitialState(testError)

        try {
            mSM.start()
            fail("Don't support processMessage in State#enter()")
        } catch (e: IllegalStateException) { }
    }

    @Test
    fun testThrowIfProcessMsgInStateExit() {
        val testError = TestErrorProne(false /* testEnter */, true /* testExit */)
        mSM.addState(testError)
        mSM.addState(mState1)
        mSM.setInitialState(testError)
        mSM.start()

        try {
            processMessage(MSG_1, mState1, true)
            fail("Don't support processMessage in State#exit()")
        } catch (e: IllegalStateException) { }
    }

    @Test
    fun testResetState() {
        //                mState1       mState2 -> initial
        //            /      |       \
        // mResetInEnter mResetInExit mResetInProcessMsg
        //      |                         |
        //   mState3                    mState4
        mSM.addState(mState1)
            mSM.addState(mResetInEnter, mState1)
                mSM.addState(mState3, mResetInEnter)
            mSM.addState(mResetInExit, mState1)
            mSM.addState(mResetInProcessMsg, mState1)
                mSM.addState(mState4, mResetInProcessMsg)
        mSM.addState(mState2)

        mSM.setInitialState(mState2)
        mSM.start()
        mInOrder.verify(mState2).enter()
        noMoreInteractions()

        processMessage(MSG_2, mState3, false)
        verifyMessage(MSG_2, mState2)
        mInOrder.verify(mState2).exit()
        mInOrder.verify(mState1).enter()
        mInOrder.verify(mResetInEnter).enter()
        mInOrder.verify(mResetInEnter).exit()
        mInOrder.verify(mState1).exit()
        mInOrder.verify(mState2).enter()
        noMoreInteractions()

        processMessage(MSG_2, mResetInExit, true)
        verifyMessage(MSG_2, mState2)
        mInOrder.verify(mState2).exit()
        mInOrder.verify(mState1).enter()
        mInOrder.verify(mResetInExit).enter()
        noMoreInteractions()
        processMessage(MSG_1, mState4, false)
        verifyMessage(MSG_1, mResetInExit)
        verifyMessage(MSG_1, mState1)
        mInOrder.verify(mResetInExit).exit()
        mInOrder.verify(mState1).exit()
        mInOrder.verify(mState2).enter()
        noMoreInteractions()

        processMessage(MSG_2, mState4, true)
        verifyMessage(MSG_2, mState2)
        mInOrder.verify(mState2).exit()
        mInOrder.verify(mState1).enter()
        mInOrder.verify(mResetInProcessMsg).enter()
        mInOrder.verify(mState4).enter()
        noMoreInteractions()
        processMessage(MSG_RESET, false)
        verifyMessage(MSG_RESET, mState4)
        verifyMessage(MSG_RESET, mResetInProcessMsg)
        mInOrder.verify(mState4).exit()
        mInOrder.verify(mResetInProcessMsg).exit()
        mInOrder.verify(mState1).exit()
        mInOrder.verify(mState2).enter()
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

        mInOrder.verify(mState1).enter()
        mInOrder.verify(mResetInEnter).enter()
        mInOrder.verify(mState2).enter()
        mInOrder.verify(mState3).enter()
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

        mInOrder.verify(mState1).enter()
        mInOrder.verify(mResetInEnter).enter()
        mInOrder.verify(mResetInEnter2).enter()
        mInOrder.verify(mResetInEnter3).enter()
        mInOrder.verify(mState2).enter()
        noMoreInteractions()
    }

    @Test
    fun testMultiResetInStateEnterExit() {
        //           mState2            mState1 <- initial
        //         /        \
        // mResetInExit    mState4
        //   |
        // mResetInExit2
        //   |
        // mResetInEnter
        //   |
        // mState3
        mSM.addState(mState1)
        mSM.addState(mState2)
        mSM.addState(mResetInExit, mState2)
        mSM.addState(mResetInExit2, mResetInExit)
        mSM.addState(mResetInEnter, mResetInExit2)
        mSM.addState(mState3, mResetInEnter)
        mSM.addState(mState4, mState2)
        mSM.setInitialState(mState1)
        mSM.start()
        mInOrder.verify(mState1).enter()
        noMoreInteractions()

        processMessage(MSG_1, mState3, false)
        verifyMessage(MSG_1, mState1)
        mInOrder.verify(mState1).exit()
        mInOrder.verify(mState2).enter()
        mInOrder.verify(mResetInExit).enter()
        mInOrder.verify(mResetInExit2).enter()
        mInOrder.verify(mResetInEnter).enter()
        mInOrder.verify(mResetInEnter).exit()
        mInOrder.verify(mResetInExit2).exit()
        mInOrder.verify(mResetInExit).exit()
        mInOrder.verify(mState2).exit()
        mInOrder.verify(mState1).enter()
        noMoreInteractions()
    }

    @Test
    fun testMultiResetInStateExitProcessMsg() {
        //      mState2            mState1 <- initial
        //    /        \
        // mState3    mResetInExit
        //             |
        //            mResetInExit2
        //             |
        //            mResetInProcessMsg
        //             |
        //            mState4
        mSM.addState(mState1)
        mSM.addState(mState2)
        mSM.addState(mState3, mState2)
        mSM.addState(mResetInExit, mState2)
        mSM.addState(mResetInExit2, mResetInExit)
        mSM.addState(mResetInProcessMsg, mResetInExit2)
        mSM.addState(mState4, mResetInProcessMsg)
        mSM.setInitialState(mState1)
        mSM.start()
        mInOrder.verify(mState1).enter()
        noMoreInteractions()

        processMessage(MSG_1, mState4, true)
        verifyMessage(MSG_1, mState1)
        mInOrder.verify(mState1).exit()
        mInOrder.verify(mState2).enter()
        mInOrder.verify(mResetInExit).enter()
        mInOrder.verify(mResetInExit2).enter()
        mInOrder.verify(mResetInProcessMsg).enter()
        mInOrder.verify(mState4).enter()
        noMoreInteractions()

        processMessage(MSG_RESET, false)
        verifyMessage(MSG_RESET, mState4)
        verifyMessage(MSG_RESET, mResetInProcessMsg)
        mInOrder.verify(mState4).exit()
        mInOrder.verify(mResetInProcessMsg).exit()
        mInOrder.verify(mResetInExit2).exit()
        mInOrder.verify(mResetInExit).exit()
        mInOrder.verify(mState2).exit()
        mInOrder.verify(mState1).enter()
        noMoreInteractions()
    }

    @Test
    fun testProcessMessageWithStateTransition() {
        // mState1 <-initial, mState2
        mSM.addState(mState1)
        mSM.addState(mState2)

        mSM.setInitialState(mState1)

        mSM.start()
        mInOrder.verify(mState1).enter()
        noMoreInteractions()

        // mState1 process MSG_1
        processMessage(MSG_1, true)
        verifyMessage(MSG_1, mState1)
        noMoreInteractions()

        // mState1 don't process MSG_2
        processMessage(MSG_2, false)
        verifyMessage(MSG_2, mState1)
        noMoreInteractions()

        // mState1 doesn't process MSG_2, no state transition.
        processMessage(MSG_2, mState2, false)
        verifyMessage(MSG_2, mState1)
        noMoreInteractions()

        // mState1 process MSG_1 and the state transition to mState2
        processMessage(MSG_1, mState2, true)
        verifyMessage(MSG_1, mState1)
        mInOrder.verify(mState1).exit()
        mInOrder.verify(mState2).enter()
        noMoreInteractions()

        // If set destState to mState2 (current state), no state transition.
        processMessage(MSG_2, mState2, true)
        verifyMessage(MSG_2, mState2)
        noMoreInteractions()

        mSM.resetState()
        mInOrder.verify(mState2).exit()
        mInOrder.verify(mState1).enter()
        noMoreInteractions()
    }

    @Test
    fun testInitFromRootState() {
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
        mInOrder.verify(mState1).enter()
        noMoreInteractions()

        // mState1 process MSG_1 and transition to mState2.
        // mState1 -> current
        //    |
        // mState2 -> dest
        //    |
        // mState3
        processMessage(MSG_1, mState2, true)
        verifyMessage(MSG_1, mState1)
        mInOrder.verify(mState2).enter()
        noMoreInteractions()

        processMessage(MSG_1, mState2, true)
        verifyMessage(MSG_1, mState2)
        verifyMessage(MSG_1, mState1)
        noMoreInteractions()

        // mState2 process MSG_2 and transition to mState3.
        // mState1
        //    |
        // mState2 -> current
        //    |
        // mState3 -> dest
        processMessage(MSG_2, mState3, true)
        verifyMessage(MSG_2, mState2)
        mInOrder.verify(mState3).enter()
        noMoreInteractions()

        // mState3's parent mState2 process MSG_2 and transition to mState1.
        // mState1 -> dest
        //    |
        // mState2
        //    |
        // mState3 -> current
        processMessage(MSG_2, mState1, true)
        verifyMessage(MSG_2, mState3)
        verifyMessage(MSG_2, mState2)
        mInOrder.verify(mState3).exit()
        mInOrder.verify(mState2).exit()
        // Since mState1 is the ancestor of mState3, no mState1.exit() and mState1.enter().
        noMoreInteractions()

        mSM.resetState()
        noMoreInteractions()
    }

    @Test
    fun testInitFromLeaveState() {
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
        mInOrder.verify(mState1).enter()
        mInOrder.verify(mState2).enter()
        mInOrder.verify(mState3).enter()
        noMoreInteractions()

        // mState1            mState4
        //    |
        // mState2 -> dest
        //    |
        // mState3 -> current
        processMessage(MSG_3, mState2, true)
        verifyMessage(MSG_3, mState3)
        mInOrder.verify(mState3).exit()
        noMoreInteractions()

        // mState1            mState4 -> dest
        //    |
        // mState2 -> current
        //    |
        // mState3
        processMessage(MSG_2, mState4, true)
        verifyMessage(MSG_2, mState2)
        mInOrder.verify(mState2).exit()
        mInOrder.verify(mState1).exit()
        mInOrder.verify(mState4).enter()
        noMoreInteractions()

        processMessage(MSG_1, false)
        verifyMessage(MSG_1, mState4)
        noMoreInteractions()
        processMessage(MSG_4, mState4, true)
        verifyMessage(MSG_4, mState4)
        noMoreInteractions()

        // mState1            mState4 -> current
        //    |
        // mState2
        //    |
        // mState3 -> dest
        processMessage(MSG_4, mState3, true)
        verifyMessage(MSG_4, mState4)
        mInOrder.verify(mState4).exit()
        mInOrder.verify(mState1).enter()
        mInOrder.verify(mState2).enter()
        mInOrder.verify(mState3).enter()
        noMoreInteractions()

        mSM.resetState()
        noMoreInteractions()
    }

    @Test
    fun testMultipleStateTrees() {
        //    mState1 -> initial  mState4
        //    /     \             /     \
        // mState2 mState3     mState5 mState6
        mSM.addState(mState1)
        mSM.addState(mState2, mState1)
        mSM.addState(mState3, mState1)
        mSM.addState(mState4)
        mSM.addState(mState5, mState4)
        mSM.addState(mState6, mState4)
        mSM.setInitialState(mState1)

        mSM.start()
        mInOrder.verify(mState1).enter()
        noMoreInteractions()

        //    mState1 -> current     mState4
        //    /     \                /     \
        // mState2 mState3 -> dest mState5 mState6
        processMessage(MSG_1, mState3, true)
        verifyMessage(MSG_1, mState1)
        mInOrder.verify(mState3).enter()
        noMoreInteractions()

        //           mState1                     mState4
        //           /     \                     /     \
        // dest <- mState2 mState3 -> current mState5 mState6
        processMessage(MSG_1, mState2, true)
        verifyMessage(MSG_1, mState3)
        verifyMessage(MSG_1, mState1)
        mInOrder.verify(mState3).exit()
        mInOrder.verify(mState2).enter()
        noMoreInteractions()

        //               mState1          mState4
        //               /     \          /     \
        // current <- mState2 mState3 mState5 mState6 -> dest
        processMessage(MSG_2, mState6, true)
        verifyMessage(MSG_2, mState2)
        mInOrder.verify(mState2).exit()
        mInOrder.verify(mState1).exit()
        mInOrder.verify(mState4).enter()
        mInOrder.verify(mState6).enter()
        noMoreInteractions()

        mSM.resetState()
        mInOrder.verify(mState6).exit()
        mInOrder.verify(mState4).exit()
        mInOrder.verify(mState1).enter()
        noMoreInteractions()
    }
}
