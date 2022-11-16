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
import com.android.internal.util.StateMachine;
import com.android.networkstack.tethering.util.SyncStateMachine.StateInfo;

import java.util.List;

/** A wrapper to decide whether use synchronous state machine for tethering. */
public class StateMachineShim {
    /** A flag whether using synchronous or asynchronous state machine. */
    public static final boolean USE_SYNC_SM = false;

    private final StateMachine mAsyncSM;
    private final SyncStateMachine mSyncSM;

    public StateMachineShim(final String name, final Looper looper) {
        if (USE_SYNC_SM) {
            mAsyncSM = null;
            mSyncSM = new SyncStateMachine(name, Thread.currentThread());
        } else {
            mAsyncSM = new AsyncStateMachine(name, looper);
            mSyncSM = null;
        }
    }

    public StateMachineShim(final String name, final Thread unstartedThread) {
        mAsyncSM = null;
        mSyncSM = new SyncStateMachine(name, unstartedThread);
    }

    /** Start the state machine */
    public void start(final State initialState) {
        if (USE_SYNC_SM) {
            mSyncSM.start(initialState);
        } else {
            mAsyncSM.setInitialState(initialState);
            mAsyncSM.start();
        }
    }

    /** Add states to state machine. */
    public void addAllStates(final List<StateInfo> stateInfos) {
        if (USE_SYNC_SM) {
            mSyncSM.addAllStates(stateInfos);
        } else {
            for (final StateInfo info : stateInfos) {
                mAsyncSM.addState(info.state, info.parent);
            }
        }
    }

    /** Transition to given state. */
    public void transitionTo(final State state) {
        if (USE_SYNC_SM) {
            mSyncSM.transitionTo(state);
        } else {
            mAsyncSM.transitionTo(state);
        }
    }

    /** Send message to state machine. */
    public void sendMessage(int what) {
        sendMessage(what, 0, 0, null);
    }

    /** Send message to state machine. */
    public void sendMessage(int what, Object obj) {
        sendMessage(what, 0, 0, obj);
    }

    /** Send message to state machine. */
    public void sendMessage(int what, int arg1) {
        sendMessage(what, arg1, 0, null);
    }

    /**
     * Send message to state machine.
     *
     * If using asynchronous state machine, enqueuing the message.
     * If using synchronous state machine, the message is processed right a away.
     */
    public void sendMessage(int what, int arg1, int arg2, Object obj) {
        if (USE_SYNC_SM) {
            mSyncSM.processMessage(what, arg1, arg2, obj);
        } else {
            mAsyncSM.sendMessage(what, arg1, arg2, obj);
        }
    }

    /**
     * Send message after delayMillis millisecond.
     *
     * This can only be used for asynchronous state machine.
     */
    public void sendMessageDelayedForAsyncSM(final int what, final long delayMillis) {
        if (USE_SYNC_SM) return;

        mAsyncSM.sendMessageDelayed(what, delayMillis);
    }

    // Since StateMachine.java only provide protected construtor, adding child class so that this
    // shim could create StateMachine instance.
    private class AsyncStateMachine extends StateMachine {
        AsyncStateMachine(final String name, final Looper looper) {
            super(name, looper);
        }
    }
}
