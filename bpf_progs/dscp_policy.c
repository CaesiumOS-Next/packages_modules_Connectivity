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
DEFINE_BPF_MAP_GRW(ipv4_socket_to_policies_map_A, HASH, uint64_t, Ipv4RuleEntry, MAX_POLICIES,
        AID_SYSTEM)
DEFINE_BPF_MAP_GRW(ipv4_socket_to_policies_map_B, HASH, uint64_t, Ipv4RuleEntry, MAX_POLICIES,
        AID_SYSTEM)
DEFINE_BPF_MAP_GRW(ipv6_socket_to_policies_map_A, HASH, uint64_t, Ipv6RuleEntry, MAX_POLICIES,
        AID_SYSTEM)
DEFINE_BPF_MAP_GRW(ipv6_socket_to_policies_map_B, HASH, uint64_t, Ipv6RuleEntry, MAX_POLICIES,
        AID_SYSTEM)
DEFINE_BPF_MAP_GRW(switch_comp_map, ARRAY, int, uint64_t, 1, AID_SYSTEM)

DEFINE_BPF_MAP_GRW(ipv4_dscp_policies_map, ARRAY, uint32_t, DscpPolicy, MAX_POLICIES,
        AID_SYSTEM)
DEFINE_BPF_MAP_GRW(ipv6_dscp_policies_map, ARRAY, uint32_t, DscpPolicy, MAX_POLICIES,
        AID_SYSTEM)

DEFINE_BPF_PROG_KVER("schedcls/set_dscp", AID_ROOT, AID_SYSTEM,
                     schedcls_set_dscp, KVER(5, 4, 0))
