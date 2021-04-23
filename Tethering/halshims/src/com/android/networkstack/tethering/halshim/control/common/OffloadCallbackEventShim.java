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

public class OffloadCallbackEventShim {
    // Need to sync with hardware/interfaces/tetheroffload/control/*.*/types.hal.
    public static final int OFFLOAD_STARTED = 1;
    public static final int OFFLOAD_STOPPED_ERROR = 2;
    public static final int OFFLOAD_STOPPED_UNSUPPORTED = 3;
    public static final int OFFLOAD_SUPPORT_AVAILABLE = 4;
    public static final int OFFLOAD_STOPPED_LIMIT_REACHED = 5;
    public static final int OFFLOAD_WARNING_REACHED = 6;
}
