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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.util.State;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;

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
    // com.android.internal.util.State doesn't implement equals() or hashcode(), the key of this
    // HashMap must be exact State object. An instance of a state can't have multiple parent states,
    // but it's fine to have multiple instances of the same state as long as they have different
    // parents.
    // Not supported:            Supported:
    //   State1    State2          State1             State2
    //         \   /                 |                  |
    //       State3@instance1      State3@instance1   State3@instance2
    private final HashMap<State, StateInfo> mStateInfo = new HashMap<State, StateInfo>();

    private boolean mStarted = false;

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

    /**
     * The constructor.
     *
     * @param name of this machine.
     * @param thread the running thread of this machine. It must either be the thread on which this
     * constructor is called, or a thread that is not started yet.
     */
    public SyncStateMachine(final String name, final Thread thread) {
        this(name, thread, false /* debug */);
    }

    /**
     * The constructor.
     *
     * @param name of this machine.
     * @param thread the running thread of this machine. It must either be the thread on which this
     * constructor is called, or a thread that is not started yet.
     * @param dbg whether to print debug logs.
     */
    public SyncStateMachine(final String name, final Thread thread, final boolean dbg) {
        mMyThread = thread;
        // Machine can either be setup from machine thread or before machine thread started.
        ensureCorrectOrNotStartedThread();

        mName = name;
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
            Objects.requireNonNull(info.state);
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
     *
     * @param initialState the first state of this machine. The state must be exact state object
     * setting up by {@link #addAllStates}, not a copy of it.
     */
    public final void start(final State initialState) {
        ensureCorrectThread();
        ensureValidState(initialState);

        final StateInfo stateInfo = mStateInfo.get(initialState);
        if (stateInfo.parent != null) {
            throw new IllegalStateException("Initial state can't be a child state");
        }
        transitionTo(initialState);
        performTransitions();
        mStarted = true;
    }

    /**
     * Process the message synchronously then perform state transition.
     */
    public final void processMessage(int what, int arg1, int arg2, Object obj) {
        ensureCorrectThread();

        performTransitions();
    }

    /**
     * Transition to destination state. Upon returning from processMessage the automaton will
     * transition to the given destination state.
     *
     * This function can NOT be called inside the State enter and exit function. The transition
     * target is always defined and never be changed in the mid-way of state transition.
     *
     * @param destState will be the state that process the next message. The state must be exact
     * state object setting up by {@link #addAllStates}, not a copy of it.
     */
    public final void transitionTo(final State destState) {
        if (mDbg) Log.d(mName, "transitionTo " + destState);
        ensureCorrectThread();
        ensureValidState(destState);

        if (mDestState == null) {
            mDestState = destState;
        } else {
            throw new IllegalStateException("Destination already specified");
        }
    }

    private void performTransitions() {
        // 1. Determine the common ancestor state of current/destination states
        // 2. Invoke state exit list from current state to common acestor state.
        // 3. Invoke state enter list from common ancestor state to destState by going
        // through mEnterStateStack.
        if (mDestState == null) return;

        final StateInfo commonAncestor = getLastActiveAncestor(mStateInfo.get(mDestState));

        executeExitMethods(commonAncestor, mStateInfo.get(mCurrentState));
        executeEnterMethods(commonAncestor, mStateInfo.get(mDestState));
        mCurrentState = mDestState;
        mDestState = null;
    }

    private StateInfo getLastActiveAncestor(final StateInfo start) {
        if (null == start || start.mActive) return start;

        return getLastActiveAncestor(mStateInfo.get(start.parent));
    }

    // Call the exit method from current state to common ancestor state.
    private void executeExitMethods(@Nullable StateInfo end, @NonNull StateInfo start) {
        if (end == start) return;

        if (mDbg) Log.d(mName, start.state + " exit()");
        start.state.exit();
        start.mActive = false;
        executeExitMethods(end, mStateInfo.get(start.parent));
    }

    // Call the enter method from common ancestor state to destination state.
    private void executeEnterMethods(@Nullable StateInfo end, @NonNull StateInfo dest) {
        if (dest == end) return;

        executeEnterMethods(end, mStateInfo.get(dest.parent));
        if (mDbg) Log.d(mName, dest.state + " enter()");
        dest.state.enter();
        dest.mActive = true;
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
