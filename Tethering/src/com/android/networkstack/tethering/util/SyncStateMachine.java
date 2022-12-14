/**
 * Copyright (C) 2023 The Android Open Source Project
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

import android.os.Message;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.util.State;

import java.util.ArrayDeque;
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
    @NonNull private final String mName;
    @NonNull private final Thread mMyThread;
    private final boolean mDbg;
    // com.android.internal.util.State doesn't implement equals() or hashcode(), the key of this
    // map must be the exact State instance. An instance of a state can't have multiple parent
    // states, but it's fine to have multiple instances of the same state as long as they have
    // different parents.
    // Not supported:            Supported:
    //   State1    State2          State1             State2
    //         \   /                 |                  |
    //       State3@instance1      State3@instance1   State3@instance2
    private final ArrayMap<State, StateInfo> mStateInfo = new ArrayMap<>();

    // mCurrentState is the current state. mDestState is the target state that mCurrentState will
    // transition to. The value of mDestState can be changed when a state processes a message and
    // calls #transitionTo, but it cannot be changed during the state transition. When the state
    // transition is complete, mDestState will be set to mCurrentState. Both mCurrentState and
    // mDestState only be null before state machine starts and must only be touched on mMyThread.
    @Nullable private State mCurrentState;
    @Nullable private State mDestState;
    private final ArrayDeque<Message> mSelfMsgQueue = new ArrayDeque<Message>();

    // MIN_VALUE means not currently processing any message.
    private int mCurrentlyProcessing = Integer.MIN_VALUE;
    // Indicates if whether automaton could send self message.
    private boolean mSelfMsgAllowed = false;

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

        public StateInfo(@NonNull final State child, @Nullable final State parent) {
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
    public SyncStateMachine(@NonNull final String name, @NonNull final Thread thread) {
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
    public SyncStateMachine(@NonNull final String name, @NonNull final Thread thread,
            final boolean dbg) {
        mMyThread = thread;
        // Machine can either be setup from machine thread or before machine thread started.
        ensureCorrectOrNotStartedThread();

        mName = name;
        mDbg = dbg;
    }

    /**
     * Add all of states to the state machine. Different StateInfos which have same state instance
     * are not alled. In other words, a state can not have multiple parent states. #addAllStates
     * can only be called once either from mMyThread or before mMyThread started.
     */
    public final void addAllStates(@NonNull final List<StateInfo> stateInfos) {
        ensureCorrectOrNotStartedThread();

        if (mCurrentState != null) {
            throw new IllegalStateException("State only can be added before started");
        }

        if (!stateInfos.isEmpty()) throw new IllegalStateException("Empty state is not allowed");

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
            if (info.parent != null) ensureExistingState(info.parent);
        }
    }

    /**
     * Start the state machine. The initial state can't be child state.
     *
     * @param initialState the first state of this machine. The state must be exact state object
     * setting up by {@link #addAllStates}, not a copy of it.
     */
    public final void start(@NonNull final State initialState) {
        ensureCorrectThread();
        ensureExistingState(initialState);

        final StateInfo stateInfo = mStateInfo.get(initialState);
        if (stateInfo.parent != null) {
            throw new IllegalStateException("Initial state can't be a child state");
        }
        mDestState = initialState;
        performTransitions();
        // If calling sendSelfMessage inside initialState#enter(), mSelfMsgQueue must be processed.
        maybeProcessSelfMessageQueue();
    }

    /**
     * Process the message synchronously then perform state transition.
     */
    public final void processMessage(int what, int arg1, int arg2, @Nullable Object obj) {
        ensureCorrectThread();

        if (mCurrentlyProcessing != Integer.MIN_VALUE) {
            throw new IllegalStateException("Message(" + mCurrentlyProcessing
                    + ") is still being processed");
        }

        mCurrentlyProcessing = what;
        processMsgAndPerformTransition(Message.obtain(null, what, arg1, arg2, obj));
        maybeProcessSelfMessageQueue();

        mCurrentlyProcessing = Integer.MIN_VALUE;
    }

    private void maybeProcessSelfMessageQueue() {
        while (!mSelfMsgQueue.isEmpty()) {
            processMsgAndPerformTransition(mSelfMsgQueue.poll());
        }
    }

    private void processMsgAndPerformTransition(@NonNull final Message msg) {
        processMsgInternal(mStateInfo.get(mCurrentState), msg);
        msg.recycle();

        performTransitions();
    }

    private void processMsgInternal(@NonNull final StateInfo si, @NonNull final Message msg) {
        mSelfMsgAllowed = true;

        // Ideally this should compare with IState.HANDLED, but it is not public field so just
        // checking whether the return value is true (IState.HANDLED = true).
        if (si.state.processMessage(msg)) {
            if (mDbg) Log.d(mName, "State " + si.state + " processed message " + msg.what);
            return;
        }

        if (null != si.parent) {
            processMsgInternal(mStateInfo.get(si.parent), msg);
        } else {
            if (mDbg) Log.d(mName, "Message " + msg.what + " was not handled");
        }
        mSelfMsgAllowed = false;
    }

    /**
     * Send self message during state transition.
     *
     * Must only be used inside State processMessage, enter or exist. The typical use case is
     * something wrong happens during state transition, sending an error message which would be
     * handled after finishing current state transitions.
     */
    public final void sendSelfMessage(int what, int arg1, int arg2, Object obj) {
        if (!mSelfMsgAllowed) {
            throw new IllegalStateException("sendSelfMessage can only be called inside State");
        }

        mSelfMsgQueue.add(Message.obtain(null, what, arg1, arg2, obj));
    }

    /**
     * Transition to destination state. Upon returning from processMessage the automaton will
     * transition to the given destination state.
     *
     * This function can NOT be called inside the State enter and exit function. The transition
     * target is always defined and can never be changed mid-way of state transition.
     *
     * @param destState will be the state to transition to. The state must be the same instance set
     * up by {@link #addAllStates}, not a copy of it.
     */
    public final void transitionTo(@NonNull final State destState) {
        if (mDbg) Log.d(mName, "transitionTo " + destState);
        ensureCorrectThread();
        ensureExistingState(destState);

        if (mDestState != mCurrentState) {
            mDestState = destState;
        } else {
            throw new IllegalStateException("Destination already specified");
        }
    }

    private void performTransitions() {
        // 1. Determine the common ancestor state of current/destination states
        // 2. Invoke state exit list from current state to common ancestor state.
        // 3. Invoke state enter list from common ancestor state to destState by going
        // through mEnterStateStack.
        if (mDestState == mCurrentState) return;

        mSelfMsgAllowed = true;
        final StateInfo commonAncestor = getLastActiveAncestor(mStateInfo.get(mDestState));

        executeExitMethods(commonAncestor, mStateInfo.get(mCurrentState));
        executeEnterMethods(commonAncestor, mStateInfo.get(mDestState));
        mCurrentState = mDestState;
        mSelfMsgAllowed = false;
    }

    // Null is the root of all states.
    private StateInfo getLastActiveAncestor(@Nullable final StateInfo start) {
        if (null == start || start.mActive) return start;

        return getLastActiveAncestor(mStateInfo.get(start.parent));
    }

    // Call the exit method from current state to common ancestor state.
    private void executeExitMethods(@Nullable StateInfo end, @Nullable StateInfo start) {
        if (end == start) return;

        if (mDbg) Log.d(mName, start.state + " exit()");
        start.state.exit();
        start.mActive = false;
        executeExitMethods(end, mStateInfo.get(start.parent));
    }

    // Call the enter method from common ancestor state to destination state.
    private void executeEnterMethods(@Nullable StateInfo end, @Nullable StateInfo dest) {
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

    private void ensureExistingState(@NonNull final State state) {
        if (!mStateInfo.containsKey(state)) throw new IllegalStateException("Invalid state");
    }
}
