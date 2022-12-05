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

import com.android.internal.util.State;

/**
 * An implementation of a state machine, meant to be called synchronously.
 *
 * This class implements a finite state automaton based on the same State
 * class as StateMachine.
 * All methods of this class must be called on only one thread.
 */
public class SyncStateMachine {
    /**
     * Indicate the operation was successful. Return value for
     * {@link SyncStateMachine#processMessage}, {@link SyncState#syncEnter} and
     * {@link SyncState#syncExit}.
     */
    public static final int SUCCESSFUL = 0;
    /**
     * Indicate someting wrong happen during the state transtions. If error happened in the mid-way
     * of state transtions, it would continuous transtions to destination state and this value
     * would be eventually return by {@link SyncStateMachine#processMessage}. Then the caller could
     * decide how to handle the error.
     *
     * Return value for {@link SyncStateMachine#processMessage}, {@link SyncState#syncEnter} and
     * {@link SyncState#syncExit}.
     */
    public static final int ERROR = 1;
    /** The message is not processed. Return value for {@link SyncStateMachine#processMessage} */
    public static final int MSG_UNHANDLED = 2;

    private final String mName;
    private final Thread mMyThread;
    private final boolean mDbg;
    private final SyncState mInitialState;

    private SyncState mCurrentState;
    private SyncState mDestState;


    /**
     * The extened state class which provide syncEnter/syncExit/processSyncMessage methods for
     * returning the result.
     */
    public static class SyncState extends State {
        /** Called when the message is processed by this state. */
        public boolean processSyncMessage(final int what, final int arg1, final int arg2,
                final Object obj) {
            return false /* message not processed. */;
        }

        /** Called when a state is entered. */
        public int syncEnter() {
            return SUCCESSFUL;
        }

        /** Called when a state is exited. */
        public int syncExit() {
            return SUCCESSFUL;
        }
    }

    /**
     * Constructor.
     *
     * @param name of the state machine
     */
    public SyncStateMachine(final String name, final SyncState initialState) {
        this(name, initialState, false /* debug */);
    }

    public SyncStateMachine(final String name, final SyncState initialState, final boolean dbg) {
        mName = name;
        mMyThread = Thread.currentThread();
        mDbg = dbg;

        mInitialState = initialState;
        addState(initialState);
        mInitialState.enter();
        mCurrentState = initialState;
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
        ensureCorrectThread();
    }

    /**
     * Process the message synchronously. If the message is not processed, state transition
     * would not be performed and return MSG_UNHANDLED. If current state already be destState,
     * just return SUCCESSFUL without perform state transition. Besides, this should not a
     * called inside SyncState.
     */
    public final int processMessage(int what, int arg1, int arg2, Object obj) {
        ensureCorrectThread();

        return MSG_UNHANDLED;
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
        ensureCorrectThread();

        mDestState = destState;
    }

    private void ensureTransitionNotInProgress() {
        if (mDestState != null) throw new IllegalStateException("Destination already be specified");
    }

    private void ensureCorrectThread() {
        if (!mMyThread.equals(Thread.currentThread())) {
            throw new IllegalStateException("Called from wrong thread");
        }
    }
}
