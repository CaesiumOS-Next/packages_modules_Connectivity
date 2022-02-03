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
#include <linux/pkt_cls.h>
#include <linux/tcp.h>
#include <stdint.h>
#include <netinet/in.h>
#include <netinet/udp.h>
#include <string.h>

#include "bpf_helpers.h"
#include "dscp_policy.h"

// TODO: move to using 1 map. Map v4 address to 0xffff::v4
DEFINE_BPF_MAP_GRW(ipv4_socket_to_policies_map_A, HASH, uint64_t, RuleEntry, MAX_POLICIES,
        AID_SYSTEM)
DEFINE_BPF_MAP_GRW(ipv4_socket_to_policies_map_B, HASH, uint64_t, RuleEntry, MAX_POLICIES,
        AID_SYSTEM)
DEFINE_BPF_MAP_GRW(ipv6_socket_to_policies_map_A, HASH, uint64_t, RuleEntry, MAX_POLICIES,
        AID_SYSTEM)
DEFINE_BPF_MAP_GRW(ipv6_socket_to_policies_map_B, HASH, uint64_t, RuleEntry, MAX_POLICIES,
        AID_SYSTEM)
DEFINE_BPF_MAP_GRW(switch_comp_map, ARRAY, int, uint64_t, 1, AID_SYSTEM)

DEFINE_BPF_MAP_GRW(ipv4_dscp_policies_map, ARRAY, uint32_t, DscpPolicy, MAX_POLICIES,
        AID_SYSTEM)
DEFINE_BPF_MAP_GRW(ipv6_dscp_policies_map, ARRAY, uint32_t, DscpPolicy, MAX_POLICIES,
        AID_SYSTEM)

// inline functions for ipv4 and ipv6 DSCP calculation

