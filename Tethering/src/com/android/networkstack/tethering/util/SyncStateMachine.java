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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * An implementation of a state machine, meant to be called synchronously.
 *
 * This class implements a finite state automaton based on the same State
 * class as StateMachine.
 * All methods of this class must be called on only one thread.
 */
public class SyncStateMachine {
    private static final int DEFAULT_CAPACITY = 1;
    private final String mName;
    private final Thread mMyThread;
    private final boolean mDbg;
    private final int mDefaultResult;
    private final HashMap<SyncState, StateInfo> mStateInfo = new HashMap<SyncState, StateInfo>();

    private volatile boolean mStarted = false;

    // mCurrentState and mDestState must only be touched on mMyThread.
    private SyncState mCurrentState;
    private SyncState mDestState;
    private boolean mIsInProcess;

    /**
     * A information class about a state and its parent.
     * This is passed by addStateInfos. Used to maintain the state hierarchy.
     */
    public static class StateInfo {
        private final SyncState mState;
        private final SyncState mParent;
        // True when the state has been entered and on the stack.
        private boolean mActive;

        public StateInfo(final SyncState state) {
            this(state, null);
        }

        public StateInfo(final SyncState child, final SyncState parent) {
            mState = child;
            mParent = parent;
        }
    }

    /** The state class which provides enter/exit/processMessage methods. */
    public static class SyncState extends State {
        /** Called when the message is processed by this state. */
        public boolean processMessage(final int what, final int arg1, final int arg2,
                final Object obj) {
            return false /* message not processed. */;
        }

        /**
         * Called when this state is entered. The lastResult parameter is the information passed by
         * previous state during state transitions. The state could decide how to the handle the
         * information from previous state and return the information to next state.
         * For examples, there are three states in state machine. State A is the parent of state B.
         * State B is the parent of state C. A -> B -> C. State machine is transitioning from A to
         * C. Now someting wrong happen in entering B so that B can return the error and C will
         * receive B's error code from its lastResult parameter. Note: the state transition is
         * defined, it can not be changed in the mid-way. If calling
         * SyncStateMachine#transitionTo(C) from A, the state will be transitioned to C even if
         * something wrong happen in B.
         *
         * @param lastResult is the information passed by previous state during state transitions.
         * If this state is the first state of state transition. The value would be mDefaultResult.
         * @return the information passed to next state. If this state is the target state of
         * transitions, the return value would pass to the caller.
         */
        public int enter(final int lastResult) {
            return lastResult;
        }

        /**
         * Called when this state is exited.
         *
         * @param lastResult is the information passed by previous state during state transitions.
         * If this state is the first state of state transition. The value would be mDefaultResult.
         * @return the information passed to next state. If this state is the target state of
         * transitions, the return value would pass to the caller.
         */
        public int exit(final int lastResult) {
            return lastResult;
        }
    }

    public SyncStateMachine(final String name, final int defaultResult) {
        this(name, Thread.currentThread(), defaultResult);
    }

    public SyncStateMachine(final String name, final Thread thread, final int defaultResult) {
        this(name, thread, defaultResult, false /* debug */);
    }

    public SyncStateMachine(final String name, final Thread thread, final int defaultResult,
            final boolean dbg) {
        // SM only can either run on current thread or a thread which is not started yet.
        if (!Thread.currentThread().equals(thread) && thread.isAlive()) {
            throw new IllegalStateException("SM can not run on given thread");
        }

        mName = name;
        mDefaultResult = defaultResult;
        mMyThread = thread;
        mDbg = dbg;
    }

    /**
     * Add a list of StateInfo to the state machine. A state can not have multiple parent states.
     */
    public final void addStateInfos(final List<StateInfo> stateInfos) {
        if (mStarted) throw new IllegalStateException("State only can be added before started");

        for (final StateInfo info : stateInfos) {
            if (!mStateInfo.containsKey(info.mState)) {
                throw new IllegalStateException("duplicated state is not allowed");
            }

            mStateInfo.put(info.mState, info);
        }
    }

