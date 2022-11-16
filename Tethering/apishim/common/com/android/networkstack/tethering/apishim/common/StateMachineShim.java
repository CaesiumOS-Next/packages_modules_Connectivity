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

package com.android.networkstack.tethering.apishim.common;

import android.os.Looper;
import android.os.Message;

import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.internal.util.SyncStateMachine;
import com.android.modules.utils.build.SdkLevel;

public class StateMachineShim {
    private static final boolean USE_SYNC_SM = SdkLevel.isAtLeastU();
    private final AsyncStateMachine mAsyncSM;
    private final SyncStateMachine mSyncSM;

    public StateMachineShim(final String name, final Looper looper) {
        if (USE_SYNC_SM) {
            mSyncSM = new SyncStateMachine(name);
            mAsyncSM = null;
        } else {
            mAsyncSM = new AsyncStateMachine(name, looper);
            mSyncSM = null;
        }

    }

    public void start() {
        if (USE_SYNC_SM) {
            mSyncSM.start();
        } else {
            mAsyncSM.start();
        }
    }

    public void addState(final State state) {
        addState(state, null);
    }

    public void addState(final State child, final State parent) {
        if (USE_SYNC_SM) {
            mSyncSM.addState(child, parent);
        } else {
            mAsyncSM.addState(child, parent);
        }
    }

    public void setInitialState(final State state) {
        if (USE_SYNC_SM) {
            mSyncSM.setInitialState(state);
        } else {
            mAsyncSM.setInitialState(state);
        }
    }

    public void transitionTo(final State state) {
        if (USE_SYNC_SM) {
            mSyncSM.transitionTo(state);
        } else {
            mAsyncSM.transitionTo(state);
        }
    }

    public boolean sendMessage(int what) {
        return sendMessage(what, 0, 0, null);
    }

    public boolean sendMessage(int what, Object obj) {
        return sendMessage(what, 0, 0, obj);
    }

    public boolean sendMessage(int what, int arg1) {
        return sendMessage(what, arg1, 0, null);
    }

    public boolean sendMessage(int what, int arg1, int arg2) {
        return sendMessage(what, arg1, arg2, null);
    }

    public boolean sendMessage(int what, int arg1, int arg2, Object obj) {
        return sendMessage(Message.obtain(null, what, arg1, arg2, obj));
    }

    public boolean sendMessage(final Message msg) {
        if (USE_SYNC_SM) {
            return mSyncSM.sendMessage(msg);
        } else {
            mAsyncSM.sendMessage(msg);
            return false;
        }
    }

    private class AsyncStateMachine extends StateMachine {
        AsyncStateMachine(final String name, final Looper looper) {
            super(name, looper);
        }
    }
}
