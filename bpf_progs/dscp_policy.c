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

#define STRUCT_SIZE(name, size) _Static_assert(sizeof(name) == (size), "Incorrect struct size.")

// TODO: these are already defined in /system/netd/bpf_progs/bpf_net_helpers.h
// should they be moved to common location?
static uint64_t (*bpf_get_socket_cookie)(struct __sk_buff* skb) =
        (void*)BPF_FUNC_get_socket_cookie;
static int (*bpf_skb_load_bytes)(struct __sk_buff* skb, int off, void* to,
                                 int len) = (void*)BPF_FUNC_skb_load_bytes;
// static int (*bpf_skb_store_bytes)(struct __sk_buff* skb, __u32 offset, const void* from, __u32 len,
//                                   __u64 flags) = (void*)BPF_FUNC_skb_store_bytes;
static long (*bpf_skb_ecn_set_ce)(struct __sk_buff* skb) =
        (void*)BPF_FUNC_skb_ecn_set_ce;

typedef struct {
    // Add family here to match __sk_buff ?
    struct in_addr srcIp;
    struct in_addr dstIp;
    __be16 srcPort;
    __be16 dstPort;
    uint8_t proto;
    uint8_t dscpVal;
    uint8_t pad[2];
} Ipv4RuleEntry;
STRUCT_SIZE(Ipv4RuleEntry, 2 * 4 + 2 * 2 + 2 * 1 + 2);  // 16, 4 for in_addr

#define SRC_IP_MASK     1
#define DST_IP_MASK     2
#define SRC_PORT_MASK   4
#define DST_PORT_MASK   8
#define PROTO_MASK      16

typedef struct {
    struct in6_addr srcIp;
    struct in6_addr dstIp;
    __be16 srcPort;
    __be16 dstPortStart;
    __be16 dstPortEnd;
    uint8_t proto;
    uint8_t dscpVal;
    uint8_t mask;
    uint8_t pad[3];
} Ipv4Policy;
STRUCT_SIZE(Ipv4Policy, 2 * 16 + 3 * 2 + 3 * 1 + 3);  // 20, 2*4 for in_addr or 2*16 for in6_addr

typedef struct {
    struct in6_addr srcIp;
    struct in6_addr dstIp;
    __be16 srcPort;
    __be16 dstPort;
    uint8_t proto;
    uint8_t dscpVal;
    // should we override this struct to include the param bitmask for linear search?
    // For mapping socket to policies, all the params should match exactly since we can
    // pull any missing from the sock itself.
} Ipv6RuleEntry;
STRUCT_SIZE(Ipv6RuleEntry, 2 * 16 + 3 * 2 + 2);  // 40

// TODO: move to using 1 map. Map v4 address to 0xffff::v4
DEFINE_BPF_MAP_GRW(ipv4_socket_to_policies_map_A, HASH, uint64_t, Ipv4RuleEntry, MAX_POLICIES,
        AID_SYSTEM)
DEFINE_BPF_MAP_GRW(ipv4_socket_to_policies_map_B, HASH, uint64_t, Ipv4RuleEntry, MAX_POLICIES,
        AID_SYSTEM)
DEFINE_BPF_MAP_GRW(ipv6_socket_to_policies_map_A, HASH, uint64_t, Ipv6RuleEntry, MAX_POLICIES,
        AID_SYSTEM)
DEFINE_BPF_MAP_GRW(ipv6_socket_to_policies_map_B, HASH, uint64_t, Ipv6RuleEntry, MAX_POLICIES,
        AID_SYSTEM)
DEFINE_BPF_MAP_GRW(switch_comp_map, ARRAY, int, uint64_t, 1, AID_SYSTEM)

DEFINE_BPF_MAP_GRW(ipv4_dscp_policies_map, ARRAY, uint32_t, Ipv4Policy, MAX_POLICIES,
        AID_SYSTEM)
DEFINE_BPF_MAP_GRW(ipv6_dscp_policies_map, ARRAY, uint32_t, Ipv6RuleEntry, MAX_POLICIES,
        AID_SYSTEM)

DEFINE_BPF_PROG_KVER("cgroupskb/add_dscp_bit", AID_ROOT, AID_SYSTEM,
                     cgroupskb_add_dscp_bit, KVER(5, 8, 0))