static inline __always_inline void match_policy(struct __sk_buff* skb, bool ipv4) {
    char fmt[] = "add dscp log %d\n";
    bpf_trace_printk(fmt, sizeof(fmt), 1);

    int one = 0;
    uint64_t* selectedMap = bpf_switch_comp_map_lookup_elem(&one);

    // use this with HASH map so map lookup only happens once policies have been added?
    if (!selectedMap) {
        return;
    }

    // used for map lookup
    uint64_t cookie = bpf_get_socket_cookie(skb);
    if (!cookie)
        return;

    // Pass in new rule entry? Compose from IPv4/v6 header and UDP/TCP/UDP_LITE

    int hdr_size;
    // TODO: add in optional sizeof(struct ethhdr)
    if (ipv4)
        hdr_size = sizeof(struct iphdr);
    else
        hdr_size = sizeof(struct ipv6hdr);

    void* data = (void*)(long)skb->data;
    const void* data_end = (void*)(long)skb->data_end;

    uint16_t sport = 0;
    uint16_t dport = 0;
    uint8_t protocol = 0; // TODO: Use are reserved value? Or int (-1) and cast to uint below?
    struct in6_addr srcIp;
    struct in6_addr dstIp;
    uint8_t tos = 0; // Only usef for IPv4
    uint8_t priority = 0; // Only usef for IPv6
    uint8_t flow_lbl = 0; // Only usef for IPv6
    if (ipv4) {
        bpf_trace_printk(fmt, sizeof(fmt), 4);
        const struct iphdr* const iph = data;
        // Must have ipv4 header
        if (data + sizeof(*iph) > data_end) return;

        // IP version must be 4
        if (iph->version != 4) return;

        // We cannot handle IP options, just standard 20 byte == 5 dword minimal IPv4 header
        if (iph->ihl != 5) return;

        // Copy IPv4 address into in6_addr for easy comparision below.
        srcIp.s6_addr32[3] = iph->saddr;
        dstIp.s6_addr32[3] = iph->daddr;
        protocol = iph->protocol;
        tos = iph->tos;
    } else {
        bpf_trace_printk(fmt, sizeof(fmt), 6);
        struct ipv6hdr* ip6h = data;
        // Must have ipv6 header
        if (data + sizeof(*ip6h) > data_end) return;

        // IP version must be 6
        // TODO: technically this is the top 4 bits, bottom 4 are for DSCP.
        if (ip6h->version != 6) return;

        srcIp = ip6h->saddr;
        dstIp = ip6h->daddr;
        protocol = ip6h->nexthdr;
        priority = ip6h->priority;
        flow_lbl = ip6h->flow_lbl[0];
    }

    bpf_trace_printk(fmt, sizeof(fmt), protocol);

    switch (protocol) {
        case IPPROTO_UDP:
        case IPPROTO_UDPLITE:
        {
            struct udphdr *udp;
            udp = data + hdr_size;
            if ((void*)(udp + 1) > data_end) return;
            sport = udp->source;
            dport = udp->dest;
        }
        break;
        case IPPROTO_TCP:
        {
            struct tcphdr *tcp;
            tcp = data + hdr_size;
            if ((void*)(tcp + 1) > data_end) return;
            sport = tcp->source;
            dport = tcp->dest;
        }
        break;
        default:
            bpf_trace_printk(fmt, sizeof(fmt), 0);
            return;
    }

    RuleEntry* existingRule;
    if (ipv4) {
        if (*selectedMap == MAP_A) {
            existingRule = bpf_ipv4_socket_to_policies_map_A_lookup_elem(&cookie);
        } else {
            existingRule = bpf_ipv4_socket_to_policies_map_B_lookup_elem(&cookie);
        }
    } else {
        if (*selectedMap == MAP_A) {
            existingRule = bpf_ipv6_socket_to_policies_map_A_lookup_elem(&cookie);
        } else {
            existingRule = bpf_ipv6_socket_to_policies_map_B_lookup_elem(&cookie);
        }
    }

    if (existingRule && v6_equal(srcIp, existingRule->srcIp) &&
                v6_equal(dstIp, existingRule->dstIp) &&
                ntohs(sport) == htons(existingRule->srcPort) &&
                ntohs(dport) == htons(existingRule->dstPort) &&
                protocol == existingRule->proto) {
        bpf_trace_printk(fmt, sizeof(fmt), 00);
        // Set dscpVal in packet. Least sig 2 bits of TOS
        // reference ipv4_change_dsfield().

        if (ipv4) {
            int ecn = tos & 3;
            uint8_t newDscpVal = (existingRule->dscpVal << 2) + ecn;
            int oldDscpVal = tos >> 2;
            bpf_l3_csum_replace(skb, 1, oldDscpVal, newDscpVal, sizeof(uint8_t));
            bpf_skb_store_bytes(skb, 1, &newDscpVal, sizeof(uint8_t), 0);
        } else {
            uint8_t new_priority = (existingRule->dscpVal >> 2) + 0x60;
            uint8_t new_flow_label = ((existingRule->dscpVal & 0xf) << 6) + (priority >> 6);
            bpf_skb_store_bytes(skb, 0, &new_priority, sizeof(uint8_t), 0);
            bpf_skb_store_bytes(skb, 1, &new_flow_label, sizeof(uint8_t), 0);
        }
        return;
    }

    // Linear scan ipv4_dscp_policies_map since no stored params match skb.
    int bestScore = -1;
    uint32_t bestMatch = 0;

    for (register uint64_t i = 0; i < MAX_POLICIES; i++) {
        bpf_trace_printk(fmt, sizeof(fmt), 11);
        int score = 0;
        uint8_t tempMask = 0;
        // Using a uint64 in for loop prevents infinite loop during BPF load,
        // but the key is uint32, so convert back.
        uint32_t key = i;

        DscpPolicy* policy;
        if (ipv4) {
            policy = bpf_ipv4_dscp_policies_map_lookup_elem(&key);
        } else {
            policy = bpf_ipv6_dscp_policies_map_lookup_elem(&key);
        }

        // if mask is 0 continue, key does not have corresponding policy value
        if (policy && policy->mask != 0) {
            bpf_trace_printk(fmt, sizeof(fmt), 111);
            bpf_trace_printk(fmt, sizeof(fmt), policy->mask );
            if ((policy->mask & SRC_IP_MASK) == SRC_IP_MASK &&
                    v6_equal(srcIp, policy->srcIp)) {
                score++;
                tempMask |= SRC_IP_MASK;
                bpf_trace_printk(fmt, sizeof(fmt), tempMask);
            }
            if ((policy->mask & DST_IP_MASK) == DST_IP_MASK &&
                    v6_equal(dstIp, policy->dstIp)) {
                score++;
                tempMask |= DST_IP_MASK;
                bpf_trace_printk(fmt, sizeof(fmt), tempMask);
            }
            if ((policy->mask & SRC_PORT_MASK) == SRC_PORT_MASK &&
                    ntohs(sport) == htons(policy->srcPort)) {
                score++;
                tempMask |= SRC_PORT_MASK;
                bpf_trace_printk(fmt, sizeof(fmt), tempMask);
            }
            if ((policy->mask & DST_PORT_MASK) == DST_PORT_MASK &&
                    ntohs(dport) >= htons(policy->dstPortStart) &&
                    ntohs(dport) <= htons(policy->dstPortEnd)) {
                score++;
                tempMask |= DST_PORT_MASK;
                bpf_trace_printk(fmt, sizeof(fmt), tempMask);
            }
            if ((policy->mask & PROTO_MASK) == PROTO_MASK &&
                    protocol == policy->proto) {
                score++;
                tempMask |= PROTO_MASK;
                bpf_trace_printk(fmt, sizeof(fmt), tempMask);
            }

            if (score > bestScore && tempMask == policy->mask) {
                bpf_trace_printk(fmt, sizeof(fmt), 2222);
                bestMatch = i;
                bestScore = score;
            }
        }
    }

    uint8_t newTos= 0; // Can 0 be used as default forwarding value?
    uint8_t new_priority = 0;
    uint8_t new_flow_lbl = 0;
    if (bestScore > 0) {
        DscpPolicy* policy;
        if (ipv4) {
            policy = bpf_ipv4_dscp_policies_map_lookup_elem(&bestMatch);
        } else {
            policy = bpf_ipv6_dscp_policies_map_lookup_elem(&bestMatch);
        }

        if (policy) {
            // TODO: if DSCP value is already set ignore?
            // TODO: update checksum, for testing increment counter...
            if (ipv4) {
                int ecn = tos & 3;
                newTos = (policy->dscpVal << 2) + ecn;
            } else {
                new_priority = (policy->dscpVal >> 2) + 0x60;
                new_flow_lbl = ((policy->dscpVal & 0xf) << 6) + (flow_lbl >> 6);

                // Set IPv6 curDscp value to stored value and recalulate priority
                // and flow label during next use.
                newTos = policy->dscpVal;
            }
        }
    } else return;

    // RuleEntry value = {
    //     .srcIp = srcIp,
    //     .dstIp = dstIp,
    //     .srcPort = sport,
    //     .dstPort = dport,
    //     .proto = protocol,
    //     .dscpVal = newTos,
    // };

    //Update map with new policy.
    // TODO: Policy Update not passing bpf loader.
    // if (ipv4) {
    //     if (*selectedMap == MAP_A) {
    //         bpf_ipv4_socket_to_policies_map_A_update_elem(&cookie, &value, BPF_ANY);
    //     } else {
    //         bpf_ipv4_socket_to_policies_map_B_update_elem(&cookie, &value, BPF_ANY);
    //     }
    // } else {
    //     if (*selectedMap == MAP_A) {
    //         bpf_ipv6_socket_to_policies_map_A_update_elem(&cookie, &value, BPF_ANY);
    //     } else {
    //         bpf_ipv6_socket_to_policies_map_B_update_elem(&cookie, &value, BPF_ANY);
    //     }
    // }

    // Need to store bytes after updating map or program will not load.
    if (ipv4 && newTos != (tos & 252)) {
        bpf_trace_printk(fmt, sizeof(fmt), 4444);
        int oldDscpVal = tos >> 2;
        bpf_l3_csum_replace(skb, 1, oldDscpVal, newTos, sizeof(uint8_t));
        bpf_skb_store_bytes(skb, 1, &newTos, sizeof(uint8_t), 0);
    } else if (!ipv4 && (new_priority != priority || new_flow_lbl != flow_lbl)) {
        bpf_trace_printk(fmt, sizeof(fmt), 6666);
        bpf_skb_store_bytes(skb, 0, &new_priority, sizeof(uint8_t), 0);
        bpf_skb_store_bytes(skb, 1, &new_flow_lbl, sizeof(uint8_t), 0);
    }
    return;
}

DEFINE_BPF_PROG_KVER("schedcls/set_dscp", AID_ROOT, AID_SYSTEM,
                     schedcls_set_dscp, KVER(5, 4, 0))
(struct __sk_buff* skb) {
    if (skb->protocol == htons(ETH_P_IP)) {
        match_policy(skb, true);
    } else if (skb->protocol == htons(ETH_P_IPV6)) {
        match_policy(skb, false);
    }

    // Always return TC_ACT_PIPE
    return TC_ACT_PIPE;
}

LICENSE("GPL");
//LICENSE("Apache 2.0");
//CRITICAL("Connectivity");
