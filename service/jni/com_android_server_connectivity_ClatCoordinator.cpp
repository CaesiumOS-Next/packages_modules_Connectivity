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
#include <linux/if_tun.h>
#include <linux/ioctl.h>
#include <nativehelper/JNIHelp.h>
#include <net/if.h>
#include <spawn.h>
#include <sys/wait.h>
#include <string>

#include <android-base/unique_fd.h>
#include <netjniutils/netjniutils.h>

#include "TcUtils.h"
#include "libclat/clatutils.h"
#include "nativehelper/scoped_utf_chars.h"

// Sync from system/netd/include/netid_client.h
#define MARK_UNSET 0u

// Sync from system/netd/server/NetdConstants.h
#define __INT_STRLEN(i) sizeof(#i)
#define _INT_STRLEN(i) __INT_STRLEN(i)
#define INT32_STRLEN _INT_STRLEN(INT32_MIN)

#define DEVICEPREFIX "v4-"

using android::net::RAWIP;
//using android::net::getClatEgress4MapFd;
//using android::net::getClatIngress6MapFd;
using android::net::getClatEgress4ProgFd;
using android::net::getClatIngress6ProgFd;
using android::net::tcQdiscAddDevClsact;
using android::net::tcFilterAddDevEgressClatIpv4;
using android::net::tcFilterAddDevIngressClatIpv6;
using android::net::tcFilterDelDevEgressClatIpv4;
using android::net::tcFilterDelDevIngressClatIpv6;

