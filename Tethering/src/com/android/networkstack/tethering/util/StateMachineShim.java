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

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.networkstack.tethering.util.SyncStateMachine.StateInfo;

import java.util.List;

/** A wrapper to decide whether use synchronous state machine for tethering. */
public class StateMachineShim {
    private final StateMachine mAsyncSM;
    private final SyncStateMachine mSyncSM;
    private final boolean mUseSyncSM;

    // This is only used for IpServer which always create under tethering thread.
    public StateMachineShim(final String name, final Looper looper, final boolean useSyncSM) {
        mUseSyncSM = useSyncSM;
        if (useSyncSM) {
            mAsyncSM = null;
            mSyncSM = new SyncStateMachine(name, Thread.currentThread());
        } else {
            mAsyncSM = new AsyncStateMachine(name, looper);
            mSyncSM = null;
        }
    }

    // TODO: adding construtor for mainSM which create before tethering thread started.

    @VisibleForTesting
    public StateMachineShim(final StateMachine async, final SyncStateMachine sync) {
        mAsyncSM = async;
        mSyncSM = sync;
        mUseSyncSM = (mSyncSM != null);
    }

    /** Start the state machine */
    public void start(final State initialState) {
        if (mUseSyncSM) {
            mSyncSM.start(initialState);
        } else {
            mAsyncSM.setInitialState(initialState);
            mAsyncSM.start();
        }
    }

    /** Add states to state machine. */
    public void addAllStates(final List<StateInfo> stateInfos) {
        if (mUseSyncSM) {
            mSyncSM.addAllStates(stateInfos);
        } else {
            for (final StateInfo info : stateInfos) {
                mAsyncSM.addState(info.state, info.parent);
            }
        }
    }

    /** Transition to given state. */
    public void transitionTo(final State state) {
        if (mUseSyncSM) {
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
        if (mUseSyncSM) {
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
        if (mUseSyncSM) return;

        mAsyncSM.sendMessageDelayed(what, delayMillis);
    }

    /**
     * An alias StateMahchine class with public construtor.
     *
     * Since StateMachine.java only provide protected construtor, adding child class so that this
     * shim could create StateMachine instance.
     */
    @VisibleForTesting
    public static class AsyncStateMachine extends StateMachine {
        public AsyncStateMachine(final String name, final Looper looper) {
            super(name, looper);
        }
    }
}
