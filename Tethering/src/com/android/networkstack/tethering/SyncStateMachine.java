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

    private StateInfo mStateStack[];
    private int mStateStackTopIndex = -1;
    private StateInfo mTempStateStack[];
    private int mTempStateStackCount;
    private boolean mIsInProcess;
    private boolean mQuit;
    private Runnable mErrorHandling;

    /**
     * Information about a state.
     * Used to maintain the hierarchy.
     */
    private class StateInfo {
        State state;
        StateInfo childState;
        StateInfo parentStateInfo;

        boolean active = false;
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
    private State invokeExitMethods() {
        StateInfo topStateInfo = mStateInfo.getCommonAncestorStateInfo(mDstState);
        StateInfo stateInfo = mStateInfo.get(mCurrentState);

        while (stateInfo != null) {
            log("invokeExitMethods: " + stateInfo.state.getName());
            stateInfo.state.exit();
            stateInfo.active = false;

            if (stateInfo == topStateInfo) break;

            stateInfo = stateInfo.parentStateInfo;
        }

        return topStateInfo.state;
    }

    private StateInfo getCommonAncestorStateInfo(final State state) {
        StateInfo stateInfo = mStateInfo.get(state);

        while (stateInfo.parentStateInfo != null) {
            if (stateInfo.active) return stateInfo;

            stateInfo = stateInfo.parentStateInfo;
        }

        return stateInfo;
    }

    /**
     * Invoke the enter method starting at the entering index to top of state stack
     */
    private void invokeEnterMethods(final State state) {
        StateInfo stateInfo = mStateInfo.get(state);
        while (stateInfo != null) {
            log("invokeEnterMethods: " + stateInfo.state.getName());
            stateInfo.state.enter();
            stateInfo.active = true;

            if (stateInfo == mDestState) return;

            stateInfo = stateInfo.childState;
        }
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
        }

        StateInfo stateInfo = mStateInfo.get(state);
        if (stateInfo == null) {
            stateInfo = new StateInfo();
            stateInfo.state = state;
            mStateInfo.put(state, stateInfo);
        } else {
            throw new RuntimeException("Add duplicated state");
        }

        stateInfo.parentStateInfo = parentStateInfo;
        parentStateInfo.childStateInfo = stateInfo;
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

    public final boolean sendMessage(int what) {
        return sendMessage(what, 0, 0, null);
    }

    public final boolean sendMessage(int what, Object obj) {
        return sendMessage(what, 0, 0, obj);
    }

    public final boolean sendMessage(int what, int arg1) {
        return sendMessage(what, arg1, 0, null);
    }

    public final boolean sendMessage(int what, int arg1, int arg2) {
        return sendMessage(what, arg1, arg2, null);
    }

    public final boolean sendMessage(int what, int arg1, int arg2, Object obj) {
        return sendMessage(Message.obtain(null, what, arg1, arg2, obj));
    }

    public final boolean sendMessage(Message msg) {
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
        StateInfo curStateInfo = mStateInfo.get(mCurrentState);
        log("processMsg: " + curStateInfo.state.getName());

        while (!curStateInfo.state.processMessage(msg)) {
            // Not processed.
            curStateInfo = curStateInfo.parentStateInfo;
            if (curStateInfo == null) {
                // No parents left so it's not handled.
                return false;
            }
            log("processMsg: " + curStateInfo.state.getName());
        }

        return true;
    }

    private void doStateTransition() {
        // Determine the states to exit and enter and return the
        // common ancestor state of the enter/exit states. Then
        // invoke the exit methods then the enter methods.
        log("transitionTo: destState=" + mDestState.getName());
        State topState = invokeExitMethods();

        invokeEnterMethods(topState);

        mCurrentState = mDestState;
        mDestState = null;
    }

    /** Start state machine. */
    public final void start() {
        final State topState = mStateInfo.getLastParentStateInfo(mCurrentState);
        invokeEnterMethods(topState);
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
