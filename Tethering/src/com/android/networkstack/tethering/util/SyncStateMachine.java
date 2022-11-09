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

import com.android.internal.util.State;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * A syncronous state machine which should always be called with the same thread.
 */
public class SyncStateMachine {
    private static final int DEFAULT_CAPACITY = 1;
    private final String mName;
    private boolean mIsInProcess;
    private boolean mBackToInitialState;

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
        mName = name;
    }

    private StateInfo setupEnterStackOfInactiveState(State destState) {
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
            stateInfo.state.exit();
            stateInfo.active = false;
            stateInfo = stateInfo.parentStateInfo;
        }
    }

    // Call the enter method from common ancestor state to destination state.
    private void invokeEnterMethods() {
        while (!mEnterStateStack.isEmpty()) {
            final StateInfo stateInfo = mEnterStateStack.remove(mEnterStateStack.size() - 1);
            stateInfo.state.enter();
            stateInfo.active = true;
        }
    }

    /** Add a new state to the state machine. */
    public final void addState(State state) {
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
        final StateInfo parentStateInfo;
        if (parent != null) {
            parentStateInfo = mStateInfo.get(parent);
            if (parentStateInfo == null) {
                throw new RuntimeException("The parent must be added before child state");
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
            throw new RuntimeException("state already exist");
        }
    }

    /** Set initial state. */
    public final void setInitialState(State initialState) {
        mInitialState = initialState;
    }

    /**
     * Process the message syncronously.
     * Note: this should not a called inside State.
     *
     * @return process message sucessfully or not.
     */
    public final boolean processMessage(int what, int arg1, int arg2, Object obj) {
        return processMessage(what, arg1, arg2, obj, null);
    }

    /**
     * Process the message syncronously. If the message is processed sucessfully, transition the
     * state to the destState. If current state already be destState, state transition would be
     * ignored. Note: this should not a called inside State.
     *
     * @return process message sucessfully or not.
     */
    public final boolean processMessage(int what, int arg1, int arg2, Object obj,
            final State destState) {
        if (mIsInProcess) {
            throw new RuntimeException("Do not support recursive processMessage");
        }

        mIsInProcess = true;

        final boolean msgHandled = processMsg(Message.obtain(null, what, arg1, arg2, obj));

        if (destState != null && msgHandled) transitionTo(destState);

        if (mBackToInitialState) {
            transitionTo(mInitialState);
            mBackToInitialState = false;
            mIsInProcess = false;
            return false;
        }

        mIsInProcess = false;
        return msgHandled;
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

    private void transitionTo(State destState) {
        // Determine the states to exit and enter and return the
        // common ancestor state of the enter/exit states. Then
        // invoke the exit methods then the enter methods.
        final StateInfo commonStateInfo = setupEnterStackOfInactiveState(destState);
        invokeExitMethods(commonStateInfo);
        invokeEnterMethods();
        mCurrentState = destState;
    }

    /** Start state machine. */
    public final void start() {
        setupEnterStackOfInactiveState(mInitialState);
        invokeEnterMethods();
        mCurrentState = mInitialState;
        mIsInProcess = false;
        mBackToInitialState = false;
    }

    /**
     * Reset the state back to initial state. If the current state already be initial state, nothing
     * will happen. If #processMessage doesn't complete yet, the state reset would be pending and be
     * executed at the end of #processMessage.
     */
    public final void resetState() {
        if (mIsInProcess) {
            mBackToInitialState = true;
            return;
        }

        transitionTo(mInitialState);
    }
}