    /**
     * Start the state machine. The initial state can't be child state.
     */
    public final void start(final SyncState initialState) {
        ensureCorrectThread();
        ensureValidState(initialState);

        final StateInfo stateInfo = mStateInfo.get(initialState);
        if (stateInfo.mParent != null) {
            throw new IllegalStateException("Initial state can't be child state");
        }
        stateInfo.mActive = true;

        initialState.enter(mDefaultResult);
        mCurrentState = initialState;
        mDestState = null;
        mStarted = true;
    }

    /**
     * Process the message synchronously then perform state transition.
     *
     * @return the result of state transitions. If there is no state transition, the return value
     * would be mDefaultResult.
     */
    public final int processMessage(int what, int arg1, int arg2, Object obj) {
        ensureCorrectThread();

        if (mIsInProcess) throw new IllegalStateException("Previous message is still in process");

        mIsInProcess = true;
        try {
            StateInfo curStateInfo = mStateInfo.get(mCurrentState);
            while (curStateInfo != null) {
                if (curStateInfo.mState.processMessage(what, arg1, arg2, obj)) {
                    if (mDbg) Log.d(mName, "processMsg(" + what + ") at " + curStateInfo.mState);
                    break;
                }
                curStateInfo = mStateInfo.get(curStateInfo.mParent);
            }

            return performTransitions();
        } finally {
            mIsInProcess = false;
        }
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
        ensureValidState(destState);

        if (mDestState == null) {
            mDestState = destState;
        } else {
            throw new IllegalStateException("Destination already be specified");
        }
    }

    private int performTransitions() {
        // 1. Determine the common ancestor state of current/destination states
        // 2. Invoke state exit list from current state to common acestor state.
        // 3. Invoke state enter list from common ancestor state to destState by going
        // through mEnterStateStack.
        if (mDestState == null) return mDefaultResult;

        final StateInfo commonStateInfo = setupEnterStackOfInactiveState();
        try {
            final int lastResult = invokeExitMethods(commonStateInfo);
            return invokeEnterMethods(lastResult);
        } finally {
            mEnterStateStack.clear();
            mDestState = null;
        }
    }

    // Instead of creating a new ArrayList every time the machine performs a transition, having a
    // cache stack temporary storing the states which will be entered.
    private final ArrayList<StateInfo> mEnterStateStack = new ArrayList<>(DEFAULT_CAPACITY);
    private StateInfo setupEnterStackOfInactiveState() {
        // Search up the parent list of the destination state for an active
        // state.
        StateInfo curStateInfo = mStateInfo.get(mDestState);
        while ((curStateInfo != null) && !curStateInfo.mActive) {
            mEnterStateStack.add(curStateInfo);
            curStateInfo = mStateInfo.get(curStateInfo.mParent);
        }

        // common ancestor state of current state and destination state. Current state or
        // destination state could be common ancestor if they are in the same parent list.
        return curStateInfo;
    }

    // Call the exit method from current state to common ancestor state.
    private int invokeExitMethods(final StateInfo commonStateInfo) {
        int lastResult = mDefaultResult;
        StateInfo stateInfo = mStateInfo.get(mCurrentState);
        while (stateInfo != null && stateInfo != commonStateInfo) {
            if (mDbg) Log.d(mName, stateInfo.mState + " exit()");
            lastResult = stateInfo.mState.exit(lastResult);
            stateInfo.mActive = false;
            stateInfo = mStateInfo.get(stateInfo.mParent);
        }

        mCurrentState = stateInfo != null ? stateInfo.mState : null;
        return lastResult;
    }

    // Call the enter method from common ancestor state to destination state.
    private int invokeEnterMethods(int defaultResult) {
        int lastResult = defaultResult;
        while (!mEnterStateStack.isEmpty()) {
            final StateInfo stateInfo = mEnterStateStack.remove(mEnterStateStack.size() - 1);
            if (mDbg) Log.d(mName, stateInfo.mState + " enter()");
            lastResult = stateInfo.mState.enter(lastResult);
            stateInfo.mActive = true;
            mCurrentState = stateInfo.mState;
        }

        return lastResult;
    }

    private void ensureCorrectThread() {
        if (!mMyThread.equals(Thread.currentThread())) {
            throw new IllegalStateException("Called from wrong thread");
        }
    }

    private void ensureValidState(final SyncState state) {
        if (!mStateInfo.containsKey(state)) throw new IllegalStateException("Invalid state");
    }
}
