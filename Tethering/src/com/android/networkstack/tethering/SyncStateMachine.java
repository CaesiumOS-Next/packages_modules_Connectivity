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

package com.android.internal.util;

import android.os.Message;
import android.util.Log;

import java.util.HashMap;

public class SyncStateMachine {
    private final String mName;
    private boolean mIsInProcess;
    private boolean mQuit;
    private Runnable mErrorHandling;

    /**
     * Information about a state.
     * Used to maintain the hierarchy.
     */
    private class StateInfo {
        State state;
        StateInfo parentStateInfo;
    }

    private final HashMap<State, StateInfo> mStateInfo = new HashMap<State, StateInfo>();
    private State mCurrentState;
    private State mDestState;

    /**
     * Constructor.
     *
     * @param name of the state machine
     */
    protected SyncStateMachine(final String name) {
        mName = name;
    }

    /**
     * Call the exit method for each state from the top of stack
     * up to the common ancestor state.
     */
    private void invokeExitMethods() {
        final StateInfo stateInfo = mStateInfo.get(mCurrentState);
        stateInfo.state.exit();

        final StateInfo parentInfo = stateInfo.parentStateInfo;
        if (parentInfo != null) {
            parentInfo.state.exit();
        }
    }

    /**
     * Invoke the enter method starting at the entering index to top of state stack
     */
    private void invokeEnterMethods(final State dstState) {
        final StateInfo stateInfo = mStateInfo.get(dstState);

        final StateInfo parentInfo = stateInfo.parentStateInfo;
        if (parentInfo != null) {
            parentInfo.state.enter();
        }

        stateInfo.state.enter();
    }

    protected final void addState(State state) {
        addState(state, null);
    }

    /**
     * Add a new state to the state machine. Bottom up addition
     * of states is not allowed.
     *
     * @param state the state to add
     * @param parent the parent of state
     */
    protected final void addState(State state, State parent) {
        log("addState: E state=" + state.getName() + ",parent="
                    + ((parent == null) ? "" : parent.getName()));
        StateInfo parentStateInfo = null;
        if (parent != null) {
            parentStateInfo = mStateInfo.get(parent);
            if (parentStateInfo == null) {
                throw new RuntimeException("The parent must be added before child state");
            }

            if (parentStateInfo.state != null) {
                throw new RuntimeException("3+ hierarchies is not supported");
            }
        }

        if (!mStateInfo.containsKey(state)) {
            final StateInfo stateInfo = new StateInfo();
            stateInfo.state = state;
            stateInfo.parentStateInfo = parentStateInfo;
            mStateInfo.put(state, stateInfo);
        } else {
            throw new RuntimeException("Add duplicated state");
        }
    }

    protected final void setInitialState(State initialState) {
        log("setInitialState: initialState=" + initialState.getName());
        mCurrentState = initialState;
    }

    protected final void transitionTo(State destState) {
        if (mDestState != null) {
            Log.wtf(mName, "transitionTo called while transition already in progress to " +
                    mDestState + ", new target state=" + destState);
            return;
        }

        mDestState = destState;
    }

    protected final void setError(Runnable action) {
        if (mErrorHandling != null) {
            Log.wtf(mName, "Replace an unhandled error");
        }
        mErrorHandling = action;
    }

    private void handleError() {
        if (mErrorHandling == null) return;

        mErrorHandling.run();
        mErrorHandling = null;
    }

    /**
     * Log for debug
     *
     * @param s is string log
     */
    protected void log(String s) { }

    public final boolean sendMessage(int what, int arg1, int arg2, Object obj) {
        return sendMessage(Message.obtain(null, what, arg1, arg2, obj));
    }

    private final boolean sendMessage(Message msg) {
        log("processMessage: E msg.what=" + msg.what);

        if (mIsInProcess) {
            throw new RuntimeException("Do not support recursive processMessage");
        }
        mIsInProcess = true;

        final boolean msgProcessed = processMsg(msg);

        while (mDestState != null) {
            doStateTransition();
            handleError();
        }

        if (mQuit) cleanup();

        mIsInProcess = false;
        log("processMessage: X");

        return msgProcessed;
    }

    /**
     * Process the message. If the current state doesn't handle it, call the states parent and so
     * on. If it is never handled then return false.
     */
    private boolean processMsg(final Message msg) {
        final StateInfo curInfo = mStateInfo.get(mCurrentState);
        log("processMsg: " + curInfo.state.getName());

        if (!curInfo.state.processMessage(msg)) {
            // Not processed.
            final StateInfo parentInfo = curInfo.parentStateInfo;
            if (parentInfo != null) {
                log("processMsg: " + parentInfo.state.getName());
                return parentInfo.state.processMessage(msg);
            }
        }

        return true;
    }

    private void doStateTransition() {
        // Determine the states to exit and enter and return the
        // common ancestor state of the enter/exit states. Then
        // invoke the exit methods then the enter methods.
        log("transitionTo: destState=" + mDestState.getName());
        invokeExitMethods();

        invokeEnterMethods(mDestState);

        mCurrentState = mDestState;
        mDestState = null;
    }

    /** Start state machine. */
    public final void start() {
        invokeEnterMethods(mCurrentState);
        mDestState = null;
        mQuit = false;
        mIsInProcess = false;
    }

    /**
     * Stop state machine.
     */
    public final void quit() {
        if (mIsInProcess) {
            mQuit = true;
            return;
        }

        cleanup();
    }

    private void cleanup() {
        mStateInfo.clear();
        mCurrentState = null;
        mDestState = null;
    }
}
