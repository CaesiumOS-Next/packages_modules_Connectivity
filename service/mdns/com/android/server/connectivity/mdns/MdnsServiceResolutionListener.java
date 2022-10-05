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

package com.android.server.connectivity.mdns;

import android.annotation.NonNull;

/**
 * Listener interface for mDNS service instance resolution events.
 *
 * @hide
 */
public interface MdnsServiceResolutionListener extends MdnsServiceListener {

    /**
     * Called when an mDNS service instance is resolved.
     *
     * @param serviceInfo The resolved mDNS service instance.
     */
    void onServiceResolved(@NonNull MdnsServiceInfo serviceInfo);

    /**
     * Called when an error has happened when resolving an mDNS service.
     *
     * @param errorCode The error code, defined in {@link MdnsResponseErrorCode}.
     */
    void onResolveFailed(int errorCode);
}