namespace android {
//static const char* kClatdPath = "/system/bin/clatd";
static const char* kClatdLuncherPath = "/apex/com.android.tethering/bin/clat";

static void throwIOException(JNIEnv* env, const char* msg, int error) {
    jniThrowExceptionFmt(env, "java/io/IOException", "%s: %s", msg, strerror(error));
}

jstring com_android_server_connectivity_ClatCoordinator_selectIpv4Address(JNIEnv* env,
                                                                          jobject clazz,
                                                                          jstring v4addr,
                                                                          jint prefixlen) {
    ScopedUtfChars address(env, v4addr);
    in_addr ip;
    if (inet_pton(AF_INET, address.c_str(), &ip) != 1) {
        return nullptr;
    }

    // Pick an IPv4 address.
    // TODO: this picks the address based on other addresses that are assigned to interfaces, but
    // the address is only actually assigned to an interface once clatd starts up. So we could end
    // up with two clatd instances with the same IPv4 address.
    // Stop doing this and instead pick a free one from the kV4Addr pool.
    in_addr v4 = {net::clat::selectIpv4Address(ip, prefixlen)};
    if (v4.s_addr == INADDR_NONE) {
        jniThrowExceptionFmt(env, "java/io/IOException", "No free IPv4 address in %s/%d",
                             address.c_str(), prefixlen);
        return nullptr;
    }

    char addrstr[INET_ADDRSTRLEN];
    if (!inet_ntop(AF_INET, (void*)&v4, addrstr, sizeof(addrstr))) {
        return nullptr;
    }
    return env->NewStringUTF(addrstr);
}

// Picks a random interface ID that is checksum neutral with the IPv4 address and the NAT64 prefix.
jstring com_android_server_connectivity_ClatCoordinator_generateIpv6Address(
        JNIEnv* env, jobject clazz, jstring ifaceStr, jstring v4Str, jstring prefix64Str) {
    ScopedUtfChars iface(env, ifaceStr);
    ScopedUtfChars addr4(env, v4Str);
    ScopedUtfChars prefix64(env, prefix64Str);

    if (iface.c_str() == nullptr) {
        jniThrowExceptionFmt(env, "java/io/IOException", "Invalid null interface name");
        return nullptr;
    }

    in_addr v4;
    if (inet_pton(AF_INET, addr4.c_str(), &v4) != 1) {
        jniThrowExceptionFmt(env, "java/io/IOException", "Invalid clat v4 address %s",
                             addr4.c_str());
        return nullptr;
    }

    in6_addr nat64Prefix;
    if (inet_pton(AF_INET6, prefix64.c_str(), &nat64Prefix) != 1) {
        jniThrowExceptionFmt(env, "java/io/IOException", "Invalid prefix %s", prefix64.c_str());
        return nullptr;
    }

    in6_addr v6;
    if (net::clat::generateIpv6Address(iface.c_str(), v4, nat64Prefix, &v6)) {
        jniThrowExceptionFmt(env, "java/io/IOException",
                             "Unable to find global source address on %s for %s", iface.c_str(),
                             prefix64.c_str());
        return nullptr;
    }

    char addrstr[INET6_ADDRSTRLEN];
    if (!inet_ntop(AF_INET6, (void*)&v6, addrstr, sizeof(addrstr))) {
        return nullptr;
    }
    return env->NewStringUTF(addrstr);
}

static jint com_android_server_connectivity_ClatCoordinator_createTunInterface(JNIEnv* env,
                                                                               jobject clazz,
                                                                               jstring tuniface) {
    ScopedUtfChars v4interface(env, tuniface);

    // open the tun device in non blocking mode as required by clatd
    jint fd = open("/dev/net/tun", O_RDWR | O_NONBLOCK | O_CLOEXEC);
    if (fd == -1) {
        jniThrowExceptionFmt(env, "java/io/IOException", "open tun device failed (%s)",
                             strerror(errno));
        return -1;
    }

    struct ifreq ifr = {
            .ifr_flags = IFF_TUN,
    };
    strlcpy(ifr.ifr_name, v4interface.c_str(), sizeof(ifr.ifr_name));

    if (ioctl(fd, TUNSETIFF, &ifr, sizeof(ifr))) {
        close(fd);
        jniThrowExceptionFmt(env, "java/io/IOException", "ioctl(TUNSETIFF) failed (%s)",
                             strerror(errno));
        return -1;
    }

    return fd;
}

static jint com_android_server_connectivity_ClatCoordinator_detectMtu(JNIEnv* env, jobject clazz,
                                                                      jstring platSubnet,
                                                                      jint plat_suffix, jint mark) {
    ScopedUtfChars platSubnetStr(env, platSubnet);

    in6_addr plat_subnet;
    if (inet_pton(AF_INET6, platSubnetStr.c_str(), &plat_subnet) != 1) {
        jniThrowExceptionFmt(env, "java/io/IOException", "Invalid plat prefix address %s",
                             platSubnetStr.c_str());
        return -1;
    }

    int ret = net::clat::detect_mtu(&plat_subnet, plat_suffix, mark);
    if (ret < 0) {
        jniThrowExceptionFmt(env, "java/io/IOException", "detect mtu failed: %s", strerror(-ret));
        return -1;
    }

    return ret;
}

static jint com_android_server_connectivity_ClatCoordinator_openPacketSocket6(JNIEnv* env,
                                                                              jobject clazz) {
    // Will eventually be bound to htons(ETH_P_IPV6) protocol,
    // but only after appropriate bpf filter is attached.
    int sock = socket(AF_PACKET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (sock < 0) {
        throwIOException(env, "packet socket failed", errno);
        return -1;
    }
    return sock;
}

static jint com_android_server_connectivity_ClatCoordinator_openRawSocket6(JNIEnv* env,
                                                                           jobject clazz,
                                                                           jint mark) {
    int sock = socket(AF_INET6, SOCK_RAW | SOCK_NONBLOCK | SOCK_CLOEXEC, IPPROTO_RAW);
    if (sock < 0) {
        throwIOException(env, "raw socket failed", errno);
        return -1;
    }

    // TODO: check the mark validation
    if (mark != MARK_UNSET && setsockopt(sock, SOL_SOCKET, SO_MARK, &mark, sizeof(mark)) < 0) {
        throwIOException(env, "could not set mark on raw socket", errno);
        close(sock);
        return -1;
    }

    return sock;
}

static void com_android_server_connectivity_ClatCoordinator_addAnycastSetsockopt(
        JNIEnv* env, jobject clazz, jobject javaFd, jstring addr6, jint ifindex) {
    int sock = netjniutils::GetNativeFileDescriptor(env, javaFd);
    if (sock < 0) {
        jniThrowExceptionFmt(env, "java/io/IOException", "Invalid file descriptor");
        return;
    }

    ScopedUtfChars addrStr(env, addr6);

    in6_addr addr;
    if (inet_pton(AF_INET6, addrStr.c_str(), &addr) != 1) {
        jniThrowExceptionFmt(env, "java/io/IOException", "Invalid IPv6 address %s",
                             addrStr.c_str());
        return;
    }

    struct ipv6_mreq mreq = {addr, ifindex};
    int ret = setsockopt(sock, SOL_IPV6, IPV6_JOIN_ANYCAST, &mreq, sizeof(mreq));
    if (ret) {
        jniThrowExceptionFmt(env, "java/io/IOException", "setsockopt IPV6_JOIN_ANYCAST failed: %s",
                             strerror(errno));
        return;
    }
}

static void com_android_server_connectivity_ClatCoordinator_configurePacketSocket(
        JNIEnv* env, jobject clazz, jobject javaFd, jstring addr6, jint ifindex) {
    ScopedUtfChars addrStr(env, addr6);

    int sock = netjniutils::GetNativeFileDescriptor(env, javaFd);
    if (sock < 0) {
        jniThrowExceptionFmt(env, "java/io/IOException", "Invalid file descriptor");
        return;
    }

    in6_addr addr;
    if (inet_pton(AF_INET6, addrStr.c_str(), &addr) != 1) {
        jniThrowExceptionFmt(env, "java/io/IOException", "Invalid IPv6 address %s",
                             addrStr.c_str());
        return;
    }

    int ret = net::clat::configure_packet_socket(sock, &addr, ifindex);
    if (ret < 0) {
        throwIOException(env, "configure packet socket failed", -ret);
        return;
    }
}

static jint com_android_server_connectivity_ClatCoordinator_startClatd(
        JNIEnv* env, jobject clazz, jobject tunJavaFd, jobject readSockJavaFd,
        jobject writeSockJavaFd, jstring iface, jstring pfx96, jstring v4, jstring v6) {
    ScopedUtfChars ifaceStr(env, iface);
    ScopedUtfChars pfx96Str(env, pfx96);
    ScopedUtfChars v4Str(env, v4);
    ScopedUtfChars v6Str(env, v6);

    int tunFd = netjniutils::GetNativeFileDescriptor(env, tunJavaFd);
    if (tunFd < 0) {
        jniThrowExceptionFmt(env, "java/io/IOException", "Invalid tun file descriptor");
        return -1;
    }

    int readSock = netjniutils::GetNativeFileDescriptor(env, readSockJavaFd);
    if (readSock < 0) {
        jniThrowExceptionFmt(env, "java/io/IOException", "Invalid read socket");
        return -1;
    }

    int writeSock = netjniutils::GetNativeFileDescriptor(env, writeSockJavaFd);
    if (writeSock < 0) {
        jniThrowExceptionFmt(env, "java/io/IOException", "Invalid write socket");
        return -1;
    }

    // 1. create a throwaway socket to reserve a file descriptor number
    int passedTunFd = socket(AF_INET6, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (passedTunFd == -1) {
        throwIOException(env, "socket(ipv6/udp) for tun fd failed", errno);
        return -1;
    }
    int passedSockRead = socket(AF_INET6, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (passedSockRead == -1) {
        throwIOException(env, "socket(ipv6/udp) for read socket failed", errno);
        return -1;
    }
    int passedSockWrite = socket(AF_INET6, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (passedSockWrite == -1) {
        throwIOException(env, "socket(ipv6/udp) for write socket failed", errno);
        return -1;
    }

    // these are the FD we'll pass to clatd on the cli, so need it as a string
    char passedTunFdStr[INT32_STRLEN];
    char passedSockReadStr[INT32_STRLEN];
    char passedSockWriteStr[INT32_STRLEN];
    snprintf(passedTunFdStr, sizeof(passedTunFdStr), "%d", passedTunFd);
    snprintf(passedSockReadStr, sizeof(passedSockReadStr), "%d", passedSockRead);
    snprintf(passedSockWriteStr, sizeof(passedSockWriteStr), "%d", passedSockWrite);

    // 2. we're going to use this as argv[0] to clatd to make ps output more useful
    std::string progname("clat-");
    progname += ifaceStr.c_str();

    // clang-format off
    const char* args[] = {progname.c_str(),
                          "-i", ifaceStr.c_str(),
                          "-p", pfx96Str.c_str(),
                          "-4", v4Str.c_str(),
                          "-6", v6Str.c_str(),
                          "-t", passedTunFdStr,
                          "-r", passedSockReadStr,
                          "-w", passedSockWriteStr,
                          "-s",
                          nullptr};

    // 3. register vfork requirement
    posix_spawnattr_t attr;
    if (posix_spawnattr_init(&attr)) {
        throwIOException(env, "posix_spawnattr_init failed", errno);
        return -1;
    }
    //const android::base::ScopeGuard attrGuard = [&] { posix_spawnattr_destroy(&attr); };
    if (posix_spawnattr_setflags(&attr, POSIX_SPAWN_USEVFORK)) {
        posix_spawnattr_destroy(&attr);
        throwIOException(env, "posix_spawnattr_setflags failed", errno);
        return -1;
    }

    // 4. register dup2() action: this is what 'clears' the CLOEXEC flag
    // on the tun fd that we want the child clatd process to inherit
    // (this will happen after the vfork, and before the execve)
    posix_spawn_file_actions_t fa;
    if (posix_spawn_file_actions_init(&fa)) {
        posix_spawnattr_destroy(&attr);
        throwIOException(env, "posix_spawn_file_actions_init failed", errno);
        return -1;
    }

    if (posix_spawn_file_actions_adddup2(&fa, tunFd, passedTunFd)) {
        posix_spawnattr_destroy(&attr);
        posix_spawn_file_actions_destroy(&fa);
        throwIOException(env, "posix_spawn_file_actions_adddup2 for tun fd failed", errno);
        return -1;
    }
    if (posix_spawn_file_actions_adddup2(&fa, readSock, passedSockRead)) {
        posix_spawnattr_destroy(&attr);
        posix_spawn_file_actions_destroy(&fa);
        throwIOException(env, "posix_spawn_file_actions_adddup2 for read socket failed", errno);
        return -1;
    }
    if (posix_spawn_file_actions_adddup2(&fa, writeSock, passedSockWrite)) {
        posix_spawnattr_destroy(&attr);
        posix_spawn_file_actions_destroy(&fa);
        throwIOException(env, "posix_spawn_file_actions_adddup2 for write socket failed", errno);
        return -1;
    }

    // 5. actually perform vfork/dup2/execve
//    pid_t pid;
//    if (posix_spawn(&pid, kClatdPath, &fa, &attr, (char* const*)args, nullptr)) {
//        posix_spawnattr_destroy(&attr);
//        posix_spawn_file_actions_destroy(&fa);
//        throwIOException(env, "posix_spawn failed", errno);
//        return -1;
//    }

    pid_t pid;
    if (posix_spawn(&pid, kClatdLuncherPath, &fa, &attr, (char* const*)args, nullptr)) {
        posix_spawnattr_destroy(&attr);
        posix_spawn_file_actions_destroy(&fa);
        throwIOException(env, "posix_spawn failed", errno);
        return -1;
    }
    posix_spawnattr_destroy(&attr);
    posix_spawn_file_actions_destroy(&fa);

    //+debug
    unsigned ifIndex = if_nametoindex(ifaceStr.c_str());
    if (ifIndex == 0) {
        ALOGE("interface %s not found", ifaceStr.c_str());
        jniThrowExceptionFmt(env, "java/io/IOException", "interface %s not found", ifaceStr.c_str());
        exit(1);
    }
    
    auto isEthernet = android::net::isEthernet(ifaceStr.c_str());
    if (!isEthernet.ok()) {
        ALOGE("isEthernet(%s[%d]) failure: %s", ifaceStr.c_str(), ifIndex,
              isEthernet.error().message().c_str());
        jniThrowExceptionFmt(env, "java/io/IOException", "isEthernet(%s[%d]) failure: %s", ifaceStr.c_str(), ifIndex,
              isEthernet.error().message().c_str());
        return -1;
    }

    int v4ifIndex;
    char v4iface[IFNAMSIZ];

    unsigned len = snprintf(v4iface, sizeof(v4iface), "%s%s", DEVICEPREFIX, ifaceStr.c_str());
    if (len >= sizeof(v4iface)) {
        ALOGE("interface name too long '%s'", v4iface);
        jniThrowExceptionFmt(env, "java/io/IOException", "interface name too long '%s'", v4iface);
        return -1;
    }
    v4ifIndex = if_nametoindex(v4iface);
    if (v4ifIndex == 0) {
        ALOGE("interface %s not found", v4iface);
        jniThrowExceptionFmt(env, "java/io/IOException", "interface %s not found", v4iface);
        return -1;
    }

    int rv = tcQdiscAddDevClsact(v4ifIndex);
    ALOGI("[SSD] tcQdiscAddDevClsact(%d[%s]) rv = %d", v4ifIndex, v4iface, rv); // +debug
    if (rv) {
        ALOGE("[SSD] tcQdiscAddDevClsact(%d[%s]) failure: %s", v4ifIndex, v4iface,
              strerror(-rv));
        jniThrowExceptionFmt(env, "java/io/IOException", "tcQdiscAddDevClsact(%d[%s]) failure: %s", v4ifIndex, v4iface,
              strerror(-rv));
        //ret = mClatEgress4Map.deleteValue(txKey);
        //if (!ret.ok())
        //    ALOGE("mClatEgress4Map.deleteValue failure: %s", strerror(ret.error().code()));
        //ret = mClatIngress6Map.deleteValue(rxKey);
        //if (!ret.ok())
        //    ALOGE("mClatIngress6Map.deleteValue failure: %s", strerror(ret.error().code()));
        return -1;
    }

    // This program will be attached to the v4-* interface which is a TUN and thus always rawip.
    rv = getClatEgress4ProgFd(RAWIP);
    ALOGI("[SSD] getClatEgress4ProgFd(RAWIP) rv = %d", rv); // +debug
    if (rv < 0) {
        ALOGE("[SSD] getClatEgress4ProgFd(RAWIP) failure: %s", strerror(-rv));
        jniThrowExceptionFmt(env, "java/io/IOException", "getClatEgress4ProgFd(RAWIP) failure: %s", strerror(-rv));
        return -1;
    }
    base::unique_fd txRawIpProgFd(rv);

    rv = getClatIngress6ProgFd(isEthernet.value());
    ALOGI("[SSD] getClatIngress6ProgFd(%d) rv = %d", isEthernet.value(), rv); // +debug
    if (rv < 0) {
        ALOGE("[SSD] getClatIngress6ProgFd(%d) failure: %s", isEthernet.value(), strerror(-rv));
        jniThrowExceptionFmt(env, "java/io/IOException", "getClatIngress6ProgFd(%d) failure: %s", isEthernet.value(), strerror(-rv));
        return -1;
    }
    base::unique_fd rxProgFd(rv);

    rv = tcFilterAddDevEgressClatIpv4(v4ifIndex, txRawIpProgFd, RAWIP);
    ALOGI("[SSD] tcFilterAddDevEgressClatIpv4() rv = %d", rv); // +debug
    if (rv) {
        ALOGE("[SSD] tcFilterAddDevEgressClatIpv4(%d[%s], RAWIP) failure: %s", v4ifIndex,
              v4iface, strerror(-rv));
        jniThrowExceptionFmt(env, "java/io/IOException", "tcFilterAddDevEgressClatIpv4(%d[%s], RAWIP) failure: %s", v4ifIndex,
              v4iface, strerror(-rv));

        // The v4- interface clsact is not deleted for unwinding error because once it is created
        // with interface addition, the lifetime is till interface deletion. Moreover, the clsact
        // has no clat filter now. It should not break anything.

        //ret = mClatEgress4Map.deleteValue(txKey);
        //if (!ret.ok())
        //    ALOGE("mClatEgress4Map.deleteValue failure: %s", strerror(ret.error().code()));
        //ret = mClatIngress6Map.deleteValue(rxKey);
        //if (!ret.ok())
        //    ALOGE("mClatIngress6Map.deleteValue failure: %s", strerror(ret.error().code()));
        return -1;
    }

    rv = tcFilterAddDevIngressClatIpv6(ifIndex, rxProgFd, isEthernet.value());
    ALOGI("[SSD] tcFilterAddDevIngressClatIpv6() rv = %d", rv); // +debug
    if (rv) {
        ALOGE("[SSD] tcFilterAddDevIngressClatIpv6(%d[%s], %d) failure: %s", ifIndex,
              ifaceStr.c_str(), isEthernet.value(), strerror(-rv));
        jniThrowExceptionFmt(env, "java/io/IOException", "tcFilterAddDevIngressClatIpv6(%d[%s], %d) failure: %s", ifIndex,
              ifaceStr.c_str(), isEthernet.value(), strerror(-rv));

        rv = tcFilterDelDevEgressClatIpv4(v4ifIndex);
        if (rv) {
            ALOGE("[SSD] tcFilterDelDevEgressClatIpv4(%d[%s]) failure: %s", v4ifIndex,
                  v4iface, strerror(-rv));
            jniThrowExceptionFmt(env, "java/io/IOException", "tcFilterDelDevEgressClatIpv4(%d[%s]) failure: %s", v4ifIndex,
                  v4iface, strerror(-rv));
        }

        // The v4- interface clsact is not deleted. See the reason in the error unwinding code of
        // the egress filter attaching of v4- tun interface.

        //ret = mClatEgress4Map.deleteValue(txKey);
        //if (!ret.ok())
        //    ALOGE("mClatEgress4Map.deleteValue failure: %s", strerror(ret.error().code()));
        //ret = mClatIngress6Map.deleteValue(rxKey);
        //if (!ret.ok())
        //    ALOGE("mClatIngress6Map.deleteValue failure: %s", strerror(ret.error().code()));
        return -1;
    }
    return pid;
}


static void com_android_server_connectivity_ClatCoordinator_stopClatd(JNIEnv* env, jobject clazz,
                                                                      jstring iface, jstring pfx96,
                                                                      jstring v4, jstring v6,
                                                                      jint killPid) {
    ScopedUtfChars ifaceStr(env, iface);
    ScopedUtfChars pfx96Str(env, pfx96);
    ScopedUtfChars v4Str(env, v4);
    ScopedUtfChars v6Str(env, v6);

    if (killPid == -1) {
        jniThrowExceptionFmt(env, "java/io/IOException", "Invalid pid");
        return;
    }

    unsigned ifIndex = if_nametoindex(ifaceStr.c_str());
    if (ifIndex == 0) {
        ALOGW("interface %s not found", ifaceStr.c_str());
    }

    int v4ifIndex;
    char v4iface[IFNAMSIZ];
    unsigned len = snprintf(v4iface, sizeof(v4iface), "%s%s", DEVICEPREFIX, ifaceStr.c_str());
    if (len >= sizeof(v4iface)) {
        ALOGW("interface name too long '%s'", v4iface);
    }
    unsigned v4ifIndex = if_nametoindex(v4iface);
    v4ifIndex = if_nametoindex(v4iface);
    if (v4ifIndex == 0) {
        ALOGW("interface %s not found", v4iface);
    }

    int rv
    if (rv = tcFilterDelDevIngressClatIpv6(ifIndex) < 0) [
        ALOGW("tcFilterDelDevIngressClatIpv6(%d[%s]) failure: %s", ifIndex, ifaceStr.c_str(),
              strerror(-rv));
    }
    if (rv = tcFilterDelDevEgressClatIpv4(v4ifIndex) < 0) {
        ALOGW("tcFilterDelDevEgressClatIpv4(%d[%s]) failure: %s", v4ifIndex,
              v4iface, strerror(-rv));
    }

    std::string progname("clat-clear-");
    progname += ifaceStr.c_str();

    // clang-format off
    const char* args[] = {progname.c_str(),
                          "-i", ifaceStr.c_str(),
                          "-p", pfx96Str.c_str(),
                          "-4", v4Str.c_str(),
                          "-6", v6Str.c_str(),
                          "-k",
                          nullptr};
    // clang-format on
    posix_spawnattr_t attr;
    if (posix_spawnattr_init(&attr)) {
        throwIOException(env, "posix_spawnattr_init failed", errno);
        return;
    }

    posix_spawn_file_actions_t fa;
    if (posix_spawn_file_actions_init(&fa)) {
        posix_spawnattr_destroy(&attr);
        throwIOException(env, "posix_spawn_file_actions_init failed", errno);
        return ;
    }

    pid_t clearPid;
    if (posix_spawn(&clearPid, kClatdLuncherPath, &fa, &attr, (char* const*)args, nullptr)) {
        posix_spawnattr_destroy(&attr);
        posix_spawn_file_actions_destroy(&fa);
        throwIOException(env, "posix_spawn failed", errno);
        return ;
    }
    posix_spawnattr_destroy(&attr);
    posix_spawn_file_actions_destroy(&fa);
    waitpid(clearPid, nullptr, 0);

    kill(killPid, SIGTERM);
    waitpid(killPid, nullptr, 0);
}

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
        /* name, signature, funcPtr */
        {"selectIpv4Address", "(Ljava/lang/String;I)Ljava/lang/String;",
         (void*)com_android_server_connectivity_ClatCoordinator_selectIpv4Address},
        {"generateIpv6Address",
         "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
         (void*)com_android_server_connectivity_ClatCoordinator_generateIpv6Address},
        {"createTunInterface", "(Ljava/lang/String;)I",
         (void*)com_android_server_connectivity_ClatCoordinator_createTunInterface},
        {"detectMtu", "(Ljava/lang/String;II)I",
         (void*)com_android_server_connectivity_ClatCoordinator_detectMtu},
        {"openPacketSocket6", "()I",
         (void*)com_android_server_connectivity_ClatCoordinator_openPacketSocket6},
        {"openRawSocket6", "(I)I",
         (void*)com_android_server_connectivity_ClatCoordinator_openRawSocket6},
        {"addAnycastSetsockopt", "(Ljava/io/FileDescriptor;Ljava/lang/String;I)V",
         (void*)com_android_server_connectivity_ClatCoordinator_addAnycastSetsockopt},
        {"configurePacketSocket", "(Ljava/io/FileDescriptor;Ljava/lang/String;I)V",
         (void*)com_android_server_connectivity_ClatCoordinator_configurePacketSocket},
        {"startClatd",
         "(Ljava/io/FileDescriptor;Ljava/io/FileDescriptor;Ljava/io/FileDescriptor;Ljava/lang/"
         "String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I",
         (void*)com_android_server_connectivity_ClatCoordinator_startClatd},
        {"stopClatd",
         "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V",
         (void*)com_android_server_connectivity_ClatCoordinator_stopClatd},
};

int register_android_server_connectivity_ClatCoordinator(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/server/connectivity/ClatCoordinator",
                                    gMethods, NELEM(gMethods));
}

};  // namespace android
