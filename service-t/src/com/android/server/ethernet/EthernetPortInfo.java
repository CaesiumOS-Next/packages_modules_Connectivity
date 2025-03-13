/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.ethernet;

/**
 * A class representing information of an EthernetPort object.
 * EthernetPortInfo is mainly used in network callbacks.
 */
public class EthernetPortInfo implements Parcelable {
    private static final String TAG = EthernetPortInfo.class.getSimpleName();

    // Role of this ethernet interface, one of {@code EthernetManeger.ROLE_SERVER},
    // {@code EthernetManeger.ROLE_CLIENT}, or {@code EthernetManeger.ROLE_NONE}.
    private final int mRole;

    // State of this ethernet interface, one of {@code EthernetManeger.STATE_LINK_UP},
    // {@code EthernetManager.STATE_LINK_DOWN}, or {@code EthernetManager.STATE_ABSENT}.
    private final int mState;

    // Configuration of this ethernet interface.
    private EthernetConfiguration mConfig;

    // Whether this interface is metered.
    private boolean mIsMetered;

    // Getters.
    // Setters for config and metered attributes?
    // Builder
    // ToString()
    // parcelable implementation
}
