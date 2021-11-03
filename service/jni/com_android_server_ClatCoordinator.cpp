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


#include <arpa/inet.h>
#include <fcntl.h>
#include <jni.h>
#include <spawn.h>
#include <string>
#include <linux/if_tun.h>
#include <linux/ioctl.h>
#include <linux/if_arp.h>
#include <linux/if_ether.h>
#include <linux/netlink.h>
#include <linux/pkt_cls.h>
#include <linux/pkt_sched.h>
#include <linux/rtnetlink.h>
#include <nativehelper/JNIHelp.h>
#include <net/if.h>
#include <netinet/in.h>
#include <stdio.h>
#include <sys/socket.h>

#include "nativehelper/scoped_utf_chars.h"

// Sync from system/netd/server/NetdConstants.h
#define __INT_STRLEN(i) sizeof(#i)
#define _INT_STRLEN(i) __INT_STRLEN(i)
#define INT32_STRLEN _INT_STRLEN(INT32_MIN)

namespace android {
static const char* kClatdPath = "/system/bin/clatd";

static void throwIOException(JNIEnv *env, const char* msg, int error) {
    jniThrowExceptionFmt(env, "java/io/IOException", "%s: %s", msg, strerror(error));
}

bool isIpv4AddressFree(in_addr_t addr) {
    int s = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (s == -1) {
        return 0;
    }

    // Attempt to connect to the address. If the connection succeeds and getsockname returns the
    // same then the address is already assigned to the system and we can't use it.
    struct sockaddr_in sin = {
            .sin_family = AF_INET,
            .sin_port = htons(53),
            .sin_addr = {addr},
    };
    socklen_t len = sizeof(sin);
    bool inuse = connect(s, (struct sockaddr*)&sin, sizeof(sin)) == 0 &&
                 getsockname(s, (struct sockaddr*)&sin, &len) == 0 && (size_t)len >= sizeof(sin) &&
                 sin.sin_addr.s_addr == addr;

    close(s);
    return !inuse;
}

// Picks a free IPv4 address, starting from ip and trying all addresses in the prefix in order.
//   v4addr    - the IP address from the configuration file
//   prefixlen - the length of the prefix from which addresses may be selected.
//   returns: the IPv4 address, or nullptr if no addresses were available or any error.
jstring com_android_server_ClatCoordinator_selectIpv4Address(JNIEnv* env, jobject clazz,
                                                             jstring v4addr, jint prefixlen) {
    // Don't accept prefixes that are too large because we scan addresses one by one.
    if (prefixlen < 16 || prefixlen > 32) {
        return nullptr;
    }

    ScopedUtfChars address(env, v4addr);
    in_addr ip;
    if (inet_pton(AF_INET, address.c_str(), &ip) != 1) {
        return nullptr;
    }

    // All these are in host byte order.
    in_addr_t mask = 0xffffffff >> (32 - prefixlen) << (32 - prefixlen);
    in_addr_t ipv4 = ntohl(ip.s_addr);
    in_addr_t first_ipv4 = ipv4;
    in_addr_t prefix = ipv4 & mask;

    // Pick the first IPv4 address in the pool, wrapping around if necessary.
    // So, for example, 192.0.0.4 -> 192.0.0.5 -> 192.0.0.6 -> 192.0.0.7 -> 192.0.0.0.
    do {
        if (isIpv4AddressFree(htonl(ipv4))) {
            char addrstr[INET_ADDRSTRLEN];
            in_addr found;
            found.s_addr = htonl(ipv4);
            if (!inet_ntop(AF_INET, (void *)&found, addrstr, sizeof(addrstr))) {
                continue;  // impossible, just in case
            }
            return env->NewStringUTF(addrstr);
        }
        ipv4 = prefix | ((ipv4 + 1) & ~mask);
    } while (ipv4 != first_ipv4);

    return nullptr;
}

// Duplicate the checksum functions from system/core/libnetutils/checksum.c
// - ip_checksum_add
// - ip_checksum_fold
// - ip_checksum_adjust
// TODO: consider using netutils/checksum.h
uint32_t ip_checksum_add(uint32_t current, const void* data, int len) {
    uint32_t checksum = current;
    int left = len;
    const uint16_t* data_16 = (uint16_t*)data;

    while (left > 1) {
        checksum += *data_16;
        data_16++;
        left -= 2;
    }
    if (left) {
        checksum += *(uint8_t*)data_16;
    }

    return checksum;
}

uint16_t ip_checksum_fold(uint32_t temp_sum) {
    while (temp_sum > 0xffff) {
        temp_sum = (temp_sum >> 16) + (temp_sum & 0xFFFF);
    }
    return temp_sum;
}

uint16_t ip_checksum_adjust(uint16_t checksum, uint32_t old_hdr_sum, uint32_t new_hdr_sum) {
    // Algorithm suggested in RFC 1624.
    // http://tools.ietf.org/html/rfc1624#section-3
    checksum = ~checksum;
    uint16_t folded_sum = ip_checksum_fold(checksum + new_hdr_sum);
    uint16_t folded_old = ip_checksum_fold(old_hdr_sum);
    if (folded_sum > folded_old) {
        return ~(folded_sum - folded_old);
    } else {
        return ~(folded_sum - folded_old - 1);  // end-around borrow
    }
}

// Alters the bits in the IPv6 address to make them checksum neutral with v4 and nat64Prefix.
void makeChecksumNeutral(in6_addr* v6, const in_addr v4, const in6_addr& nat64Prefix) {
    // Fill last 8 bytes of IPv6 address with random bits.
    arc4random_buf(&v6->s6_addr[8], 8);

    // Make the IID checksum-neutral. That is, make it so that:
    //   checksum(Local IPv4 | Remote IPv4) = checksum(Local IPv6 | Remote IPv6)
    // in other words (because remote IPv6 = NAT64 prefix | Remote IPv4):
    //   checksum(Local IPv4) = checksum(Local IPv6 | NAT64 prefix)
    // Do this by adjusting the two bytes in the middle of the IID.

    uint16_t middlebytes = (v6->s6_addr[11] << 8) + v6->s6_addr[12];

    uint32_t c1 = ip_checksum_add(0, &v4, sizeof(v4));
    uint32_t c2 = ip_checksum_add(0, &nat64Prefix, sizeof(nat64Prefix)) +
                  ip_checksum_add(0, v6, sizeof(*v6));

    uint16_t delta = ip_checksum_adjust(middlebytes, c1, c2);
    v6->s6_addr[11] = delta >> 8;
    v6->s6_addr[12] = delta & 0xff;
}

// Picks a random interface ID that is checksum neutral with the IPv4 address and the NAT64 prefix.
jstring com_android_server_ClatCoordinator_generateIpv6Address(JNIEnv* env, jobject clazz,
                                                               jstring ifaceStr, jstring v4Str, jstring prefix64Str) {
    ScopedUtfChars iface(env, ifaceStr);
    ScopedUtfChars addr4(env, v4Str);
    ScopedUtfChars prefix64(env, prefix64Str);

    if (iface.c_str() == nullptr) {
        jniThrowExceptionFmt(env, "java/io/IOException", "Invalid null interface name");
        return nullptr;
    }

    in_addr v4;
    if (inet_pton(AF_INET, addr4.c_str(), &v4) != 1) {
        jniThrowExceptionFmt(env, "java/io/IOException", "Invalid clat v4 address %s", addr4.c_str());
        return nullptr;
    }

    in6_addr nat64Prefix;
    if (inet_pton(AF_INET6, prefix64.c_str(), &nat64Prefix) != 1) {
        jniThrowExceptionFmt(env, "java/io/IOException", "Invalid prefix %s", prefix64.c_str());
        return nullptr;
    }

    int s = socket(AF_INET6, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (s == -1) {
        throwIOException(env, "socket(AF_INET6, SOCK_DGRAM | SOCK_CLOEXEC, 0)", errno);
        return nullptr;
    }

    if (setsockopt(s, SOL_SOCKET, SO_BINDTODEVICE, iface.c_str(), strlen(iface.c_str()) + 1) == -1) {
        throwIOException(env, "setsockopt(s, SOL_SOCKET, SO_BINDTODEVICE, iface, strlen(iface) + 1)", errno);
        close(s);
        return nullptr;
    }

    sockaddr_in6 sin6 = {.sin6_family = AF_INET6, .sin6_addr = nat64Prefix};
    if (connect(s, reinterpret_cast<struct sockaddr*>(&sin6), sizeof(sin6)) == -1) {
        throwIOException(env, "connect(s, &sin6, sizeof(sin6))", errno);
        close(s);
        return nullptr;
    }

    socklen_t len = sizeof(sin6);
    if (getsockname(s, reinterpret_cast<struct sockaddr*>(&sin6), &len) == -1) {
        throwIOException(env, "getsockname(s, &sin6, &len)", errno);
        close(s);
        return nullptr;
    }

    in6_addr v6 = sin6.sin6_addr;

    if (IN6_IS_ADDR_UNSPECIFIED(&v6) || IN6_IS_ADDR_LOOPBACK(&v6) || IN6_IS_ADDR_LINKLOCAL(&v6) ||
        IN6_IS_ADDR_SITELOCAL(&v6) || IN6_IS_ADDR_ULA(&v6)) {
        jniThrowExceptionFmt(env, "java/io/IOException", "Invalid IPv6 address type");
        close(s);
        return nullptr;
    }
    close(s);

    makeChecksumNeutral(&v6, v4, nat64Prefix);

    char addrstr[INET6_ADDRSTRLEN];
    if (!inet_ntop(AF_INET6, (void *)&v6, addrstr, sizeof(addrstr))) {
        return nullptr;
    }
    return env->NewStringUTF(addrstr);
}

static jint com_android_server_ClatCoordinator_createTunInterface(JNIEnv* env, jobject clazz,
                                                                  jstring iface) {
    ScopedUtfChars v4interface(env, iface);

    // 3. open the tun device in non blocking mode as required by clatd
    jint fd = open("/dev/net/tun", O_RDWR | O_NONBLOCK | O_CLOEXEC);
    if (fd == -1) {
        jniThrowExceptionFmt(env, "java/io/IOException", "open of tun device failed (%s)", strerror(errno));
        return -1;
    }

    // 4. create the v4-... tun interface
    // std::string v4interface("v4-");
    // v4interface += std::string(interface.c_str());

    struct ifreq ifr = {
            .ifr_flags = IFF_TUN,
    };
    strlcpy(ifr.ifr_name, v4interface.c_str(), sizeof(ifr.ifr_name));

    if (ioctl(fd, TUNSETIFF, &ifr, sizeof(ifr))) {
        close(fd);
        jniThrowExceptionFmt(env, "java/io/IOException", "ioctl(TUNSETIFF) failed (%s)", strerror(errno));
        return -1;
    }

    return fd;
}

static jint com_android_server_ClatCoordinator_spawnClatd(JNIEnv* env, jobject clazz,
                                                                  jint tunFd, jstring iface, jstring fwmark, jstring pfx96, jstring v4, jstring v6) {
    ScopedUtfChars ifaceStr(env, iface);
    ScopedUtfChars fwmarkStr(env, fwmark);
    ScopedUtfChars pfx96Str(env, pfx96);
    ScopedUtfChars v4Str(env, v4);
    ScopedUtfChars v6Str(env, v6);

    // 6. create a throwaway socket to reserve a file descriptor number
    int passedTunFd = socket(AF_INET6, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (passedTunFd == -1) {
        throwIOException(env, "socket(ipv6/udp) failed", errno);
        return -1;
    }

    // 7. this is the FD we'll pass to clatd on the cli, so need it as a string
    char passedTunFdStr[INT32_STRLEN];
    snprintf(passedTunFdStr, sizeof(passedTunFdStr), "%d", passedTunFd);

    // 8. we're going to use this as argv[0] to clatd to make ps output more useful
    std::string progname("clatd-");
    progname += ifaceStr.c_str();

    // clang-format off
    const char* args[] = {progname.c_str(),
                          "-i", ifaceStr.c_str(),
                          "-m", fwmarkStr.c_str(),
                          "-p", pfx96Str.c_str(),
                          "-4", v4Str.c_str(),
                          "-6", v6Str.c_str(),
                          "-t", passedTunFdStr,
                          nullptr};
    // clang-format on

    // 9. register vfork requirement
    posix_spawnattr_t attr;
    if (posix_spawnattr_init(&attr)) {
        throwIOException(env, "posix_spawnattr_init failed", errno);
        return -1;
    }
    // TODO: const android::base::ScopeGuard attrGuard = [&] { posix_spawnattr_destroy(&attr); };
    if (posix_spawnattr_setflags(&attr, POSIX_SPAWN_USEVFORK)) {
        throwIOException(env, "posix_spawnattr_setflags failed", errno);
        return -1;
    }

    // 10. register dup2() action: this is what 'clears' the CLOEXEC flag
    // on the tun fd that we want the child clatd process to inherit
    // (this will happen after the vfork, and before the execve)
    posix_spawn_file_actions_t fa;
    if (posix_spawn_file_actions_init(&fa)) {
        throwIOException(env, "posix_spawn_file_actions_init failed", errno);
        return -1;
    }
    // TODO: const android::base::ScopeGuard faGuard = [&] { posix_spawn_file_actions_destroy(&fa); };
    if (posix_spawn_file_actions_adddup2(&fa, tunFd, passedTunFd)) {
        throwIOException(env, "posix_spawn_file_actions_adddup2 failed", errno);
        return -1;
    }

    // 12. actually perform vfork/dup2/execve
    pid_t pid;
    if (posix_spawn(&pid, kClatdPath, &fa, &attr, (char* const*)args, nullptr)) {
        throwIOException(env, "posix_spawn failed", errno);
        return -1;
    }

    return pid;
}

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
        /* name, signature, funcPtr */
        {"createTunInterface", "(Ljava/lang/String;)I",
         (void*)com_android_server_ClatCoordinator_createTunInterface},
        {"selectIpv4Address", "(Ljava/lang/String;I)Ljava/lang/String;",
         (void*)com_android_server_ClatCoordinator_selectIpv4Address},
        {"generateIpv6Address",
          "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
         (void*)com_android_server_ClatCoordinator_generateIpv6Address},
        {"spawnClatd",
          "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I",
         (void*)com_android_server_ClatCoordinator_spawnClatd},
};

int register_android_server_ClatCoordinator(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/server/ClatCoordinator", gMethods,
                                    NELEM(gMethods));
}

}; // namespace android
