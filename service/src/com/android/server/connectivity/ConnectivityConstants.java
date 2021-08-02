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

package com.android.server.connectivity;

/**
 * Collection of constants for the connectivity service module.
 */
public final class ConnectivityConstants {
    private ConnectivityConstants() {}

    /**
     * Minimum module version at which to use legacy behavior to rematch all requests when a
     * network request is registered, instead of only the registered requests.
     *
     * This flag is disabled by default.
     */
    public static final String REMATCH_ALL_REQUESTS_ON_REGISTER =
            "rematch_all_requests_on_register";
}
