/*
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

import com.android.internal.util.State;

/** A wrapper to decide whether use synchronous state machine for tethering. */
public class StateMachineShim {
    private static final boolean USE_SYNC_SM = true;
    private final AsyncStateMachine mAsyncSM;
    private final SyncStateMachine mSyncSM;
    private State mInitialState;

    /** Constructor. */
    public StateMachineShim(final String name, final Looper looper) {
        if (USE_SYNC_SM) {
            mSyncSM = new SyncStateMachine(name);
            mAsyncSM = null;
        } else {
            mAsyncSM = new AsyncStateMachine(name, looper);
            mSyncSM = null;
        }

    }

    /** Start state machine. */
    public void start() {
        if (USE_SYNC_SM) {
            mSyncSM.start();
        } else {
            mAsyncSM.start();
        }
    }

    /** Add state to state machine. */
    public void addState(final State state) {
        addState(state, null);
    }

    /** Add state and specify its parent state. */
    public void addState(final State child, final State parent) {
        if (USE_SYNC_SM) {
            mSyncSM.addState(child, parent);
        } else {
            mAsyncSM.addState(child, parent);
        }
    }

    /** Specify initial state. */
    public void setInitialState(final State state) {
        if (USE_SYNC_SM) {
            mSyncSM.setInitialState(state);
        } else {
            mAsyncSM.setInitialState(state);
            mInitialState = state;
        }
    }

    /** Transition to given state and this would be no-op for synchronous state machine. */
    public void transitionTo(final State state) {
        if (USE_SYNC_SM) return;

        mAsyncSM.transitionTo(state);
    }

    /** Send message to state machine. */
    public boolean sendMessage(int what) {
        return sendMessage(what, 0, 0, null);
    }

    /** Send message to state machine. */
    public boolean sendMessage(int what, int arg1, int arg2, Object obj) {
        return sendMessage(what, arg1, arg2, obj, null /* destState */);
    }

    /**
     * Send message and state machine would transition to given state after processed the message.
     * Asynchronous state machine would ignore destState paramter.
     */
    public boolean sendMessage(int what, int arg1, int arg2, Object obj, final State destState) {
        if (USE_SYNC_SM) {
            if (destState == null) {
                return mSyncSM.processMessage(what, arg1, arg2, obj);
            } else {
                return mSyncSM.processMessage(what, arg1, arg2, obj, destState);
            }
        } else {
            mAsyncSM.sendMessage(what, arg1, arg2, obj);
            return false;
        }
    }

    /** Reset the state back to initial state. */
    public void resetState() {
        if (USE_SYNC_SM) {
            mSyncSM.resetState();
        } else {
            mAsyncSM.transitionTo(mInitialState);
        }
    }

    private class AsyncStateMachine extends StateMachine {
        AsyncStateMachine(final String name, final Looper looper) {
            super(name, looper);
        }
    }
}
