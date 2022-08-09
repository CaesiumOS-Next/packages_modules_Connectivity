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

#pragma once

// 'static' - otherwise these constants end up in .rodata in the resulting .o post compilation
static const int CACHE_MAP_SIZE = 1024;
static const int MAX_POLICIES = 16;

static const uint8_t SRC_IP_MASK_FLAG = 1;
static const uint8_t DST_IP_MASK_FLAG = 2;
static const uint8_t SRC_PORT_MASK_FLAG = 4;
static const uint8_t PROTO_MASK_FLAG = 8;

#define STRUCT_SIZE(name, size) _Static_assert(sizeof(name) == (size), "Incorrect struct size.")

typedef struct {
    struct in6_addr src_ip;
    struct in6_addr dst_ip;
    uint32_t ifindex;
    __be16 src_port;
    uint16_t dst_port_start;
    uint16_t dst_port_end;
    uint8_t proto;
    int8_t dscp_val;  // -1 none, or 0..63 DSCP value
    uint8_t present_fields;
    uint8_t pad[3];
} DscpPolicy;
STRUCT_SIZE(DscpPolicy, 2 * 16 + 4 + 3 * 2 + 3 * 1 + 3);  // 48

typedef struct {
    struct in6_addr src_ip;
    struct in6_addr dst_ip;
    __u32 ifindex;
    __be16 src_port;
    __be16 dst_port;
    __u8 proto;
    __s8 dscp_val;  // -1 none, or 0..63 DSCP value
    __u8 pad[2];
} RuleEntry;
STRUCT_SIZE(RuleEntry, 2 * 16 + 1 * 4 + 2 * 2 + 2 * 1 + 2);  // 44
