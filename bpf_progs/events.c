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

#include <linux/bpf.h>
#include <stdint.h>

#define BPFLOADER_MIN_VER BPFLOADER_U_VERSION

#include <bpf_helpers.h>

typedef struct {
    uint64_t timestampNs;
    uint32_t fwmark;

    char _padding[4];
} ActiveEstablishedEvent;
_Static_assert(sizeof(ActiveEstablishedEvent) == 8+4+4, "wrong struct size");

// BUFFER_SIZE must be a multiple of sizeof ActiveEstablishedEvent
#define BUFFER_SIZE 256 * sizeof(ActiveEstablishedEvent)
DEFINE_BPF_RINGBUF_EXT(active_established_ringbuf, ActiveEstablishedEvent, BUFFER_SIZE,
                       AID_ROOT, AID_SYSTEM, 0060, "fs_bpf_net_shared", /*pindir*/ "",
                       /*shared*/ false, BPFLOADER_MIN_VER, BPFLOADER_MAX_VER, /*ignore_eng*/ false,
                       /*ignore_user*/ false, /*ignore_userdebug*/ false)

// This program requires a minimum kernel version of 5.10 as it uses bpf ringbuffer.
DEFINE_BPF_PROG_KVER("sockops/events", AID_ROOT, AID_SYSTEM, sockops_events, KVER(5, 10, 0))
(struct bpf_sock_ops *skops) {
    if (skops->op != BPF_SOCK_OPS_ACTIVE_ESTABLISHED_CB) {
        // Only BPF_SOCK_OPS_ACTIVE_ESTABLISHED_CB is supported at the moment.
        return 0;
    }
    const struct bpf_sock* sk = skops->sk;
    if (!sk) return 0;

    ActiveEstablishedEvent* event = bpf_active_established_ringbuf_reserve();
    if (!event) return 0;

    event->timestampNs = bpf_ktime_get_boot_ns();
    event->fwmark = sk->mark; 

    bpf_active_established_ringbuf_submit(event);
    return 0;
}

LICENSE("Apache 2.0");
CRITICAL("Connectivity");

