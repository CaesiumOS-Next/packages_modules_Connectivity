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

import com.android.internal.util.State;

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

    private final String mName;
    private final boolean mDbg;
    private final Looper mLooper;

    private SyncState mInitialState;
    private SyncState mCurrentState;


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
    }

    /** Set initial state. */
    public final void setInitialState(final SyncState initialState) {
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
        ensureCurrentThread();

        return SUCCESSFUL;
    }

    /** Whether the given result is fail. */
    public static boolean isFailed(final int result) {
        return result == FAILED;
    }

    /** Start state machine. */
    public final void start() {
        ensureCurrentThread();
    }

    private void ensureCurrentThread() {
        if (!mLooper.isCurrentThread()) throw new IllegalStateException("Called from wrong thread");
    }
}
