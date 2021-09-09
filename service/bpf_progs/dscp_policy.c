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
#include <linux/ip.h>
#include <linux/ipv6.h>
#include <linux/if_ether.h>
#include <stdint.h>
#include <netinet/in.h>
#include <string.h>

#include "bpf_helpers.h"

#define ALLOW 1
#define MAX_POLICIES 16
#define MAP_A 1
#define MAP_B 2

// TODO: these are already defined in /system/netd/bpf_progs/bpf_net_helpers.h
// should they be moved to common location?
static uint64_t (*bpf_get_socket_cookie)(struct __sk_buff* skb) = (void*)BPF_FUNC_get_socket_cookie;
static int (*bpf_skb_load_bytes)(struct __sk_buff* skb, int off, void* to,
                                 int len) = (void*)BPF_FUNC_skb_load_bytes;

typedef struct {
  // Add family here to match __sk_buff ?
    struct in_addr srcIp;
    __be16 srcPort;
    struct in_addr dstIp;
    __be16 dstPortStart;
    __be16 dstPortEnd;
    __u64 proto;
    __u8 dscpVal;
} Ipv4RuleEntry;

typedef struct {
    __u8 hasSrcIp;
    struct in_addr srcIp;
    __u8 hasSrcPort;
    __be16 srcPort;
    __u8 hasDstIp;
    struct in_addr dstIp;
    __u8 hasDstPortStart;
    __be16 dstPortStart;
    __u8 hasDstPortEnd;
    __be16 dstPortEnd;
    __u8 hasProto;
    __u64 proto;
    __u8 dscpVal;
} Ipv4Policies;

