/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * main.c - main function
 */

#include <arpa/inet.h>

#include <android-base/unique_fd.h>
#include <log/log.h>
#include <spawn.h>

#include "bpf/BpfMap.h"
#include "TcUtils.h"

#define DEVICEPREFIX "v4-"

using android::base::unique_fd;
using android::net::RAWIP;
using android::net::getClatEgress4MapFd;
using android::net::getClatIngress6MapFd;
using android::net::getClatEgress4ProgFd;
using android::net::getClatIngress6ProgFd;
using android::net::tcQdiscAddDevClsact;
using android::net::tcFilterAddDevEgressClatIpv4;
using android::net::tcFilterAddDevIngressClatIpv6;
using android::net::tcFilterDelDevEgressClatIpv4;
using android::net::tcFilterDelDevIngressClatIpv6;
using android::bpf::BpfMap;

BpfMap<ClatEgress4Key, ClatEgress4Value> mClatEgress4Map;
BpfMap<ClatIngress6Key, ClatIngress6Value> mClatIngress6Map;

namespace {
static const char* kClatdPath = "/apex/com.android.tethering/bin/clatd";

struct ClatdTracker {
    //pid_t pid = -1;
    unsigned ifIndex;
    char iface[IFNAMSIZ];
    unsigned v4ifIndex;
    char v4iface[IFNAMSIZ];
    //Fwmark fwmark;
    //char fwmarkString[UINT32_STRLEN];
    in_addr v4;
    //char v4Str[INET_ADDRSTRLEN];
    in6_addr v6;
    //char v6Str[INET6_ADDRSTRLEN];
    in6_addr pfx96;
    //char pfx96String[INET6_ADDRSTRLEN];
};

int initMaps(void) {
    int rv = getClatEgress4MapFd();
    if (rv < 0) {
        ALOGE("getClatEgress4MapFd() failure: %s", strerror(-rv));
        return -rv;
    }
    mClatEgress4Map.reset(rv);

    rv = getClatIngress6MapFd();
    if (rv < 0) {
        ALOGE("getClatIngress6MapFd() failure: %s", strerror(-rv));
        mClatEgress4Map.reset(-1);
        return -rv;
    }
    mClatIngress6Map.reset(rv);

    //mClatEgress4Map.clear();
    //mClatIngress6Map.clear();
    return 0;
}

void maybeStartBpf(const ClatdTracker& tracker) {
    //+debug
    ALOGI("maybeStartBpf+"); // +debug

    auto isEthernet = android::net::isEthernet(tracker.iface);
    if (!isEthernet.ok()) {
        ALOGE("isEthernet(%s[%d]) failure: %s", tracker.iface, tracker.ifIndex,
              isEthernet.error().message().c_str());
        return;
    }

    ALOGI("isEthernet: %d", isEthernet.value()); // +debug

    // This program will be attached to the v4-* interface which is a TUN and thus always rawip.
    int rv = getClatEgress4ProgFd(RAWIP);
    if (rv < 0) {
        ALOGE("getClatEgress4ProgFd(RAWIP) failure: %s", strerror(-rv));
        return;
    }
    unique_fd txRawIpProgFd(rv);

    rv = getClatIngress6ProgFd(isEthernet.value());
    if (rv < 0) {
        ALOGE("getClatIngress6ProgFd(%d) failure: %s", isEthernet.value(), strerror(-rv));
        return;
    }
    unique_fd rxProgFd(rv);

    ClatEgress4Key txKey = {
            .iif = tracker.v4ifIndex,
            .local4 = tracker.v4,
    };
    ClatEgress4Value txValue = {
            .oif = tracker.ifIndex,
            .local6 = tracker.v6,
            .pfx96 = tracker.pfx96,
            .oifIsEthernet = isEthernet.value(),
    };

    auto ret = mClatEgress4Map.writeValue(txKey, txValue, BPF_ANY);
    if (!ret.ok()) {
        ALOGE("mClatEgress4Map.writeValue failure: %s", strerror(ret.error().code()));
        return;
    }

    ClatIngress6Key rxKey = {
            .iif = tracker.ifIndex,
            .pfx96 = tracker.pfx96,
            .local6 = tracker.v6,
    };
    ClatIngress6Value rxValue = {
            // TODO: move all the clat code to eBPF and remove the tun interface entirely.
            .oif = tracker.v4ifIndex,
            .local4 = tracker.v4,
    };

    ret = mClatIngress6Map.writeValue(rxKey, rxValue, BPF_ANY);
    if (!ret.ok()) {
        ALOGE("mClatIngress6Map.writeValue failure: %s", strerror(ret.error().code()));
        ret = mClatEgress4Map.deleteValue(txKey);
        if (!ret.ok())
            ALOGE("mClatEgress4Map.deleteValue failure: %s", strerror(ret.error().code()));
        return;
    }

    // We do tc setup *after* populating the maps, so scanning through them
    // can always be used to tell us what needs cleanup.

    // Usually the clsact will be added in RouteController::addInterfaceToPhysicalNetwork.
    // But clat is started before the v4- interface is added to the network. The clat startup have
    // to add clsact of v4- tun interface first for adding bpf filter in maybeStartBpf.
    // TODO: move "qdisc add clsact" of v4- tun interface out from ClatdController.
    ALOGE("tcQdiscAddDevClsact .. "); // debug
    rv = tcQdiscAddDevClsact(tracker.v4ifIndex);

    if (rv) {
        ALOGE("tcQdiscAddDevClsact(%d[%s]) failure: %s", tracker.v4ifIndex, tracker.v4iface,
              strerror(-rv));
        ret = mClatEgress4Map.deleteValue(txKey);
        if (!ret.ok())
            ALOGE("mClatEgress4Map.deleteValue failure: %s", strerror(ret.error().code()));
        ret = mClatIngress6Map.deleteValue(rxKey);
        if (!ret.ok())
            ALOGE("mClatIngress6Map.deleteValue failure: %s", strerror(ret.error().code()));
        return;
    }

    rv = tcFilterAddDevEgressClatIpv4(tracker.v4ifIndex, txRawIpProgFd, RAWIP);
    if (rv) {
        ALOGE("tcFilterAddDevEgressClatIpv4(%d[%s], RAWIP) failure: %s", tracker.v4ifIndex,
              tracker.v4iface, strerror(-rv));

        // The v4- interface clsact is not deleted for unwinding error because once it is created
        // with interface addition, the lifetime is till interface deletion. Moreover, the clsact
        // has no clat filter now. It should not break anything.

        ret = mClatEgress4Map.deleteValue(txKey);
        if (!ret.ok())
            ALOGE("mClatEgress4Map.deleteValue failure: %s", strerror(ret.error().code()));
        ret = mClatIngress6Map.deleteValue(rxKey);
        if (!ret.ok())
            ALOGE("mClatIngress6Map.deleteValue failure: %s", strerror(ret.error().code()));
        return;
    }

    rv = tcFilterAddDevIngressClatIpv6(tracker.ifIndex, rxProgFd, isEthernet.value());
    if (rv) {
        ALOGE("tcFilterAddDevIngressClatIpv6(%d[%s], %d) failure: %s", tracker.ifIndex,
              tracker.iface, isEthernet.value(), strerror(-rv));
        rv = tcFilterDelDevEgressClatIpv4(tracker.v4ifIndex);
        if (rv) {
            ALOGE("tcFilterDelDevEgressClatIpv4(%d[%s]) failure: %s", tracker.v4ifIndex,
                  tracker.v4iface, strerror(-rv));
        }

        // The v4- interface clsact is not deleted. See the reason in the error unwinding code of
        // the egress filter attaching of v4- tun interface.

        ret = mClatEgress4Map.deleteValue(txKey);
        if (!ret.ok())
            ALOGE("mClatEgress4Map.deleteValue failure: %s", strerror(ret.error().code()));
        ret = mClatIngress6Map.deleteValue(rxKey);
        if (!ret.ok())
            ALOGE("mClatIngress6Map.deleteValue failure: %s", strerror(ret.error().code()));
        return;
    }

    ALOGI("maybeStartBpf-"); // +debug
    // success
}

void maybeStopBpf(const ClatdTracker& tracker) {
    ALOGI("maybeStopBpf-"); // +debug
    int rv = tcFilterDelDevIngressClatIpv6(tracker.ifIndex);
    if (rv < 0) {
        ALOGE("tcFilterDelDevIngressClatIpv6(%d[%s]) failure: %s", tracker.ifIndex, tracker.iface,
              strerror(-rv));
    }

    rv = tcFilterDelDevEgressClatIpv4(tracker.v4ifIndex);
    if (rv < 0) {
        ALOGE("tcFilterDelDevEgressClatIpv4(%d[%s]) failure: %s", tracker.v4ifIndex,
              tracker.v4iface, strerror(-rv));
    }

    // We cleanup the maps last, so scanning through them can be used to
    // determine what still needs cleanup.

    ClatEgress4Key txKey = {
            .iif = tracker.v4ifIndex,
            .local4 = tracker.v4,
    };

    auto ret = mClatEgress4Map.deleteValue(txKey);
    if (!ret.ok()) ALOGE("mClatEgress4Map.deleteValue failure: %s", strerror(ret.error().code()));

    ClatIngress6Key rxKey = {
            .iif = tracker.ifIndex,
            .pfx96 = tracker.pfx96,
            .local6 = tracker.v6,
    };

    ret = mClatIngress6Map.deleteValue(rxKey);
    if (!ret.ok()) ALOGE("mClatIngress6Map.deleteValue failure: %s", strerror(ret.error().code()));

    ALOGI("maybeStopBpf+"); // +debug
}

}

