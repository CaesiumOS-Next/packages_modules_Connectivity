/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.networkstack.tethering.halshim.control.v1_1;

import android.hardware.tetheroffload.control.V1_0.NatTimeoutUpdate;
import android.hardware.tetheroffload.control.V1_1.ITetheringOffloadCallback;
import android.os.RemoteException;

import androidx.annotation.NonNull;

import com.android.networkstack.tethering.halshim.control.common.OffloadCallbackShim;

// The callback shim should always points to the latest ITetheringOffloadCallback since there is
// no harm to pass new callback into old interfaces.
public abstract class OffloadCallbackShimImpl extends
        com.android.networkstack.tethering.halshim.control.v1_0.OffloadCallbackShimImpl {

    public OffloadCallbackShimImpl() {
        super(false /*createInnerCb*/);
        this.setInnerCallback(new TetheringOffloadCallbackInner(this));
    }

    static class TetheringOffloadCallbackInner extends ITetheringOffloadCallback.Stub {
        final OffloadCallbackShim mParent;
        TetheringOffloadCallbackInner(@NonNull OffloadCallbackShim parent) {
            mParent = parent;
        }

        @Override
        public void onEvent(int i) throws RemoteException {
            mParent.onEvent(i);
        }

        @Override
        public void onEvent_1_1(int i) throws RemoteException {
            mParent.onEvent_1_1(i);
        }

        @Override
        public void updateTimeout(NatTimeoutUpdate natTimeoutUpdate) throws RemoteException {
            mParent.updateTimeout(natTimeoutUpdate);
        }
    }
}
