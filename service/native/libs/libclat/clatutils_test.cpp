// Copyright (C) 2022 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "libclat/clatutils.h"

#include "test_utils.h"
#include "tun_interface.h"

#include <android-base/stringprintf.h>
#include <arpa/inet.h>
#include <gtest/gtest.h>
#include <linux/if_packet.h>
#include <linux/if_tun.h>

extern "C" {
#include "checksum.h"
}

// Default translation parameters.
static const char kIPv4LocalAddr[] = "192.0.0.4";

namespace android {
namespace net {
namespace clat {

using android::net::TunInterface;
using base::StringPrintf;

class ClatUtils : public ::testing::Test {};

// Mock functions for isIpv4AddressFree.
bool neverFree(in_addr_t /* addr */) {
    return 0;
}
bool alwaysFree(in_addr_t /* addr */) {
    return 1;
}
bool only2Free(in_addr_t addr) {
    return (ntohl(addr) & 0xff) == 2;
}
bool over6Free(in_addr_t addr) {
    return (ntohl(addr) & 0xff) >= 6;
}
bool only10Free(in_addr_t addr) {
    return (ntohl(addr) & 0xff) == 10;
}

// Apply mocked isIpv4AddressFree function for selectIpv4Address test.
in_addr_t selectIpv4Address(const in_addr ip, int16_t prefixlen,
                            isIpv4AddrFreeFn fn /* mocked function */) {
    // Call internal function to replace isIpv4AddressFreeFn for testing.
    return selectIpv4AddressInternal(ip, prefixlen, fn);
}

TEST_F(ClatUtils, SelectIpv4Address) {
    struct in_addr addr;

    inet_pton(AF_INET, kIPv4LocalAddr, &addr);

    // If no addresses are free, return INADDR_NONE.
    EXPECT_EQ(INADDR_NONE, selectIpv4Address(addr, 29, neverFree));
    EXPECT_EQ(INADDR_NONE, selectIpv4Address(addr, 16, neverFree));

    // If the configured address is free, pick that. But a prefix that's too big is invalid.
    EXPECT_EQ(inet_addr(kIPv4LocalAddr), selectIpv4Address(addr, 29, alwaysFree));
    EXPECT_EQ(inet_addr(kIPv4LocalAddr), selectIpv4Address(addr, 20, alwaysFree));
    EXPECT_EQ(INADDR_NONE, selectIpv4Address(addr, 15, alwaysFree));

    // A prefix length of 32 works, but anything above it is invalid.
    EXPECT_EQ(inet_addr(kIPv4LocalAddr), selectIpv4Address(addr, 32, alwaysFree));
    EXPECT_EQ(INADDR_NONE, selectIpv4Address(addr, 33, alwaysFree));

    // If another address is free, pick it.
    EXPECT_EQ(inet_addr("192.0.0.6"), selectIpv4Address(addr, 29, over6Free));

    // Check that we wrap around to addresses that are lower than the first address.
    EXPECT_EQ(inet_addr("192.0.0.2"), selectIpv4Address(addr, 29, only2Free));
    EXPECT_EQ(INADDR_NONE, selectIpv4Address(addr, 30, only2Free));

    // If a free address exists outside the prefix, we don't pick it.
    EXPECT_EQ(INADDR_NONE, selectIpv4Address(addr, 29, only10Free));
    EXPECT_EQ(inet_addr("192.0.0.10"), selectIpv4Address(addr, 24, only10Free));

    // Now try using the real function which sees if IP addresses are free using bind().
    // Assume that the machine running the test has the address 127.0.0.1, but not 8.8.8.8.
    addr.s_addr = inet_addr("8.8.8.8");
    EXPECT_EQ(inet_addr("8.8.8.8"), selectIpv4Address(addr, 29));

    addr.s_addr = inet_addr("127.0.0.1");
    EXPECT_EQ(inet_addr("127.0.0.2"), selectIpv4Address(addr, 29));
}

TEST_F(ClatUtils, MakeChecksumNeutral) {
    // We can't test generateIPv6Address here since it requires manipulating routing, which we can't
    // do without talking to the real netd on the system.
    uint32_t rand = arc4random_uniform(0xffffffff);
    uint16_t rand1 = rand & 0xffff;
    uint16_t rand2 = (rand >> 16) & 0xffff;
    std::string v6PrefixStr = StringPrintf("2001:db8:%x:%x", rand1, rand2);
    std::string v6InterfaceAddrStr = StringPrintf("%s::%x:%x", v6PrefixStr.c_str(), rand2, rand1);
    std::string nat64PrefixStr = StringPrintf("2001:db8:%x:%x::", rand2, rand1);

    in_addr v4 = {inet_addr(kIPv4LocalAddr)};
    in6_addr v6InterfaceAddr;
    ASSERT_TRUE(inet_pton(AF_INET6, v6InterfaceAddrStr.c_str(), &v6InterfaceAddr));
    in6_addr nat64Prefix;
    ASSERT_TRUE(inet_pton(AF_INET6, nat64PrefixStr.c_str(), &nat64Prefix));

    // Generate a boatload of random IIDs.
    int onebits = 0;
    uint64_t prev_iid = 0;
    for (int i = 0; i < 100000; i++) {
        in6_addr v6 = v6InterfaceAddr;
        makeChecksumNeutral(&v6, v4, nat64Prefix);

        // Check the generated IP address is in the same prefix as the interface IPv6 address.
        EXPECT_EQ(0, memcmp(&v6, &v6InterfaceAddr, 8));

        // Check that consecutive IIDs are not the same.
        uint64_t iid = *(uint64_t*)(&v6.s6_addr[8]);
        ASSERT_TRUE(iid != prev_iid)
                << "Two consecutive random IIDs are the same: " << std::showbase << std::hex << iid
                << "\n";
        prev_iid = iid;

        // Check that the IID is checksum-neutral with the NAT64 prefix and the
        // local prefix.
        uint16_t c1 = ip_checksum_finish(ip_checksum_add(0, &v4, sizeof(v4)));
        uint16_t c2 = ip_checksum_finish(ip_checksum_add(0, &nat64Prefix, sizeof(nat64Prefix)) +
                                         ip_checksum_add(0, &v6, sizeof(v6)));

        if (c1 != c2) {
            char v6Str[INET6_ADDRSTRLEN];
            inet_ntop(AF_INET6, &v6, v6Str, sizeof(v6Str));
            FAIL() << "Bad IID: " << v6Str << " not checksum-neutral with " << kIPv4LocalAddr
                   << " and " << nat64PrefixStr.c_str() << std::showbase << std::hex
                   << "\n  IPv4 checksum: " << c1 << "\n  IPv6 checksum: " << c2 << "\n";
        }

        // Check that IIDs are roughly random and use all the bits by counting the
        // total number of bits set to 1 in a random sample of 100000 generated IIDs.
        onebits += __builtin_popcountll(*(uint64_t*)&iid);
    }
    EXPECT_LE(3190000, onebits);
    EXPECT_GE(3210000, onebits);
}

TEST_F(ClatUtils, DetectMtu) {
    // ::1 with bottom 32 bits set to 1 is still ::1 which routes via lo with mtu of 64KiB
    ASSERT_EQ(detect_mtu(&in6addr_loopback, htonl(1), 0 /*MARK_UNSET*/), 65536);
}

TEST_F(ClatUtils, ConfigurePacketSocket) {
    // Create an interface for configure_packet_socket to attach socket filter to.
    TunInterface v6Iface;
    ASSERT_EQ(0, v6Iface.init());

    int s = socket(AF_PACKET, SOCK_DGRAM | SOCK_CLOEXEC, htons(ETH_P_IPV6));
    EXPECT_LE(0, s);
    struct in6_addr addr6;
    EXPECT_EQ(1, inet_pton(AF_INET6, "2001:db8::f00", &addr6));
    EXPECT_EQ(0, configure_packet_socket(s, &addr6, v6Iface.ifindex()));

    // Check that the packet socket is bound to the interface. We can't check the socket filter
    // because there is no way to fetch it from the kernel.
    sockaddr_ll sll;
    socklen_t len = sizeof(sll);
    ASSERT_EQ(0, getsockname(s, reinterpret_cast<sockaddr*>(&sll), &len));
    EXPECT_EQ(htons(ETH_P_IPV6), sll.sll_protocol);
    EXPECT_EQ(sll.sll_ifindex, v6Iface.ifindex());

    close(s);
    v6Iface.destroy();
}

namespace {

std::vector<std::string> tryToFindProcesses(const std::string& processName, uint32_t maxTries = 1,
                                            uint32_t intervalMs = 50) {
    // Output looks like:(clatd)
    // clat          4963   850 1 12:16:51 ?     00:00:00 clatd-netd10a88 -i netd10a88 ...
    // ...
    // root          5221  5219 0 12:18:12 ?     00:00:00 sh -c ps -Af | grep ' clatd-netdcc1a0'

    if (maxTries == 0) return {};

    std::string cmd = StringPrintf("ps -Af | grep '[0-9] %s'", processName.c_str());
    std::vector<std::string> result;
    for (uint32_t run = 1;;) {
        result = runCommand(cmd);
        if (result.size() || ++run > maxTries) {
            break;
        }

        usleep(intervalMs * 1000);
    }
    return result;
}

void expectProcessExists(const std::string& processName) {
    EXPECT_EQ(1U, tryToFindProcesses(processName, 5 /*maxTries*/).size());
}

void expectProcessDoesNotExist(const std::string& processName) {
    EXPECT_FALSE(tryToFindProcesses(processName).size());
}

}  // namespace

TEST_F(ClatUtils, ClatdStartStop) {
    TunInterface tun;
    ASSERT_EQ(0, tun.init());

    int readSock = socket(AF_INET6, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    int writeSock = socket(AF_INET6, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    const std::string clatdName = StringPrintf("clatd-%s", tun.name().c_str());
    std::string nat64Prefix = "2001:db8:cafe:f00d:1:2::";
    std::string v4 = "192.0.0.4";
    std::string v6 = "2a00:79e1:abc:6f02:807c:2aae:bfb9:3780";

    int pid = startClatd(tun.getFdForTesting(), readSock, writeSock, tun.name().c_str(),
            nat64Prefix.c_str(), v4.c_str(), v6.c_str());
    EXPECT_LT(0, pid);
    expectProcessExists(clatdName);

    stopClatd(pid);
    expectProcessDoesNotExist(clatdName);

    tun.destroy();
    close(readSock);
    close(writeSock);
}

}  // namespace clat
}  // namespace net
}  // namespace android
