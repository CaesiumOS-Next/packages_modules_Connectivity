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

import android.os.Message;
import android.util.Log;

import com.android.internal.util.State;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * A synchronous state machine which should always be called with the same thread.
 */
public class SyncStateMachine {
    private static final int DEFAULT_CAPACITY = 1;
    private final String mName;
    private final boolean mDbg;
    private boolean mIsInProcess;
    private boolean mIsInStateTransition;
    private boolean mBackToInitialState;
    private boolean mStarted;

    /**
     * Information about a state.
     * Used to maintain the hierarchy.
     */
    private class StateInfo {
        public State state;
        public StateInfo parentStateInfo;
        /** True when the state has been entered and on the stack */
        public boolean active;
    }


    private final HashMap<State, StateInfo> mStateInfo = new HashMap<State, StateInfo>();
    private final ArrayList<StateInfo> mEnterStateStack = new ArrayList<>(DEFAULT_CAPACITY);
    private State mInitialState;
    private State mCurrentState;

    /**
     * Constructor.
     *
     * @param name of the state machine
     */
    public SyncStateMachine(final String name) {
        this(name, false /* debug */);
    }

    public SyncStateMachine(final String name, final boolean dbg) {
        mName = name;
        mDbg = dbg;
    }

    /** Add a new state to the state machine. */
    public final void addState(final State state) {
        addState(state, null);
    }

    /**
     * Add a new state to the state machine. Bottom up addition
     * of states is not allowed.
     *
     * @param state the state to add
     * @param parent the parent of state
     */
    public final void addState(final State state, final State parent) {
        if (mDbg) Log.d(mName, "addState(" + state + ", " + parent + ")");
        ensureNotStarted();

        final StateInfo parentStateInfo;
        if (parent != null) {
            parentStateInfo = mStateInfo.get(parent);
            if (parentStateInfo == null) {
                throw new IllegalStateException("The parent must be added before child state");
            }
        } else {
            parentStateInfo = null;
        }

        if (!mStateInfo.containsKey(state)) {
            final StateInfo stateInfo = new StateInfo();
            stateInfo.state = state;
            stateInfo.parentStateInfo = parentStateInfo;
            mStateInfo.put(state, stateInfo);
        } else {
            throw new IllegalStateException("State already exist");
        }
    }

    /** Set initial state. */
    public final void setInitialState(final State initialState) {
        ensureNotStarted();
        mInitialState = initialState;
    }

    /**
     * Process the message synchronously.
     * Note: this should not a called inside State.
     *
     * @return process message successfully or not.
     */
    public final boolean processMessage(int what, int arg1, int arg2, Object obj) {
        return processMessage(what, arg1, arg2, obj, mCurrentState);
    }

    /**
     * Process the message synchronously. If the message is processed successfully, transition the
     * state to the destState. If current state already be destState, state transition would be
     * ignored. Note: this should not a called inside State.
     *
     * @return process message successfully or not.
     */
    public final boolean processMessage(final int what, final int arg1, final int arg2,
            final Object obj, final State destState) {
        if (mDbg) {
            Log.d(mName, "processMessage(" + what + ", " + destState + ")");
        }
        ensureStarted();

        if (mIsInProcess) {
            throw new IllegalStateException("Don't support recursive processMessage");
        }

        if (mIsInStateTransition) {
            throw new IllegalStateException("Don't support processMessage in state transition");
        }

        mIsInProcess = true;

        final boolean msgHandled = processMsg(Message.obtain(null, what, arg1, arg2, obj));

        if (msgHandled && !mBackToInitialState) {
            transitionTo(destState);
        }

        maybeBackToInitialState();

        if (mCurrentState == null) Log.wtf(mName, "current state is null");

        mIsInProcess = false;
        return msgHandled && mCurrentState == destState;
    }

