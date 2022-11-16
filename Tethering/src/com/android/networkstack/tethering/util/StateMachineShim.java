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

import static com.android.networkstack.tethering.util.TetheringMessageBase.BASE_SM_SHIM;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import com.android.internal.util.StateMachine;
import com.android.networkstack.tethering.util.SyncStateMachine.SyncState;

/** A wrapper to decide whether use synchronous state machine for tethering. */
public class StateMachineShim {
    private static final boolean USE_SYNC_SM = true;
    private static final int DEFAULT_STATE_VALUE = -1;
    private static final int CMD_TRANSITION_TO_INITIAL_STATE = BASE_SM_SHIM + 1;
    private final AsyncStateMachine mAsyncSM;
    private SyncStateMachine mSyncSM;
    private final Handler mHandler;
    private final StateShim mInitialState;
    protected int mStateValue = DEFAULT_STATE_VALUE;

    /** Constructor. */
    public StateMachineShim(final String name, final Looper looper, final StateShim initialState) {
        mInitialState = initialState;
        if (USE_SYNC_SM) {
            mAsyncSM = null;
            mHandler = new Handler(looper);
            mHandler.post(() -> mSyncSM = new SyncStateMachine(name, initialState));
        } else {
            mAsyncSM = new AsyncStateMachine(name, looper, initialState);
            mAsyncSM.start();
            mSyncSM = null;
            mHandler = mAsyncSM.getHandler();
        }
    }

    /** Get handler which the state machine running on. */
    public Handler getHandler() {
        return mHandler;
    }

    /** Add state to state machine. */
    public void addState(final StateShim state) {
        addState(state, DEFAULT_STATE_VALUE);
    }

    /** Add state with stateValue to state machine. */
    public void addState(final StateShim state, final int stateValue) {
        state.setStateValue(stateValue);
        addState(state, null);
    }

    /** Add state and specify its parent state. */
    public void addState(final StateShim child, final StateShim parent) {
        if (parent != null) child.setStateValue(parent.mStateValue);

        if (USE_SYNC_SM) {
            mSyncSM.addState(child, parent);
        } else {
            mAsyncSM.addState(child, parent);
        }
    }

    /** Transition to given state. */
    public void transitionTo(final StateShim state) {
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
     * Send message and state machine would transition to given state after processed the message.
     */
    public void sendMessage(int what, int arg1, int arg2, Object obj) {
        if (USE_SYNC_SM) {
            if (processMessage(what, arg1, arg2, obj) == SyncStateMachine.ERROR) handleError();
        } else {
            mAsyncSM.sendMessage(what, arg1, arg2, obj);
        }
    }

    private int processMessage(int what, int arg1, int arg2, Object obj) {
        final int preRootState = mStateValue;
        try {
            return mSyncSM.processMessage(what, arg1, arg2, obj);
        } finally {
            // Notify root state is changed if current state is transitioned from different state
            // tree.
            if (preRootState != mStateValue) notifyRootStateChange(mStateValue);
        }
    }

    /** Error handling that transition the state to initial state. */
    public void handleError() {
        if (USE_SYNC_SM) {
            processMessage(CMD_TRANSITION_TO_INITIAL_STATE, 0, 0, null);
        } else {
            mAsyncSM.transitionTo(mInitialState);
        }
    }

    /** Send message after delayMillis millisecond. */
    public void sendMessageDelayed(final int what, final long delayMillis) {
        if (USE_SYNC_SM) {
            mHandler.postDelayed(() -> sendMessage(what), delayMillis);
        } else {
            mAsyncSM.sendMessageDelayed(what, delayMillis);
        }
    }

    /** A callback to notify root state is changed. */
    public void notifyRootStateChange(final int state) { }

    public static class StateShim extends SyncState {
        private final StateMachineShim mSM;
        private int mStateValue;

        public StateShim(final StateMachineShim sm) {
            mSM = sm;
        }

        @Override
        public void enter() {
            if (USE_SYNC_SM) {
                throw new IllegalStateException("SyncSM should call syncEnter directly");
            }

            syncEnter();
            if (mSM.mStateValue != mStateValue) mSM.notifyRootStateChange(mStateValue);
        }

        @Override
        public void exit() {
            if (USE_SYNC_SM) {
                throw new IllegalStateException("SyncSM should call syncExit directly");
            }

            syncExit();
        }

        @Override
        public boolean processMessage(Message msg) {
            return processMessage(msg.what, msg.arg1, msg.arg2, msg.obj);
        }

        private void setStateValue(final int value) {
            mStateValue = value;
        }
    }

    private class AsyncStateMachine extends StateMachine {
        AsyncStateMachine(final String name, final Looper looper, final StateShim initialState) {
            super(name, looper);
            addState(initialState);
            setInitialState(initialState);
        }
    }
}