typedef struct {
    struct in6_addr srcIp;
    __be16 srcPort;
    struct in6_addr dstIp;
    __be16 dstPortStart;
    __be16 dstPortEnd;
    __u64 proto;
    __u8 dscpVal;
    // should we override this stuct to include the param bitmask for linear search?
    // For mapping socket to policies, all the params should match exactly since we can pull any missing
    // from the sock itself.
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

DEFINE_BPF_MAP_GRW(ipv4_dscp_policies_map, ARRAY, int, Ipv4Policies, MAX_POLICIES,
        AID_NETWORK_STACK)
DEFINE_BPF_MAP_GRW(ipv6_dscp_policies_map, ARRAY, int, Ipv6RuleEntry, MAX_POLICIES,
        AID_NETWORK_STACK)

DEFINE_BPF_PROG_KVER("cgroupskb/add_dscp_bit", AID_ROOT, AID_NET_ADMIN,
                     cgroupskb_add_dscp_bit, KVER(5, 8, 0))
(struct __sk_buff* skb) {
    int one = 1;
    uint64_t* selectedMap = bpf_switch_comp_map_lookup_elem(&one);
    // use this with HASH map so map lookup only happens once policies have been added?
    if (!selectedMap) {
        return ALLOW;
    }

    // used for map lookup
    uint64_t cookie = bpf_get_socket_cookie(skb);

    // Do we need seperate maps for ipv4/ipv6
    if (skb->protocol == htons(ETH_P_IP)) { //maybe bpf_htons()
        Ipv4RuleEntry* v4Policy;
        if (*selectedMap == MAP_A) {
            v4Policy = bpf_ipv4_socket_to_policies_map_a_lookup_elem(&cookie);
        } else if (*selectedMap == MAP_B) {
            v4Policy = bpf_ipv4_socket_to_policies_map_b_lookup_elem(&cookie);
        }

        // How to use bitmask here to compare params efficiently?
        // TODO: add BPF_PROG_TYPE_SK_SKB prog type to Loader?

        // check protocol of next hdr
        struct iphdr iph_inner;
        if (bpf_skb_load_bytes(skb, ETH_HLEN, &iph_inner, sizeof(iph_inner)) < 0)
            return 1; // should we return here?

        if (!skb)
            return 1;

        if (memcmp(&skb->local_ip4, &v4Policy->srcIp.s_addr, sizeof(struct in_addr)) &&
                memcmp(&skb->remote_ip4, &v4Policy->dstIp.s_addr, sizeof(struct in_addr)) &&
                skb->local_port == v4Policy->srcPort && skb->remote_port == v4Policy->dstPortStart &&
                iph_inner.protocol == v4Policy->proto) {
            // set dscpVal in packet. Least sig 2 bits of TOS
            // reference ipv4_change_dsfield()
            iph_inner.tos = v4Policy->dscpVal;
        } else {
            // linear scan ipv4_dscp_policies_map, stored socket params do not match actual
            int newScore = 0;
            int oldScore = 0;
            int bestMatch = -1;
            for (int i=0; i<16; i++) {
                newScore = 0;
                Ipv4Policies* policy = bpf_ipv4_dscp_policies_map_lookup_elem(&i);

                if (policy) {
                    if (policy->hasSrcIp && memcmp(&skb->local_ip4, &policy->srcIp.s_addr, sizeof(struct in_addr))) {
                        newScore++;
                    }
                    if (policy->hasDstIp && memcmp(&skb->remote_ip4, &policy->dstIp.s_addr, sizeof(struct in_addr))) {
                        newScore++;
                    }
                    if (policy->hasSrcPort && skb->local_port == policy->srcPort) {
                        newScore++;
                    }
                    if (policy->hasDstPortStart && skb->remote_port == policy->dstPortStart) {
                        // ignoring port range for now
                        newScore++;
                    }
                    if (policy->hasProto && iph_inner.protocol == policy->proto) {
                        newScore++;
                    }

                    //compare score
                    if (newScore > 0 && newScore > oldScore) {
                        bestMatch = i;
                        oldScore = newScore;
                    }
                }
            }

            __u8 newDscpVal = 0; // Can 0 be used as default forwarding value?
            if (bestMatch != -1) {
                Ipv4Policies* policy = bpf_ipv4_dscp_policies_map_lookup_elem(&bestMatch);
                //set DSCP bit
                iph_inner.tos = policy->dscpVal;
                newDscpVal = policy->dscpVal;
            }

            Ipv4RuleEntry value = {
                .srcIp = {skb->local_ip4},
                .dstIp = {skb->remote_ip4},
                .srcPort = skb->local_port,
                .dstPortStart = skb->remote_port,
                .dstPortEnd = skb->remote_port,
                .proto = iph_inner.protocol,
                .dscpVal = newDscpVal,
            };

            // Update map
            if (*selectedMap == MAP_A) {
                bpf_ipv4_socket_to_policies_map_a_update_elem(&cookie, &value, 0);
            } else if (*selectedMap == MAP_B) {
                bpf_ipv4_socket_to_policies_map_b_update_elem(&cookie, &value, 0);
            }
        }

    } else if (skb->protocol == htons(ETH_P_IPV6)) { //maybe bpf_htons()
        Ipv6RuleEntry* v6Policy;
        if (*selectedMap == MAP_A) {
            v6Policy = bpf_ipv6_socket_to_policies_map_a_lookup_elem(&cookie);
        } else if (*selectedMap == MAP_B) {
            v6Policy = bpf_ipv6_socket_to_policies_map_b_lookup_elem(&cookie);
        }

        // check protocol of next hdr
        struct ipv6hdr iph6_inner;
        if (bpf_skb_load_bytes(skb, ETH_HLEN, &iph6_inner, sizeof(iph6_inner)) < 0)
                return 1;

        if (memcmp(&skb->local_ip6, &v6Policy->srcIp, sizeof(struct in6_addr)) &&
                memcmp(&skb->remote_ip6, &v6Policy->dstIp, sizeof(struct in6_addr)) &&
                skb->local_port == v6Policy->srcPort && skb->remote_port == v6Policy->dstPortStart) {

            if (iph6_inner.nexthdr == v6Policy->proto) {
                //set dscpVal in packet

                // reference ipv6_change_dsfield()
                /* The four highest bits need to be set at hdr->priority */
                iph6_inner.priority |= ((v6Policy->dscpVal & 0x3c) >> 2);
                /* The two lower bits is part of hdr->flow_lbl[0] */
                iph6_inner.flow_lbl[0] |= ((v6Policy->dscpVal & 0x03) << 6);
            } else {
              // linear scan ipv4_dscp_policies_map, stored socket params do not match actual
            }
        } else {
          // linear scan ipv4_dscp_policies_map, stored socket params do not match actual
        }
    }

    // Always return ALLOW
    return ALLOW;
}

LICENSE("Apache 2.0");
//CRITICAL("Connectivity");
