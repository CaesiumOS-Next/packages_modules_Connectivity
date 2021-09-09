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

#include "bpf_helpers.h"

#define ALLOW 1
#define MAX_POLICIES 16
#define MAP_A 1
#define MAP_B 2

typedef struct {
    struct in_addr srcIp;
    __be16 srcPort;
    struct in_addr dstIp;
    __be16 dstPortStart;
    __be16 dstPortEnd;
    uint16_t proto;
    uint16_t dscpVal;
    // param bitmask?
} Ipv4RuleEntry;

typedef struct {
    struct in6_addr srcIp;
    __be16 srcPort;
    struct in6_addr dstIp;
    __be16 dstPortStart;
    __be16 dstPortEnd;
    uint16_t proto;
    uint16_t dscpVal;
    // param bitmask?
} Ipv6RuleEntry;

// TODO: move to using 1 map. Map v4 address to 0xffff::v4
DEFINE_BPF_MAP_GRW(ipv4_socket_to_policies_map_a, HASH, uint64_t, Ipv4RuleEntry, MAX_POLICIES,
        AID_NETWORK_STACK)
DEFINE_BPF_MAP_GRW(ipv4_socket_to_policies_map_b, HASH, uint64_t, Ipv4RuleEntry, MAX_POLICIES,
        AID_NETWORK_STACK)
DEFINE_BPF_MAP_GRW(ipv6_socket_to_policies_map_a, HASH, uint64_t, Ipv6RuleEntry, MAX_POLICIES,
        AID_NETWORK_STACK)
DEFINE_BPF_MAP_GRW(ipv6_socket_to_policies_map_b, HASH, uint64_t, Ipv6RuleEntry, MAX_POLICIES,
        AID_NETWORK_STACK)
DEFINE_BPF_MAP_GRW(switch_comp_map, ARRAY, int, uint64_t, 1, AID_NETWORK_STACK) // ARRAY or HASH?

DEFINE_BPF_MAP_GRW(ipv4_dscp_policies_map, ARRAY, int, Ipv4RuleEntry, MAX_POLICIES,
        AID_NETWORK_STACK)
DEFINE_BPF_MAP_GRW(ipv6_dscp_policies_map, ARRAY, int, Ipv6RuleEntry, MAX_POLICIES,
        AID_NETWORK_STACK)

DEFINE_BPF_PROG_KVER("cgroupskb/add_dscp_bit", AID_ROOT, AID_NET_ADMIN,
                     cgroupskb_add_dscp_bit, KVER(5, 8, 0))
(struct __sk_buff* skb) {
    int one = 1;
    uint8_t* selectedMap = bpf_switch_comp_map_lookup_elem(&one);
    // use this with HASH map so map lookup only happens once policies have been added?
    if (!selectedMap) {
        return match;
    }

    uint64_t cookie = bpf_get_socket_cookie(skb);

    // Do we need seperate maps for ipv4/ipv6
    if (skb->protocol == htons(ETH_P_IP)) {
        Ipv4RuleEntry* v4Policy;
        if (selectedMap == MAP_A) {
            v4Policy = bpf_ipv4_socket_to_policies_map_a_lookup_elem(&one);
        } else if (selectedMap == MAP_B) {
            v4Policy = bpf_ipv4_socket_to_policies_map_b_lookup_elem(&one);
        }

        // How to use bitmask here to compare params efficiently?

        // TODO: add BPF_PROG_TYPE_SK_SKB prog type to Loader?
        struct bpf_sock *sk = skb->sk;
        /* accessed by BPF_PROG_TYPE_sk_skb types */
        // __u32 family;
        // __u32 remote_ip4;    /* Stored in network byte order */
        // __u32 local_ip4;    /* Stored in network byte order */
        // __u32 remote_ip6[4];    /* Stored in network byte order */
        // __u32 local_ip6[4];    /* Stored in network byte order */
        // __u32 remote_port;    /* Stored in network byte order */
        // __u32 local_port;    /* stored in host byte order */

        if () {
            // socket params == value in socket_to_policies_map
            // apply DSCP value from socket_to_policies_map and return
        } else {
            // Do linear search of dscp_policies_map
            if () {
                // match found, update socket_to_policies_map with DSCP value
            } else {
                // no match found, set DSCP value to 0 (no DSCP) or 255 (Invalid DSCP)
            }
        }

    } else if (skb->protocol == htons(ETH_P_IPV6)) {
        Ipv6RuleEntry* v6Policy;
        if (selectedMap == MAP_A) {
            v6Policy = bpf_ipv6_socket_to_policies_map_a_lookup_elem(&one);
        } else if (selectedMap == MAP_B) {
            v6Policy = bpf_ipv6_socket_to_policies_map_b_lookup_elem(&one);
        }


    }

    // Always return ALLOW
    return ALLOW;
}

LICENSE("Apache 2.0");
CRITICAL("Connectivity");
