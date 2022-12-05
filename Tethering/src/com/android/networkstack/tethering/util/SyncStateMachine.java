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

import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.android.internal.util.State;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * A synchronous state machine which should always be called with the same thread.
 */
public class SyncStateMachine {
    /**
     * Process message successful and the state would be transition given destination state if has.
     */
    public static final int SUCCESSFUL = 0;
    /** The message is not processed. */
    public static final int UNHANDLED = 1;
    /** ProcessMessage fail and the state would be reset to initial state. */
    public static final int FAILED = 2;
    private static final int DEFAULT_CAPACITY = 1;

    private final String mName;
    private final boolean mDbg;
    private final Looper mLooper;
    private final HashMap<SyncState, StateInfo> mStateInfo = new HashMap<SyncState, StateInfo>();
    private final ArrayList<StateInfo> mEnterStateStack = new ArrayList<>(DEFAULT_CAPACITY);

    /**
     * Information about a state.
     * Used to maintain the hierarchy.
     */
    private class StateInfo {
        public SyncState state;
        public StateInfo parentStateInfo;
        /** True when the state has been entered and on the stack */
        public boolean active;
    }

    private SyncState mInitialState;
    private SyncState mCurrentState;
    private boolean mStarted;


    /**
     * The extened state class which provide syncEnter/syncExit/processSyncMessage methods for
     * returning the result.
     */
    public static class SyncState extends State {
        @Override
        public boolean processMessage(Message msg) {
            return processSyncMessage(msg.what, msg.arg1, msg.arg2, msg.obj) != UNHANDLED;
        }

        @Override
        public void enter() {
            syncEnter();
        }

        @Override
        public void exit() {
            syncExit();
        }

        /** Called when the message is processed by this state. */
        public int processSyncMessage(final int what, final int arg1, final int arg2,
                final Object obj) {
            return UNHANDLED;
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
    public SyncStateMachine(final String name, final Looper looper) {
        this(name, looper, false /* debug */);
    }

    public SyncStateMachine(final String name, final Looper looper, final boolean dbg) {
        mName = name;
        mLooper = looper;
        mDbg = dbg;
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
        if (mDbg) Log.d(mName, "addState(" + state + ", " + parent + ")");
        ensureNotStarted();

        final StateInfo parentStateInfo;
        if (parent != null) {
            // The parent must be added before child state.
            ensureValidState(parent);
            parentStateInfo = mStateInfo.get(parent);
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
    public final void setInitialState(final SyncState initialState) {
        ensureNotStarted();
        ensureValidState(initialState);

        mInitialState = initialState;
    }

    /**
     * Process the message synchronously.
     * Note: this should not a called inside State.
     */
    public final int processMessage(int what, int arg1, int arg2, Object obj) {
        return processMessage(what, arg1, arg2, obj, mCurrentState);
    }

    /**
     * Process the message synchronously. If the message is processed successfully, transition the
     * state to the destState. If current state already be destState, state transition would be
     * ignored. Note: this should not a called inside State.
     */
    public final int processMessage(final int what, final int arg1, final int arg2,
            final Object obj, final SyncState destState) {
        if (mDbg) Log.d(mName, "processMessage(" + what + ", " + destState + ")");

        ensureCurrentThread();
        ensureStarted();
        ensureValidState(destState);

        return SUCCESSFUL;
    }

    /** Whether the given result is fail. */
    public static boolean isFailed(final int result) {
        return result == FAILED;
    }

    /** Start state machine. */
    public final void start() {
        ensureCurrentThread();
        // Make sure initial state already be set.
        ensureValidState(mInitialState);
        ensureNotStarted();

        mStarted = true;
        doTransitions(mInitialState, UNHANDLED);
    }

    private int doTransitions(SyncState target, final int defaultResult) {
        boolean hasFailed = isFailed(defaultResult);
        int result = defaultResult;
        while (mCurrentState != target) {
            result = transitionTo(target);
            if (mDbg) Log.d(mName, "transitionTo(" + target + "), now: " + mCurrentState);

            if (isFailed(result)) {
                target = mInitialState;
                hasFailed = true;
            }
        }

        return hasFailed ? FAILED : result;
    }

    private int transitionTo(SyncState destState) {
        // 1. Determine the common ancestor state of current/destination states
        // 2. Invoke state exit list from current state to common acestor state.
        // 3. Invoke state enter list from common ancestor state to destState by going
        // through mEnterStateStack.
        // 4. State tranistions would be interrupted if any of state exit/enter fail.
        final StateInfo commonStateInfo = setupEnterStackOfInactiveState(destState);
        try {
            return invokeEnterMethods(invokeExitMethods(commonStateInfo));
        } finally {
            mEnterStateStack.clear();
        }
    }

    private StateInfo setupEnterStackOfInactiveState(final SyncState destState) {
        // Search up the parent list of the destination state for an active
        // state.
        StateInfo curStateInfo = mStateInfo.get(destState);
        while ((curStateInfo != null) && !curStateInfo.active) {
            mEnterStateStack.add(curStateInfo);
            curStateInfo = curStateInfo.parentStateInfo;
        }

        // common ancestor state of current state and destination state. Current state or
        // destination state could be common ancestor if they are in the same parent list.
        return curStateInfo;
    }

    // Call the exit method from current state to common ancestor state.
    private int invokeExitMethods(final StateInfo commonStateInfo) {
        int result = UNHANDLED;
        StateInfo stateInfo = mStateInfo.get(mCurrentState);
        while (stateInfo != null && stateInfo != commonStateInfo && !isFailed(result)) {
            if (mDbg) Log.d(mName, stateInfo.state + " exit()");
            result = stateInfo.state.syncExit();
            stateInfo.active = false;
            stateInfo = stateInfo.parentStateInfo;
        }

        mCurrentState = stateInfo != null ? stateInfo.state : null;
        return result;
    }

    // Call the enter method from common ancestor state to destination state.
    private int invokeEnterMethods(final int invokeExitResult) {
        int result = invokeExitResult;
        while (!mEnterStateStack.isEmpty() && !isFailed(result)) {
            final StateInfo stateInfo = mEnterStateStack.remove(mEnterStateStack.size() - 1);
            if (mDbg) Log.d(mName, stateInfo.state + " enter()");
            result = stateInfo.state.syncEnter();
            stateInfo.active = true;
            mCurrentState = stateInfo.state;
        }

        return result;
    }

    private void ensureCurrentThread() {
        if (!mLooper.isCurrentThread()) throw new IllegalStateException("Called from wrong thread");
    }

    private void ensureNotStarted() {
        if (mStarted) throw new IllegalStateException("State machine already started");
    }

    private void ensureStarted() {
        if (!mStarted) throw new IllegalStateException("State machine doesn't started yet");
    }

    private void ensureValidState(final SyncState state) {
        if (!mStateInfo.containsKey(state)) throw new IllegalStateException("Invalid state");
    }
}
