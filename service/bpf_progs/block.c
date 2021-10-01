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

#include <linux/types.h>
#include <linux/bpf.h>
#include <stdint.h>

#include "bpf_block_ports.h"
#include "bpf_helpers.h"

DEFINE_BPF_MAP_GRW(blocked_ports_map, ARRAY, BlockedPortKey, BlockedPortValue, 8192 /* Max */, AID_NETWORK_STACK)

DEFINE_BPF_PROG_KVER("cgroupsockaddr/block_port", AID_ROOT, AID_NET_ADMIN,
                     sched_cls_block_port, KVER(5, 8, 0))
(struct bpf_sock_addr *ctx) {
    BlockedPortKey key = (ctx->user_port - 1) / 8;
    int shift = ctx->user_port % 8;

    BlockedPortValue *ptr = bpf_blocked_ports_map_lookup_elem(&key);
    if (!(*ptr >> shift)) {
        /* Port is not present in the map, so it's not reserved.
         * bind is allowed.
         */
        return 1;
    }
    /* Port is blocked, so deny */
    return 0;
}

LICENSE("Apache 2.0");
CRITICAL("Connectivity");
