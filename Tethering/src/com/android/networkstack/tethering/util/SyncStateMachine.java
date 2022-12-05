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

package com.android.networkstack.tethering.util;

import android.util.Log;

import com.android.internal.util.State;

/**
 * An implementation of a state machine, meant to be called synchronously.
 *
 * This class implements a finite state automaton based on the same State
 * class as StateMachine.
 * All methods of this class must be called on only one thread.
 */
public class SyncStateMachine {
    private final String mName;
    private final Thread mMyThread;
    private final boolean mDbg;
    private final int mDefaultResult;

    private SyncState mCurrentState;
    private SyncState mDestState;

    /**
     * The extened state class which provide enter/exit/processMessage methods for
     * returning the result.
     */
    public static class SyncState extends State {
        /** Called when the message is processed by this state. */
        public boolean processMessage(final int what, final int arg1, final int arg2,
                final Object obj) {
            return false /* message not processed. */;
        }

        /**
         * Called when a state is entered.
         *
         * @param lastResult the return value of parent state enter. For the first state enter,
         * the value would be mDefaultResult.
         */
        public int enter(final int lastResult) {
            return lastResult;
        }

        /**
         * Called when a state is exited.
         *
         * @param lastResult the return value of child state enter. For the first state exit,
         * the value would be mDefaultResult.
         */
        public int exit(final int lastResult) {
            return lastResult;
        }
    }

    public SyncStateMachine(final String name, final Thread thread, final int defaultResult) {
        this(name, thread, defaultResult, false /* debug */);
    }

    public SyncStateMachine(final String name, final Thread thread, final int defaultResult,
            final boolean dbg) {
        mName = name;
        mDefaultResult = defaultResult;
        mMyThread = thread;
        mDbg = dbg;
    }

    /** Add a new state to the state machine. */
    public final void addState(final SyncState state) {
        addState(state, null);
    }

    /**
     * Add a new state to the state machine. Bottom up addition
     * of states is not allowed.
     *
     * @param state the state to add
     * @param parent the parent of state
     */
    public final void addState(final SyncState state, final SyncState parent) {
        if (mDbg) Log.d(mName, "addState(" + state + ", " + parent + ")");
    }

    /**
     * Start the state machine.
     */
    public final void start(final SyncState initialState) {
        ensureCorrectThread();

        mCurrentState = initialState;
        mDestState = null;
    }

    /**
     * Process the message synchronously. State transitions would be performed after processing
     * message. If current state already be destState, just return mDefaultResult without
     * perform state transition.
     */
    public final int processMessage(int what, int arg1, int arg2, Object obj) {
        ensureCorrectThread();

        return mDefaultResult;
    }

    /**
     * Transition to destination state. Upon returning from processMessage the current state
     * would be transition to given destination state.
     *
     * This function can NOT be called inside the SyncState enter and exit function. The transition
     * target is always defined and never be change in the mid-way of state transition.
     *
     * @param destState will be the state that process the next message.
     */
    public final void transitionTo(final SyncState destState) {
        if (mDbg) Log.d(mName, "transitionTo " + destState);
        ensureCorrectThread();

        mDestState = destState;
    }

    private void ensureCorrectThread() {
        if (!mMyThread.equals(Thread.currentThread())) {
            throw new IllegalStateException("Called from wrong thread");
        }
    }
}
