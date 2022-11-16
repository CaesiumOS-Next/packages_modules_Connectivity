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

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import com.android.internal.util.StateMachine;
import com.android.networkstack.tethering.util.SyncStateMachine.StateInfo;
import com.android.networkstack.tethering.util.SyncStateMachine.SyncState;

import java.util.List;

/** A wrapper to decide whether use synchronous state machine for tethering. */
public class StateMachineShim {
    /** A flag whether using synchronous or asynchronous state machine. */
    public static final boolean USE_SYNC_SM = false;

    private final AsyncStateMachine mAsyncSM;
    private final SyncStateMachine mSyncSM;
    private final Handler mHandler;

    public StateMachineShim(final String name, final int defaultResult, final Handler handler) {
        if (USE_SYNC_SM) {
            mAsyncSM = null;
            mHandler = handler;
            mSyncSM = new SyncStateMachine(name, handler.getLooper().getThread(), defaultResult);
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
    public void start(final SyncState initialState) {
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
    public void transitionTo(final SyncState state) {
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

    // The user of SyncSM could override this function to handle the result of processMessage.
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

    private class AsyncStateMachine extends StateMachine {
        AsyncStateMachine(final String name, final Looper looper) {
            super(name, looper);
        }
    }
}
