/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.google.android.server.remoteauth.connectivity;

import android.annotation.NonNull;

/**
 * Abstract class to expose a callback for clients to send a response to the peer device.
 */
public abstract class ResponseCallback {
    /**
     * Callback to send a message to the peer device.
     * @param messageContext
     */
    public void run(@NonNull MessageContext messageContext) {}
}
