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
#include "bpf_endian.h"

#include "bpf_block_ports.h"
#include "bpf_helpers.h"
#include "bpf_net_helpers.h"


DEFINE_BPF_MAP_GRW(blocked_ports_map, HASH, BlockedPortKey, 1/* unused val */, 500 /* Max */,
                   AID_NETWORK_STACK)

DEFINE_BPF_PROG_KVER("cgroupsock/inet/block_port$5_8", AID_ROOT, AID_NETWORK_STACK,
                     sched_cls_block_port_5_8, KVER(5, 8, 0))
(struct bpf_sock_addr *ctx)
	struct BlockedPortKey key = {};
	__u32 *ptr;

	key.ipFamily = ctx->family;
	key.proto = ctx->protocol;
	key.port = ctx->user_port;
	ptr = bpf_map_lookup_elem(&blocked_ports_map, &key);
	if (!ptr)
		/* Port is not present in the map, so it's not reserved.
		 * bind is allowed.
		 */
		return 1;

	/* Port is blocked, so deny */
	return 0;
}


LICENSE("Apache 2.0");
//CRITICAL("connectivity"); //is this needed?
