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

package com.android.networkstack.tethering.halshim.control.common;

import android.hardware.tetheroffload.control.V1_0.NatTimeoutUpdate;
import android.os.RemoteException;

public interface OffloadCallbackShim {
    void onEvent(int i) throws RemoteException;
    void onEvent_1_1(int i) throws RemoteException;
    void updateTimeout(NatTimeoutUpdate natTimeoutUpdate) throws RemoteException;
}
