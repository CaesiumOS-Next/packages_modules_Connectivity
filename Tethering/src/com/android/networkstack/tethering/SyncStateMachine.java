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
        /** The state */
        State state;

        /** The parent of this state, null if there is no parent */
        StateInfo parentStateInfo;

        /** True when the state has been entered and on the stack */
        boolean active;

        /**
         * Convert StateInfo to string
         */
        @Override
        public String toString() {
            return "state=" + state.getName() + ",active=" + active + ",parent="
                    + ((parentStateInfo == null) ? "null" : parentStateInfo.state.getName());
        }
    }

    private final HashMap<State, StateInfo> mStateInfo = new HashMap<State, StateInfo>();
    private State mInitialState;
    private State mDestState;

    /**
     * Constructor.
     *
     * @param name of the state machine
     */
    protected SyncStateMachine(final String name) {
        mName = name;
    }

    private int determineStackDepth() {
        int maxDepth = 0;
        for (StateInfo si : mStateInfo.values()) {
            int depth = 0;
            for (StateInfo i = si; i != null; depth++) {
                i = i.parentStateInfo;
            }
            if (maxDepth < depth) {
                maxDepth = depth;
            }
        }

        return maxDepth;
    }

    /**
     *  Initialize the state stack.
     */
    private final void initStateStack() {
        // Determine the maximum depth of the state hierarchy
        // so we can allocate the state stacks.
        final int maxDepth = determineStackDepth();

        mStateStack = new StateInfo[maxDepth];
        mTempStateStack = new StateInfo[maxDepth];

        StateInfo curStateInfo = mStateInfo.get(mInitialState);
        for (mTempStateStackCount = 0; curStateInfo != null; mTempStateStackCount++) {
            mTempStateStack[mTempStateStackCount] = curStateInfo;
            curStateInfo = curStateInfo.parentStateInfo;
        }

        // Empty the StateStack
        mStateStackTopIndex = -1;

        moveTempStateStackToStateStack();
    }

    /**
     * Call the exit method for each state from the top of stack
     * up to the common ancestor state.
     */
    private void invokeExitMethods(final StateInfo commonStateInfo) {
        while ((mStateStackTopIndex >= 0)
                && (mStateStack[mStateStackTopIndex] != commonStateInfo)) {
            State curState = mStateStack[mStateStackTopIndex].state;
            log("invokeExitMethods: " + curState.getName());
            curState.exit();
            mStateStack[mStateStackTopIndex].active = false;
            mStateStackTopIndex -= 1;
        }
    }

    /**
     * Invoke the enter method starting at the entering index to top of state stack
     */
    private void invokeEnterMethods(int stateStackEnteringIndex) {
        for (int i = stateStackEnteringIndex; i <= mStateStackTopIndex; i++) {
            log("invokeEnterMethods: " + mStateStack[i].state.getName());
            mStateStack[i].state.enter();
            mStateStack[i].active = true;
        }
    }

    protected void enterInitialState() {
        invokeEnterMethods(0);
    }

    /**
     * Move the contents of the temporary stack to the state stack
     * reversing the order of the items on the temporary stack as
     * they are moved.
     *
     * @return index into mStateStack where entering needs to start
     */
    private final int moveTempStateStackToStateStack() {
        int startingIndex = mStateStackTopIndex + 1;
        int i = mTempStateStackCount - 1;
        int j = startingIndex;
        while (i >= 0) {
            log("moveTempStackToStateStack: i=" + i + ",j=" + j);
            mStateStack[j] = mTempStateStack[i];
            j += 1;
            i -= 1;
        }

        mStateStackTopIndex = j - 1;
        log("moveTempStackToStateStack: X mStateStackTop=" + mStateStackTopIndex
                + ",startingIndex=" + startingIndex + ",Top="
                + mStateStack[mStateStackTopIndex].state.getName());
        return startingIndex;
    }

    /**
     * Setup the mTempStateStack with the states we are going to enter.
     *
     * This is found by searching up the destState's ancestors for a
     * state that is already active i.e. StateInfo.active == true.
     * The destStae and all of its inactive parents will be on the
     * TempStateStack as the list of states to enter.
     *
     * @return StateInfo of the common ancestor for the destState and
     * current state or null if there is no common parent.
     */
    private final StateInfo setupTempStateStackWithStatesToEnter(State destState) {
        /**
         * Search up the parent list of the destination state for an active
         * state. Use a do while() loop as the destState must always be entered
         * even if it is active. This can happen if we are exiting/entering
         * the current state.
         */
        mTempStateStackCount = 0;
        StateInfo curStateInfo = mStateInfo.get(destState);
        do {
            mTempStateStack[mTempStateStackCount++] = curStateInfo;
            curStateInfo = curStateInfo.parentStateInfo;
        } while ((curStateInfo != null) && !curStateInfo.active);

        log("setupTempStateStackWithStatesToEnter: X mTempStateStackCount="
                + mTempStateStackCount + ",curStateInfo: " + curStateInfo);
        return curStateInfo;
    }

    public final void addState(State state) {
        addState(state, null);
    }

    /**
     * Add a new state to the state machine. Bottom up addition
     * of states is allowed but the same state may only exist
     * in one hierarchy.
     *
     * @param state the state to add
     * @param parent the parent of state
     */
    public final void addState(State state, State parent) {
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
            mStateInfo.put(state, stateInfo);
        }

        // Validate that we aren't adding the same state in two different hierarchies.
        if ((stateInfo.parentStateInfo != null)
                && (stateInfo.parentStateInfo != parentStateInfo)) {
            throw new RuntimeException("state already added");
        }
        stateInfo.state = state;
        stateInfo.parentStateInfo = parentStateInfo;
        stateInfo.active = false;
    }

    /**
     * Remove a state from the state machine. Will not remove the state if it is currently
     * active or if it has any children in the hierarchy.
     * @param state the state to remove
     */
    public final void removeState(State state) {
        StateInfo stateInfo = mStateInfo.get(state);
        if (stateInfo == null || stateInfo.active) {
            return;
        }
        boolean isParent = mStateInfo.values().stream()
                .filter(si -> si.parentStateInfo == stateInfo)
                .findAny()
                .isPresent();
        if (isParent) {
            return;
        }
        mStateInfo.remove(state);
    }

    public final void setInitialState(State initialState) {
        log("setInitialState: initialState=" + initialState.getName());
        mInitialState = initialState;
    }

    public final void transitionTo(State destState) {
        if (mDestState != null) {
            Log.wtf(mName, "transitionTo called while transition already in progress to " +
                    mDestState + ", new target state=" + destState);
            return;
        }

        mDestState = destState;
    }

    public final void setError(Runnable action) {
        if (mErrorHandling != null) {
            Log.wtf(mName, "Replace an unhandled error");
        }
        mErrorHandling = action;
    }

    private void handleError() {
        mErrorHandling.run();
        mErrorHandling = null;
    }

    /**
     * Log for debug
     *
     * @param s is string log
     */
    public void log(String s) { }

    private void doStateTransition() {
        // Determine the states to exit and enter and return the
        // common ancestor state of the enter/exit states. Then
        // invoke the exit methods then the enter methods.
        log("transitionTo: destState=" + mDestState.getName());
        StateInfo commonStateInfo = setupTempStateStackWithStatesToEnter(mDestState);
        invokeExitMethods(commonStateInfo);
        int stateStackEnteringIndex = moveTempStateStackToStateStack();
        invokeEnterMethods(stateStackEnteringIndex);
        mDestState = null;

        handleError();
    }

    /**
     * Process the message. If the current state doesn't handle it, call the states parent and so
     * on. If it is never handled then return false.
     */
    private boolean processMsg(final Message msg) {
        StateInfo curStateInfo = mStateStack[mStateStackTopIndex];
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

    public boolean sendMessage(int what, int arg1, int arg2, Object obj) {
        return sendMessage(Message.obtain(null, what, arg1, arg2, obj));
    }

    public final boolean sendMessage(Message msg) {
        log("processMessage: E msg.what=" + msg.what);

        if (mStateStack == null) {
            throw new RuntimeException("SyncStateMachine doesn't started, msg: " + msg);
        }

        if (mIsInProcess) {
            throw new RuntimeException("Do not support recursive processMessage");
        }
        mIsInProcess = true;

        final boolean msgProcessed = processMsg(msg);

        doStateTransition();

        if (mQuit) cleanup();

        mIsInProcess = false;
        log("processMessage: X");

        return msgProcessed;
    }

    /** Start state machine. */
    public final void start() {
        initStateStack();
        enterInitialState();
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
        mStateStack = null;
        mTempStateStack = null;
        mStateInfo.clear();
        mInitialState = null;
        mDestState = null;
    }

}
