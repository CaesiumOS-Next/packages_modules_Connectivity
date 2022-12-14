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
    private static final int CMD_START = 0;
    private final String mName;
    private final Thread mMyThread;
    private final boolean mDbg;
    private final int mDefaultResult;
    private final HashMap<State, StateInfo> mStateInfo = new HashMap<State, StateInfo>();

    private volatile boolean mStarted = false;

    // mCurrentState and mDestState must only be touched on mMyThread.
    private State mCurrentState;
    private State mDestState;

    /**
     * A information class about a state and its parent. Used to maintain the state hierarchy.
     */
    public static class StateInfo {
        /** The state who own this StateInfo. */
        public final State state;
        /** The parent state. */
        public final State parent;
        // True when the state has been entered and on the stack.
        private boolean mActive;

        public StateInfo(final State state) {
            this(state, null);
        }

        public StateInfo(final State child, final State parent) {
            this.state = child;
            this.parent = parent;
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
        mMyThread = thread;
        // Machine can either be setup from machine thread or before machine thread started.
        ensureCorrectOrNotStartedThread();

        mName = name;
        mDefaultResult = defaultResult;
        mDbg = dbg;
    }

    /**
     * Add all of states to the state machine. Different StateInfos which have same state but have
     * different parents are not allowed. A state can not have multiple parent states.
     * This can only be called once either from mMyThread or before mMyThread started.
     */
    public final void addAllStates(final List<StateInfo> stateInfos) {
        ensureCorrectOrNotStartedThread();

        if (mStarted) throw new IllegalStateException("State only can be added before started");
        if (!mStateInfo.isEmpty()) throw new IllegalStateException("States are already configured");

        for (final StateInfo info : stateInfos) {
            if (mStateInfo.containsKey(info.state)) {
                throw new IllegalStateException("duplicated state is not allowed");
            }

            mStateInfo.put(info.state, info);
        }

        // Check whether all of parent states indicated from StateInfo are added.
        for (final StateInfo info : stateInfos) {
            if (info.parent != null) ensureValidState(info.parent);
        }
    }

    /**
     * Start the state machine. The initial state can't be child state.
     */
    public final void start(final State initialState) {
        ensureCorrectThread();
        ensureValidState(initialState);

        final StateInfo stateInfo = mStateInfo.get(initialState);
        if (stateInfo.parent != null) {
            throw new IllegalStateException("Initial state can't be child state");
        }
        processMessage(CMD_START, 0, 0, initialState, true /* fromInternal */);
        mStarted = true;
    }

    /**
     * Process the message synchronously then perform state transition.
     *
     * @return the result of state transitions. If there is no state transition, the return value
     * would be mDefaultResult.
     */
    public final void processMessage(int what, int arg1, int arg2, Object obj) {
        processMessage(what, arg1, arg2, obj, false /* fromIntenral */);
    }

    private void processMessage(int what, int arg1, int arg2, Object obj, boolean fromInternal) {
        ensureCorrectThread();

        if (fromInternal && what == CMD_START) {
            transitionTo((State) obj); // transition to initial state.
        }

        performTransitions();
    }

    /**
     * Transition to destination state. Upon returning from processMessage the current state
     * would be transition to given destination state.
     *
     * This function can NOT be called inside the State enter and exit function. The transition
     * target is always defined and never be change in the mid-way of state transition.
     *
     * @param destState will be the state that process the next message.
     */
    public final void transitionTo(final State destState) {
        if (mDbg) Log.d(mName, "transitionTo " + destState);
        ensureCorrectThread();
        ensureValidState(destState);

        if (mDestState == null) {
            mDestState = destState;
        } else {
            throw new IllegalStateException("Destination already be specified");
        }
    }

    private void performTransitions() {
        // 1. Determine the common ancestor state of current/destination states
        // 2. Invoke state exit list from current state to common acestor state.
        // 3. Invoke state enter list from common ancestor state to destState by going
        // through mEnterStateStack.
        if (mDestState == null) return;

        invokeExitMethods(setupEnterStackOfInactiveState());
        invokeEnterMethods();
        mEnterStateStack.clear();
        mDestState = null;
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
            curStateInfo = mStateInfo.get(curStateInfo.parent);
        }

        // common ancestor state of current state and destination state. Current state or
        // destination state could be common ancestor if they are in the same parent list.
        return curStateInfo;
    }

    // Call the exit method from current state to common ancestor state.
    private void invokeExitMethods(final StateInfo commonStateInfo) {
        StateInfo stateInfo = mStateInfo.get(mCurrentState);
        while (stateInfo != null && stateInfo != commonStateInfo) {
            if (mDbg) Log.d(mName, stateInfo.state + " exit()");
            stateInfo.state.exit();
            stateInfo.mActive = false;
            stateInfo = mStateInfo.get(stateInfo.parent);
        }

        mCurrentState = stateInfo != null ? stateInfo.state : null;
    }

    // Call the enter method from common ancestor state to destination state.
    private void invokeEnterMethods() {
        while (!mEnterStateStack.isEmpty()) {
            final StateInfo stateInfo = mEnterStateStack.remove(mEnterStateStack.size() - 1);
            if (mDbg) Log.d(mName, stateInfo.state + " enter()");
            mCurrentState = stateInfo.state;
            stateInfo.state.enter();
            stateInfo.mActive = true;
        }
    }

    private void ensureCorrectThread() {
        if (!mMyThread.equals(Thread.currentThread())) {
            throw new IllegalStateException("Called from wrong thread");
        }
    }

    private void ensureCorrectOrNotStartedThread() {
        if (!mMyThread.isAlive()) return;

        ensureCorrectThread();
    }

    private void ensureValidState(final State state) {
        if (!mStateInfo.containsKey(state)) throw new IllegalStateException("Invalid state");
    }
}