(struct __sk_buff* skb) {
    int one = 0;
    uint64_t* selectedMap = bpf_switch_comp_map_lookup_elem(&one);

    // use this with HASH map so map lookup only happens once policies have been added?
    if (!selectedMap) {
        return TC_ACT_PIPE;
    }

    // used for map lookup
    uint64_t cookie = bpf_get_socket_cookie(skb);

    // Do we need separate maps for ipv4/ipv6
    if (skb->protocol == htons(ETH_P_IP)) { //maybe bpf_htons()
        Ipv4RuleEntry* v4Policy;
        if (*selectedMap == MAP_A) {
            v4Policy = bpf_ipv4_socket_to_policies_map_A_lookup_elem(&cookie);
        } else {
            v4Policy = bpf_ipv4_socket_to_policies_map_B_lookup_elem(&cookie);
        }

        void* data = (void*)(long)skb->data;
        const void* data_end = (void*)(long)skb->data_end;
        const struct iphdr* const iph = data;

        // Must have ipv4 header
        if (data + sizeof(*iph) > data_end) return TC_ACT_PIPE;

        // IP version must be 4
        if (iph->version != 4) return TC_ACT_PIPE;

        // We cannot handle IP options, just standard 20 byte == 5 dword minimal IPv4 header
        if (iph->ihl != 5) return TC_ACT_PIPE;

        if (iph->protocol != IPPROTO_UDP) return TC_ACT_PIPE;

        struct udphdr *udp;
        udp = data + sizeof(struct iphdr); // TODO: add in optional sizeof(struct ethhdr)
        if ((void*)(udp + 1) > data_end) return TC_ACT_PIPE;

        // Source/destination port in udphdr are stored in be16, need to convert to le16.
        // This can be done via ntohs or htons. Is there a more preferred way?
        // Cached policy was found.
        if (v4Policy && iph->saddr == v4Policy->srcIp.s_addr &&
                    iph->daddr == v4Policy->dstIp.s_addr &&
                    ntohs(udp->source) == v4Policy->srcPort &&
                    ntohs(udp->dest) == v4Policy->dstPort &&
                    iph->protocol == v4Policy->proto) {
            // set dscpVal in packet. Least sig 2 bits of TOS
            // reference ipv4_change_dsfield()

            // TODO: fix checksum...
            int ecn = iph->tos & 3;
            uint8_t newDscpVal = (v4Policy->dscpVal << 2) + ecn;
            int oldDscpVal = iph->tos >> 2;
            bpf_l3_csum_replace(skb, 1, oldDscpVal, newDscpVal, sizeof(uint8_t));
            bpf_skb_store_bytes(skb, 1, &newDscpVal, sizeof(uint8_t), 0);
            return TC_ACT_PIPE;
        }

        // linear scan ipv4_dscp_policies_map, stored socket params do not match actual
        int bestScore = -1;
        uint32_t bestMatch = 0;

        for (register uint64_t i = 0; i < MAX_POLICIES; i++) {
            int score = 0;
            uint8_t tempMask = 0;
            // Using a uint64 in for loop prevents infinite loop during BPF load,
            // but the key is uint32, so convert back.
            uint32_t key = i;
            DscpPolicy* policy = bpf_ipv4_dscp_policies_map_lookup_elem(&key);

            // if mask is 0 continue, key does not have corresponding policy value
            if (policy && policy->mask != 0) {
                if ((policy->mask & SRC_IP_MASK) == SRC_IP_MASK &&
                        iph->saddr == policy->srcIp.s6_addr32[3]) {
                    score++;
                    tempMask |= SRC_IP_MASK;
                }
                if ((policy->mask & DST_IP_MASK) == DST_IP_MASK &&
                        iph->daddr == policy->dstIp.s6_addr32[3]) {
                    score++;
                    tempMask |= DST_IP_MASK;
                }
                if ((policy->mask & SRC_PORT_MASK) == SRC_PORT_MASK &&
                        ntohs(udp->source) == htons(policy->srcPort)) {
                    score++;
                    tempMask |= SRC_PORT_MASK;
                }
                if ((policy->mask & DST_PORT_MASK) == DST_PORT_MASK &&
                        ntohs(udp->dest) >= htons(policy->dstPortStart) &&
                        ntohs(udp->dest) <= htons(policy->dstPortEnd)) {
                    score++;
                    tempMask |= DST_PORT_MASK;
                }
                if ((policy->mask & PROTO_MASK) == PROTO_MASK &&
                        iph->protocol == policy->proto) {
                    score++;
                    tempMask |= PROTO_MASK;
                }

                if (score > bestScore && tempMask == policy->mask) {
                    bestMatch = i;
                    bestScore = score;
                }
            }
        }

        uint8_t newDscpVal = 0; // Can 0 be used as default forwarding value?
        uint8_t curDscp = iph->tos & 252;
        if (bestScore > 0) {
            DscpPolicy* policy = bpf_ipv4_dscp_policies_map_lookup_elem(&bestMatch);
            if (policy) {
                // TODO: if DSCP value is already set ignore?
                // TODO: update checksum, for testing increment counter...
                int ecn = iph->tos & 3;
                newDscpVal = (policy->dscpVal << 2) + ecn;
            }
        }

        Ipv4RuleEntry value = {
            .srcIp.s_addr = iph->saddr,
            .dstIp.s_addr = iph->daddr,
            .srcPort = udp->source,
            .dstPort = udp->dest,
            .proto = iph->protocol,
            .dscpVal = newDscpVal,
        };

        if (!cookie)
            return TC_ACT_PIPE;

        // Update map
        if (*selectedMap == MAP_A) {
            bpf_ipv4_socket_to_policies_map_A_update_elem(&cookie, &value, BPF_ANY);
        } else {
            bpf_ipv4_socket_to_policies_map_B_update_elem(&cookie, &value, BPF_ANY);
        }

        // Need to store bytes after updating map or program will not load.
        if (newDscpVal != curDscp) {
            // 1 is the offset (Version/Header length)
            int oldDscpVal = iph->tos >> 2;
            bpf_l3_csum_replace(skb, 1, oldDscpVal, newDscpVal, sizeof(uint8_t));
            bpf_skb_store_bytes(skb, 1, &newDscpVal, sizeof(uint8_t), 0);
        }
    } else if (skb->protocol == htons(ETH_P_IPV6)) { //maybe bpf_htons()
        Ipv6RuleEntry* v6Policy;
        if (*selectedMap == MAP_A) {
            v6Policy = bpf_ipv6_socket_to_policies_map_A_lookup_elem(&cookie);
        } else {
            v6Policy = bpf_ipv6_socket_to_policies_map_B_lookup_elem(&cookie);
        }

        void* data = (void*)(long)skb->data;
        const void* data_end = (void*)(long)skb->data_end;
        struct ipv6hdr* ip6h = data;

        // Must have ipv6 header
        if (data + sizeof(*ip6h) > data_end) return TC_ACT_PIPE;

        // IP version must be 6
        // TODO: technically this is the top 4 bits, bottom 4 are for DSCP.
        if (ip6h->version != 6) return TC_ACT_PIPE;

        if (ip6h->nexthdr != IPPROTO_UDP) return TC_ACT_PIPE;

        struct udphdr *udp;
        udp = data + sizeof(struct ipv6hdr); //sizeof(struct ethhdr)

        if ((void*)(udp + 1) > data_end) return TC_ACT_PIPE;

        // Source/destination port in udphdr are stored in be16, need to convert to le16.
        // This can be done via ntohs or htons. Is there a more preferred way?
        // Cached policy was found.
        if (v6Policy && v6_equal(&ip6h->saddr, &v6Policy->srcIp) &&
                    v6_equal(&ip6h->daddr, &v6Policy->dstIp) &&
                    ntohs(udp->source) == v6Policy->srcPort &&
                    ntohs(udp->dest) == v6Policy->dstPort &&
                    ip6h->nexthdr == v6Policy->proto) {

            int ecn = ip6h->flow_lbl[0] & 3;
            uint8_t newDscpVal = (v6Policy->dscpVal << 2) + ecn;
            // int oldDscpVal = ip6h->flow_lbl[0] >> 2;

            // TODO: program won't load with csum or store_bytes for v6
            // if (newDscpVal || oldDscpVal) return TC_ACT_PIPE;
            // bpf_l3_csum_replace(skb, 1, oldDscpVal, newDscpVal, sizeof(uint8_t));
            bpf_skb_store_bytes(skb, 1, &newDscpVal, sizeof(uint8_t), 0);
            //bpf_skb_ecn_set_ce(skb);
            return TC_ACT_PIPE;
        }

        // linear scan ipv6_dscp_policies_map, stored socket params do not match actual
        int bestScore = -1;
        uint32_t bestMatch = 0;

        for (register uint64_t i = 0; i < MAX_POLICIES; i++) {
            int score = 0;
            uint8_t tempMask = 0;
            // Using a uint64 in for loop prevents infinite loop during BPF load,
            // but the key is uint32, so convert back.
            uint32_t key = i;
            DscpPolicy* policy = bpf_ipv6_dscp_policies_map_lookup_elem(&key);

            // if mask is 0 continue, key does not have corresponding policy value
            if (policy && policy->mask != 0) {
                if ((policy->mask & SRC_IP_MASK) == SRC_IP_MASK &&
                        v6_equal(ip6h->saddr, policy->srcIp)) {
                    score++;
                    tempMask |= SRC_IP_MASK;
                }
                if ((policy->mask & DST_IP_MASK) == DST_IP_MASK &&
                        v6_equal(ip6h->daddr, policy->dstIp)) {
                    score++;
                    tempMask |= DST_IP_MASK;
                }
                if ((policy->mask & SRC_PORT_MASK) == SRC_PORT_MASK &&
                        ntohs(udp->source) == htons(policy->srcPort)) {
                    score++;
                    tempMask |= SRC_PORT_MASK;
                }
                if ((policy->mask & DST_PORT_MASK) == DST_PORT_MASK &&
                        ntohs(udp->dest) >= htons(policy->dstPortStart) &&
                        ntohs(udp->dest) <= htons(policy->dstPortEnd)) {
                    score++;
                    tempMask |= DST_PORT_MASK;
                }
                if ((policy->mask & PROTO_MASK) == PROTO_MASK &&
                        ip6h->nexthdr == policy->proto) {
                    score++;
                    tempMask |= PROTO_MASK;
                }

                if (score > bestScore && tempMask == policy->mask) {
                    bestMatch = i;
                    bestScore = score;
                }
            }
        }

        uint8_t newDscpVal = 0; // Can 0 be used as default forwarding value?
        uint8_t newDscpVal2 = 0; // Can 0 be used as default forwarding value?
        uint8_t curDscp = ip6h->priority;
        uint8_t curDscp2 = ip6h->flow_lbl[0];
        if (bestScore > 0) {
            DscpPolicy* policy = bpf_ipv6_dscp_policies_map_lookup_elem(&bestMatch);
            if (policy) {
                // TODO: if DSCP value is already set ignore?
                // TODO: update checksum, for testing increment counter...
                //int dscp_top = ip6h->flow_lbl[0] & 0xf;
                //newDscpVal = (policy->dscpVal << 2) + ecn;

                // First 4 bits of Dscp val needs to be Ipv6 version.
                newDscpVal = (policy->dscpVal >> 2) + 0x60;
                // Dscp is top 2 bits. CE and Flow label are remaining bits.
                newDscpVal2 = ((policy->dscpVal & 0xf) << 6) + (curDscp2 >> 6);
            }
        }

        Ipv6RuleEntry value = {
            .srcIp = ip6h->saddr,
            .dstIp = ip6h->daddr,
            .srcPort = udp->source,
            .dstPort = udp->dest,
            .proto = ip6h->nexthdr,
            .dscpVal = newDscpVal,
        };

        if (!cookie)
            return TC_ACT_PIPE;

        // Update map
        if (*selectedMap == MAP_A) {
            bpf_ipv6_socket_to_policies_map_A_update_elem(&cookie, &value, BPF_ANY);
        } else {
            bpf_ipv6_socket_to_policies_map_B_update_elem(&cookie, &value, BPF_ANY);
        }

        // Need to store bytes after updating map or program will not load.
        if (newDscpVal != curDscp && newDscpVal2 != curDscp2) {
            bpf_skb_store_bytes(skb, 0, &newDscpVal, sizeof(uint8_t), 0);
            bpf_skb_store_bytes(skb, 1, &newDscpVal2, sizeof(uint8_t), 0);
        }
    }

    // Always return TC_ACT_PIPE
    return TC_ACT_PIPE;
}

LICENSE("GPL");
//LICENSE("Apache 2.0");
//CRITICAL("Connectivity");