    // Process the message. If the current state doesn't handle it, call the states parent and so
    // on. If it is never handled then return false.
    private boolean processMsg(final Message msg) {
        StateInfo curStateInfo = mStateInfo.get(mCurrentState);
        while (!curStateInfo.state.processMessage(msg)) {
            curStateInfo = curStateInfo.parentStateInfo;
            if (curStateInfo == null) return false;
        }

        return true;
    }

    private void maybeBackToInitialState() {
        while (mBackToInitialState) {
            mBackToInitialState = false;
            transitionTo(mInitialState);

            // mBackToInitialState may be true if resetState() is called in mInitialState#enter().
            // Since the current state already be mInitialState, just flag mBackToInitialState to
            // false.
            if (mCurrentState == mInitialState) {
                mBackToInitialState = false;
                return;
            }
        }
    }

    private void transitionTo(final State destState) {
        mIsInStateTransition = true;
        // Determine the states to exit and enter and return the
        // common ancestor state of the enter/exit states. Then
        // invoke the exit methods then the enter methods.
        final StateInfo commonStateInfo = setupEnterStackOfInactiveState(destState);
        invokeExitMethods(commonStateInfo);
        invokeEnterMethods();
        if (mDbg) Log.d(mName, "transitionTo(" + destState + "), result: " + mCurrentState);
        mIsInStateTransition = false;
    }

    private StateInfo setupEnterStackOfInactiveState(final State destState) {
         // Search up the parent list of the destination state for an active
         // state.
        StateInfo curStateInfo = mStateInfo.get(destState);
        while ((curStateInfo != null) && !curStateInfo.active) {
            mEnterStateStack.add(curStateInfo);
            curStateInfo = curStateInfo.parentStateInfo;
        }

        return curStateInfo;
    }

    // Call the exit method from current state to common ancestor state.
    private void invokeExitMethods(final StateInfo commonStateInfo) {
        StateInfo stateInfo = mStateInfo.get(mCurrentState);
        while (stateInfo != null && stateInfo != commonStateInfo) {
            if (mDbg) Log.d(mName, stateInfo.state + " exit()");
            stateInfo.state.exit();
            stateInfo.active = false;
            stateInfo = stateInfo.parentStateInfo;

            // Interrupt state exit list iteration. Also clear mEnterStateStack so that the follow
            // up #invokeEnterMethods do nothing.
            if (mBackToInitialState) {
                mEnterStateStack.clear();
                break;
            }
        }

        mCurrentState = stateInfo != null ? stateInfo.state : null;
    }

    // Call the enter method from common ancestor state to destination state.
    private void invokeEnterMethods() {
        while (!mEnterStateStack.isEmpty()) {
            final StateInfo stateInfo = mEnterStateStack.remove(mEnterStateStack.size() - 1);
            if (mDbg) Log.d(mName, stateInfo.state + " enter()");
            stateInfo.state.enter();
            stateInfo.active = true;
            mCurrentState = stateInfo.state;

            // Interrupt mEnterStateStack iteration because the state is force back to initial
            // state.
            if (mBackToInitialState) {
                mEnterStateStack.clear();
                break;
            }
        }
    }

    /** Start state machine. */
    public final void start() {
        if (mInitialState == null) throw new IllegalStateException("InitialState can't be null");

        mStarted = true;
        transitionTo(mInitialState);
        maybeBackToInitialState();
        mCurrentState = mInitialState;
    }

    private void ensureNotStarted() {
        if (mStarted) throw new IllegalStateException("State machine already started");
    }

    private void ensureStarted() {
        if (!mStarted) throw new IllegalStateException("State machine doesn't started yet");
    }

    /**
     * Reset the state back to initial state. If the current state already be initial state, nothing
     * will happen. If #processMessage doesn't complete yet, the state reset would be pending till
     * state transitions starting.
     */
    public final void resetState() {
        if (mDbg) Log.d(mName, "resetState()");
        ensureStarted();

        mBackToInitialState = true;
        if (mIsInProcess || mIsInStateTransition) return;

        maybeBackToInitialState();
    }
}
