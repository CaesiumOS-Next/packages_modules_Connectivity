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

// This header file is shared by eBPF kernel programs (C) and netd (C++) and
// some of the maps are also accessed directly from Java mainline module code.
//
// Hence: explicitly pad all relevant structures and assert that their size
// is the sum of the sizes of their fields.
#define STRUCT_SIZE(name, size) _Static_assert(sizeof(name) == (size), "Incorrect struct size.")

#define BPF_PATH_TETHER BPF_PATH "connectivity/"

#define BLOCKED_PORTS_MAP_PATH BPF_PATH_TETHER "map_offload_connectivity_blocked_ports_map"

typedef struct {
    __be16 port;             // Port
    uint16_t ipType;         // IPv4/IPv6/IPv4v6
    uint16_t proto;          // IPPROTO_TCP/UDP/...
} BlockedPortKey;
STRUCT_SIZE(BlockedPortKey, 2 + 2 + 2);  // 6

// TODO: what is the value? is it needed?

#undef STRUCT_SIZE