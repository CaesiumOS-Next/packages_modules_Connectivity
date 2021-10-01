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

#pragma once

// Unsure how these are used. Pulled from Tethering, but do not seem to be referenced anywhere...
#define BPF_PATH_CONNECTIVITY BPF_PATH "connectivity/"
#define BLOCKED_PORTS_MAP_PATH BPF_PATH_CONNECTIVITY "map_offload_connectivity_blocked_ports_map"

typedef uint8_t BlockedPortKey;
typedef uint8_t BlockedPortValue;
