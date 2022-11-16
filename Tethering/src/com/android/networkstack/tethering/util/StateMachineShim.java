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

import static android.net.TetheringManager.TETHER_ERROR_NO_ERROR;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import com.android.internal.util.StateMachine;
import com.android.networkstack.tethering.util.SyncStateMachine.SyncState;

/** A wrapper to decide whether use synchronous state machine for tethering. */
public class StateMachineShim {
    public static final boolean USE_SYNC_SM = true;
    private final AsyncStateMachine mAsyncSM;
    private final SyncStateMachine mSyncSM;
    private final Handler mHandler;

    public StateMachineShim(final String name, final Handler handler) {
        if (USE_SYNC_SM) {
            mAsyncSM = null;
            mHandler = handler;
            mSyncSM = new SyncStateMachine(name, handler.getLooper().getThread(),
                    TETHER_ERROR_NO_ERROR);
        } else {
            mAsyncSM = new AsyncStateMachine(name, handler.getLooper());
            mSyncSM = null;
            mHandler = mAsyncSM.getHandler();
        }
    }

    /** Get handler which the state machine running on. */
    public Handler getHandler() {
        return mHandler;
    }

    /** Start the state machine */
    public void start(final StateShim initialState) {
        if (!USE_SYNC_SM) {
            mAsyncSM.setInitialState(initialState);
            mAsyncSM.start();
            return;
        }

        if (!mHandler.getLooper().isCurrentThread()) {
            mHandler.post(() -> mSyncSM.start(initialState));
        } else {
            mSyncSM.start(initialState);
        }
    }

    /** Add state to state machine. */
    public void addState(final StateShim state) {
        addState(state, null);
    }

    /** Add state and specify its parent state. */
    public void addState(final StateShim child, final StateShim parent) {
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
            processMessageForSyncSM(what, arg1, arg2, obj);
        } else {
            mAsyncSM.sendMessage(what, arg1, arg2, obj);
        }
    }

    protected int processMessageForSyncSM(int what, int arg1, int arg2, Object obj) {
        return mSyncSM.processMessage(what, arg1, arg2, obj);
    }

    /** Send message after delayMillis millisecond. */
    public void sendMessageDelayed(final int what, final long delayMillis) {
        if (USE_SYNC_SM) {
            mHandler.postDelayed(() -> sendMessage(what), delayMillis);
        } else {
            mAsyncSM.sendMessageDelayed(what, delayMillis);
        }
    }

    public static class StateShim extends SyncState {
        @Override
        public void enter() {
            throwIfSyncSM();

            enter(TETHER_ERROR_NO_ERROR);
        }

        @Override
        public void exit() {
            throwIfSyncSM();

            exit(TETHER_ERROR_NO_ERROR);
        }

        @Override
        public boolean processMessage(Message msg) {
            throwIfSyncSM();

            return processMessage(msg.what, msg.arg1, msg.arg2, msg.obj);
        }

        private static void throwIfSyncSM() {
            if (USE_SYNC_SM) throw new IllegalStateException("Using SyncSM by AsyncSM");
        }
    }

    private class AsyncStateMachine extends StateMachine {
        AsyncStateMachine(final String name, final Looper looper) {
            super(name, looper);
        }
    }
}