(struct __sk_buff* skb) {
    int one = 0;
    uint64_t* selectedMap = bpf_switch_comp_map_lookup_elem(&one);

    char fmt[] = "add dscp log %d\n";
    bpf_trace_printk(fmt, sizeof(fmt), 1);

    // use this with HASH map so map lookup only happens once policies have been added?
    if (!selectedMap) {
        return ALLOW;
    }

    // used for map lookup
    uint64_t cookie = bpf_get_socket_cookie(skb);

    // Do we need separate maps for ipv4/ipv6
    if (skb->protocol == htons(ETH_P_IP)) { //maybe bpf_htons()
        bpf_trace_printk(fmt, sizeof(fmt), 3434);
        bpf_trace_printk(fmt, sizeof(fmt), skb->ifindex);
        bpf_trace_printk(fmt, sizeof(fmt), skb->ingress_ifindex);
        Ipv4RuleEntry* v4Policy;
        if (*selectedMap == MAP_A) {
            //bpf_trace_printk(fmt, sizeof(fmt), 31);
            v4Policy = bpf_ipv4_socket_to_policies_map_A_lookup_elem(&cookie);
        } else {
            //bpf_trace_printk(fmt, sizeof(fmt), 32);
            v4Policy = bpf_ipv4_socket_to_policies_map_B_lookup_elem(&cookie);
        }

        // How to use bitmask here to compare params efficiently?
        // TODO: add BPF_PROG_TYPE_SK_SKB prog type to Loader?

        // check protocol of next hdr
        struct iphdr iph;
        if (bpf_skb_load_bytes(skb, 0, &iph, sizeof(iph)) < 0) // ETH_HLEN
            return ALLOW; // should we return here?

        if (!skb || !skb->local_ip4) {
            bpf_trace_printk(fmt, sizeof(fmt), 34);
            return ALLOW;
        }

        if (v4Policy && skb->local_ip4 == v4Policy->srcIp.s_addr &&
                    skb->remote_ip4 == v4Policy->dstIp.s_addr &&
                    skb->local_port == v4Policy->srcPort &&
                    skb->remote_port == v4Policy->dstPort &&
                    iph.protocol == v4Policy->proto) {
            // set dscpVal in packet. Least sig 2 bits of TOS
            // reference ipv4_change_dsfield()
            bpf_trace_printk(fmt, sizeof(fmt), 111);
            iph.tos = v4Policy->dscpVal;
            bpf_skb_ecn_set_ce(skb);
            bpf_trace_printk(fmt, sizeof(fmt), v4Policy->dscpVal);
            // TODO: fix checksum, for testing increment counter...
        } else {
            bpf_trace_printk(fmt, sizeof(fmt), 66666);
            // linear scan ipv4_dscp_policies_map, stored socket params do not match actual
            int bestScore = -1;
            uint32_t bestMatch = 0;

            for (register uint64_t i = 0; i < MAX_POLICIES; i++) {
                bpf_trace_printk(fmt, sizeof(fmt), 7);
                int score = 0;
                // Using a uint62 in for loop prevents infinite loop during BPF load,
                // but the key is uint32, so convert back.
                uint32_t key = i;
                Ipv4Policy* policy = bpf_ipv4_dscp_policies_map_lookup_elem(&key);

                // if mask is 0 continue, key does not have corresponding policy value
                if (policy && policy->mask != 0) {
                    char fmt_mask[] = "add dscp log: MASK Value = %d\n";
                    bpf_trace_printk(fmt_mask, sizeof(fmt_mask), policy->mask);
                    bpf_trace_printk(fmt, sizeof(fmt), ntohl(skb->remote_port));
                    bpf_trace_printk(fmt, sizeof(fmt), htons(policy->dstPortStart));
                    //bpf_trace_printk(fmt, sizeof(fmt), htons(skb->local_port));
                    bpf_trace_printk(fmt, sizeof(fmt), skb->local_port);
                    if ((policy->mask & SRC_IP_MASK) == SRC_IP_MASK &&
                            skb->local_ip4 == policy->srcIp.s6_addr32[3]) {
                        score++;
                        bpf_trace_printk(fmt, sizeof(fmt), 81);
                    }
                    if ((policy->mask & DST_IP_MASK) == DST_IP_MASK &&
                            skb->remote_ip4 == policy->dstIp.s6_addr32[3]) {
                        score++;
                        bpf_trace_printk(fmt, sizeof(fmt), 82);
                    }
                    if ((policy->mask & SRC_PORT_MASK) == SRC_PORT_MASK &&
                            skb->local_port == htons(policy->srcPort)) {
                        score++;
                        bpf_trace_printk(fmt, sizeof(fmt), 83);
                    }
                    if ((policy->mask & DST_PORT_MASK) == DST_PORT_MASK &&
                            ntohl(skb->remote_port) >= htons(policy->dstPortStart) &&
                            ntohl(skb->remote_port) <= htons(policy->dstPortEnd)) {
                        // ignoring port range for now
                        score++;
                        bpf_trace_printk(fmt, sizeof(fmt), 84);
                    }
                    if ((policy->mask & PROTO_MASK) == PROTO_MASK &&
                            iph.protocol == policy->proto) {
                        score++;
                        bpf_trace_printk(fmt, sizeof(fmt), 85);
                    }

                    if (score > bestScore) {
                        bestMatch = i;
                        bestScore = score;
                        bpf_trace_printk(fmt, sizeof(fmt), 86);
                        bpf_trace_printk(fmt, sizeof(fmt), score);
                    }
                }
            }

            uint8_t newDscpVal = 0; // Can 0 be used as default forwarding value?
            if (bestScore > 0) {
                Ipv4Policy* policy = bpf_ipv4_dscp_policies_map_lookup_elem(&bestMatch);
                if (policy) {
                    //set DSCP bit
                    //iph.tos = policy->dscpVal;
                    // TODO: add ecn bit back
                    // TODO: optionally add eth header in

                    //const int sz2 = sizeof(uint8_t);
                    //bpf_skb_store_bytes(skb, 1, &policy->dscpVal, sz2, 0); //only works with tc

                    // bpf_trace_printk(fmt, sizeof(fmt), skb_headlen(skb));

                    // if (skb_cloned(skb) && !skb_clone_writable(skb, sizeof(struct iphdr)))
                    //     bpf_trace_printk(fmt, sizeof(fmt), 77777);

                    int ecn_ret = bpf_skb_ecn_set_ce(skb);
                    bpf_trace_printk(fmt, sizeof(fmt), ecn_ret);

                    bpf_trace_printk(fmt, sizeof(fmt), 222);
                    bpf_trace_printk(fmt, sizeof(fmt), policy->dscpVal);
                    // TODO: update checksum, for testing increment counter...
                    newDscpVal = policy->dscpVal;
                }
            }

            Ipv4RuleEntry value = {
                .srcIp.s_addr = skb->local_ip4,
                .dstIp.s_addr = skb->remote_ip4,
                .srcPort = skb->local_port,
                .dstPort = skb->remote_port,
                .proto = iph.protocol,
                .dscpVal = newDscpVal,
            };

            if (!cookie)
                return ALLOW;

            // Update map
            if (*selectedMap == MAP_A) {
                bpf_trace_printk(fmt, sizeof(fmt), 10);
                bpf_ipv4_socket_to_policies_map_A_update_elem(&cookie, &value, BPF_ANY);
            } else {
                bpf_trace_printk(fmt, sizeof(fmt), 11);
                bpf_ipv4_socket_to_policies_map_B_update_elem(&cookie, &value, BPF_ANY);
            }
        }

    } else if (skb->protocol == htons(ETH_P_IPV6)) { //maybe bpf_htons()
        bpf_trace_printk(fmt, sizeof(fmt), 4);
        Ipv6RuleEntry* v6Policy;
        if (*selectedMap == MAP_A) {
            v6Policy = bpf_ipv6_socket_to_policies_map_A_lookup_elem(&cookie);
        } else {
            v6Policy = bpf_ipv6_socket_to_policies_map_B_lookup_elem(&cookie);
        }

        if (!v6Policy)
            return ALLOW;

        // check protocol of next hdr
        struct ipv6hdr iph6_inner;
        if (bpf_skb_load_bytes(skb, ETH_HLEN, &iph6_inner, sizeof(iph6_inner)) < 0)
                return 1;

        if (memcmp(&skb->local_ip6, &v6Policy->srcIp, sizeof(struct in6_addr)) &&
                memcmp(&skb->remote_ip6, &v6Policy->dstIp, sizeof(struct in6_addr)) &&
                skb->local_port == v6Policy->srcPort && skb->remote_port ==
                v6Policy->dstPort) {

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
    } else {
        bpf_trace_printk(fmt, sizeof(fmt), 9999);
    }

    // Always return ALLOW
    return ALLOW;
}

LICENSE("GPL");
//LICENSE("Apache 2.0");
//CRITICAL("Connectivity");
