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

package com.android.server.nearby.presence;

import java.util.UUID;

/**
 * Constants for Nearby Presence operations.
 */
public class PresenceConstants {

    /** Presence advertisement service data uuid. */
    public static final UUID PRESENCE_UUID = to128BitUuid((short) 0xFCF1);

    private static final UUID BASE_UUID = UUID.fromString("00000000-0000-1000-8000-00805F9B34FB");

    private static UUID to128BitUuid(short shortUuid) {
        return new UUID(((long) shortUuid & 65535L) << 32 | BASE_UUID.getMostSignificantBits(),
                BASE_UUID.getLeastSignificantBits());
    }
}