/* function: print_help
 * in case the user is running this on the command line
 */
void print_help() {
  printf("clat arguments:\n");
  printf("-i [uplink interface]\n");
  printf("-p [plat prefix]\n");
  printf("-4 [IPv4 address]\n");
  printf("-6 [IPv6 address]\n");
  printf("-m [socket mark]\n");
  printf("-t [tun file descriptor number]\n");
  printf("-r [read socket descriptor number]\n");
  printf("-w [write socket descriptor number]\n");
  printf("-s [start clatd]\n");
  printf("-k [stop clatd]\n");
}

/* function: main
 */
int main(int argc, char **argv) {
    ALOGE("clatd-start"); // debug
    int opt;
    bool start = false, stop = false;
    char *uplink_interface = NULL, *plat_prefix = NULL, *mark_str = NULL;
    char *v4_addr = NULL, *v6_addr = NULL, *tunfd_str = NULL, *read_sock_str = NULL,
       *write_sock_str = NULL;
    //uint32_t mark   = MARK_UNSET;
    unsigned len;

    while ((opt = getopt(argc, argv, "i:p:4:6:m:t:r:w:skh")) != -1) {
        switch (opt) {
          case 'i':
            uplink_interface = optarg;
            break;
          case 'p':
            plat_prefix = optarg;
            break;
          case '4':
            v4_addr = optarg;
            break;
          case '6':
            v6_addr = optarg;
            break;
          case 'm':
            mark_str = optarg;
            break;
          case 't':
            tunfd_str = optarg;
            break;
          case 'r':
            read_sock_str = optarg;
            break;
          case 'w':
            write_sock_str = optarg;
            break;
          case 's':
            start = true;
            break;
          case 'k':
            stop = true;
            break;
          case 'h':
            print_help();
            exit(0);
          default:
            ALOGE("Unknown option -%c. Exiting.", (char)optopt);
            exit(1);
        }
    }

    ClatdTracker tracker = {};

    ALOGI("start = %d, stop = %d", start, stop);  // debug

    if (start == false && stop == false) {
        ALOGE("neither start clatd nor stop clatd");
        exit(1);
    }

    if (uplink_interface == NULL) {
        ALOGE("clatd called without an interface");
        exit(1);
    }
    strlcpy(tracker.iface, uplink_interface, sizeof(tracker.iface));

    tracker.ifIndex = if_nametoindex(uplink_interface);
    if (tracker.ifIndex == 0) {
        ALOGE("interface %s not found", tracker.iface);
        exit(1);
    }

    len = snprintf(tracker.v4iface, sizeof(tracker.v4iface), "%s%s", DEVICEPREFIX, uplink_interface);
    if (len >= sizeof(tracker.v4iface)) {
        ALOGE("interface name too long '%s'", tracker.v4iface);
        exit(1);
    }
    tracker.v4ifIndex = if_nametoindex(tracker.v4iface);
    if (tracker.v4ifIndex == 0) {
        ALOGE("interface %s not found", tracker.v4iface);
        exit(1);
    }

    if (!plat_prefix || inet_pton(AF_INET6, plat_prefix, &tracker.pfx96) <= 0) {
        ALOGE("invalid IPv6 address specified for plat prefix: %s", plat_prefix);
        exit(1);
    }

    if (!v4_addr || !inet_pton(AF_INET, v4_addr, &tracker.v4)) {
        ALOGE("Invalid IPv4 address %s", v4_addr);
        exit(1);
    }

    if (!v6_addr || !inet_pton(AF_INET6, v6_addr, &tracker.v6)) {
        ALOGE("Invalid source address %s", v6_addr);
        exit(1);
    }

    if (read_sock_str == NULL) {
        ALOGE("read_sock_str is null");
        exit(1);
    }

    if (write_sock_str == NULL) {
        ALOGE("write_sock_str is null");
        exit(1);
    }

    if (tunfd_str == NULL) {
        ALOGE("tunfd_str is null");
        exit(1);
    }

    ALOGI("clat: uplink=%s mark=%s plat=%s v4=%s v6=%s",
         uplink_interface, mark_str ? mark_str : "(none)",
         plat_prefix ? plat_prefix : "(none)", v4_addr ? v4_addr : "(none)",
         v6_addr ? v6_addr : "(none)");

    if (initMaps()) exit(1);

    maybeStartBpf(tracker);

    std::string progname("clatd-");
    progname += uplink_interface;

    // clang-format off
    const char* args[] = {progname.c_str(),
                          "-i", uplink_interface,
                          "-p", plat_prefix,
                          "-4", v4_addr,
                          "-6", v6_addr,
                          "-t", tunfd_str,
                          "-r", read_sock_str,
                          "-w", write_sock_str,
                          nullptr};
    // clang-format on

    // 3. register vfork requirement
    posix_spawnattr_t attr;
    if (posix_spawnattr_init(&attr)) {
        ALOGE("posix_spawnattr_init failed %d", errno);
        return 1;
    }
    // TODO: const android::base::ScopeGuard attrGuard = [&] { posix_spawnattr_destroy(&attr); };
    if (posix_spawnattr_setflags(&attr, POSIX_SPAWN_USEVFORK)) {
        posix_spawnattr_destroy(&attr);
        ALOGE("posix_spawnattr_setflags failed %d", errno);
        return 1;
    }

    // 4. register dup2() action: this is what 'clears' the CLOEXEC flag
    // on the tun fd that we want the child clatd process to inherit
    // (this will happen after the vfork, and before the execve)
    posix_spawn_file_actions_t fa;
    if (posix_spawn_file_actions_init(&fa)) {
        posix_spawnattr_destroy(&attr);
        ALOGE("posix_spawn_file_actions_init failed %d", errno);
        return 1;
    }
//
//    if (posix_spawn_file_actions_adddup2(&fa, tunFd, passedTunFd)) {
//        posix_spawnattr_destroy(&attr);
//        posix_spawn_file_actions_destroy(&fa);
//        throwIOException(env, "posix_spawn_file_actions_adddup2 for tun fd failed", errno);
//        return 1;
//    }
//    if (posix_spawn_file_actions_adddup2(&fa, readSock, passedSockRead)) {
//        posix_spawnattr_destroy(&attr);
//        posix_spawn_file_actions_destroy(&fa);
//        throwIOException(env, "posix_spawn_file_actions_adddup2 for read socket failed", errno);
//        return 1;
//    }
//    if (posix_spawn_file_actions_adddup2(&fa, writeSock, passedSockWrite)) {
//        posix_spawnattr_destroy(&attr);
//        posix_spawn_file_actions_destroy(&fa);
//        throwIOException(env, "posix_spawn_file_actions_adddup2 for write socket failed", errno);
//        return 1;
//    }

    // 5. actually perform vfork/dup2/execve
    pid_t pid;
    if (posix_spawn(&pid, kClatdPath, &fa, &attr, (char* const*)args, nullptr)) {
        posix_spawnattr_destroy(&attr);
        posix_spawn_file_actions_destroy(&fa);
        ALOGE("posix_spawn failed %d", errno);
        return 1;
    }

    posix_spawnattr_destroy(&attr);
    posix_spawn_file_actions_destroy(&fa);

    ALOGI("clat sleep+");
    sleep(30);
    ALOGI("clat sleep-");
    
    // test only
    maybeStopBpf(tracker);

    //ALOGI("clatd-start %d, %p", argc, argv);
    return 0;
}
