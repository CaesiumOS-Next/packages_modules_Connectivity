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

#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <linux/netlink.h>
#include <sys/socket.h>
#include <net/if.h>
#include <linux/pkt_cls.h>
#include <linux/pkt_sched.h>
#include <arpa/inet.h>
#include <linux/rtnetlink.h>
#include <linux/if_ether.h>
#include <algorithm>
#include <tuple> // std::ignore

#include "bpf_tethering.h"

#define BPF_FD_JUST_USE_INT
#include "BpfSyscallWrappers.h"

namespace android {

// system/netd/libnetdbpf/include/netdbpf/bpf_shared.h
#define BPF_PATH "/sys/fs/bpf/"
#define BPF_PATH_TETHER BPF_PATH "tethering/"

#define CLAT_INGRESS6_PROG_RAWIP_NAME "prog_clatd_schedcls_ingress6_clat_rawip"
#define CLAT_INGRESS6_PROG_ETHER_NAME "prog_clatd_schedcls_ingress6_clat_ether"

#define CLAT_EGRESS4_PROG_RAWIP_NAME "prog_clatd_schedcls_egress4_clat_rawip"
#define CLAT_EGRESS4_PROG_ETHER_NAME "prog_clatd_schedcls_egress4_clat_ether"

#define TETHER_DOWNSTREAM6_TC_PROG_RAWIP_NAME "prog_offload_schedcls_tether_downstream6_rawip"
#define TETHER_DOWNSTREAM6_TC_PROG_ETHER_NAME "prog_offload_schedcls_tether_downstream6_ether"

#define TETHER_UPSTREAM6_TC_PROG_RAWIP_NAME "prog_offload_schedcls_tether_upstream6_rawip"
#define TETHER_UPSTREAM6_TC_PROG_ETHER_NAME "prog_offload_schedcls_tether_upstream6_ether"

#define TETHER_DOWNSTREAM4_TC_PROG_RAWIP_NAME "prog_offload_schedcls_tether_downstream4_rawip"
#define TETHER_DOWNSTREAM4_TC_PROG_ETHER_NAME "prog_offload_schedcls_tether_downstream4_ether"

#define TETHER_UPSTREAM4_TC_PROG_RAWIP_NAME "prog_offload_schedcls_tether_upstream4_rawip"
#define TETHER_UPSTREAM4_TC_PROG_ETHER_NAME "prog_offload_schedcls_tether_upstream4_ether"

#define TETHER_DOWNSTREAM6_TC_PROG_ETHER_PATH BPF_PATH_TETHER TETHER_DOWNSTREAM6_TC_PROG_ETHER_NAME

constexpr uint16_t PRIO_TETHER6 = 1;
constexpr uint16_t PRIO_TETHER4 = 2;
constexpr uint16_t PRIO_CLAT = 3;

// For better code clarity - do not change values - used for booleans like
// with_ethernet_header or isEthernet.
constexpr bool RAWIP = false;
constexpr bool ETHER = true;

// For better code clarity when used for 'bool ingress' parameter.
constexpr bool EGRESS = false;
constexpr bool INGRESS = true;

// For better code clarify when used for 'bool downstream' parameter.
//
// This is talking about the direction of travel of the offloaded packets.
//
// Upstream means packets heading towards the internet/uplink (upload),
// thus for tethering this is attached to ingress on the downstream interface,
// while for clat this is attached to egress on the v4-* clat interface.
//
// Downstream means packets coming from the internet/uplink (download), thus
// for both clat and tethering this is attached to ingress on the upstream interface.
constexpr bool UPSTREAM = false;
constexpr bool DOWNSTREAM = true;

const uint16_t NETLINK_REQUEST_FLAGS = NLM_F_REQUEST | NLM_F_ACK;
const sockaddr_nl KERNEL_NLADDR = {AF_NETLINK, 0, 0, 0};

static int sendAndProcessNetlinkResponse(const void* req, int len) {
    jint fd = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC, NETLINK_ROUTE);
    if (fd == -1) {
        const jint err = errno;
        //ALOGE("socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC, NETLINK_ROUTE)");
        return -err;
    }

    static constexpr jint on = 1;
    jint rv = setsockopt(fd, SOL_NETLINK, NETLINK_CAP_ACK, &on, sizeof(on));
    //if (rv) ALOGE("setsockopt(fd, SOL_NETLINK, NETLINK_CAP_ACK, %d)", on);

    // this is needed to get sane strace netlink parsing, it allocates the pid
    rv = bind(fd, (const struct sockaddr*)&KERNEL_NLADDR, sizeof(KERNEL_NLADDR));
    if (rv) {
        const jint err = errno;
        //ALOGE("bind(fd, {AF_NETLINK, 0, 0})");
        return -err;
    }

    // we do not want to receive messages from anyone besides the kernel
    rv = connect(fd, (const struct sockaddr*)&KERNEL_NLADDR, sizeof(KERNEL_NLADDR));
    if (rv) {
        const jint err = errno;
        //ALOGE("connect(fd, {AF_NETLINK, 0, 0})");
        return -err;
    }

    rv = send(fd, req, len, 0);
    if (rv == -1) return -errno;
    if (rv != len) return -EMSGSIZE;

    struct {
        nlmsghdr h;
        nlmsgerr e;
        char buf[256];
    } resp = {};

    rv = recv(fd, &resp, sizeof(resp), MSG_TRUNC);

    if (rv == -1) {
        const int err = errno;
        //ALOGE("recv() failed");
        return -err;
    }

    if (rv < (int)NLMSG_SPACE(sizeof(struct nlmsgerr))) {
        //ALOGE("recv() returned short packet: %d", rv);
        return -EMSGSIZE;
    }

    if (resp.h.nlmsg_len != (unsigned)rv) {
        //ALOGE("recv() returned invalid header length: %d != %d", resp.h.nlmsg_len, rv);
        return -EBADMSG;
    }

    if (resp.h.nlmsg_type != NLMSG_ERROR) {
        //ALOGE("recv() did not return NLMSG_ERROR message: %d", resp.h.nlmsg_type);
        return -EBADMSG;
    }

    return resp.e.error;  // returns 0 on success
}

// ADD:     nlMsgType=RTM_NEWQDISC nlMsgFlags=NLM_F_EXCL|NLM_F_CREATE
// REPLACE: nlMsgType=RTM_NEWQDISC nlMsgFlags=NLM_F_CREATE|NLM_F_REPLACE
// DEL:     nlMsgType=RTM_DELQDISC nlMsgFlags=0
int doTcQdiscClsact(int ifIndex, uint16_t nlMsgType, uint16_t nlMsgFlags) {
    // This is the name of the qdisc we are attaching.
    // Some hoop jumping to make this compile time constant with known size,
    // so that the structure declaration is well defined at compile time.
#define CLSACT "clsact"
    // sizeof() includes the terminating NULL
    static constexpr size_t ASCIIZ_LEN_CLSACT = sizeof(CLSACT);

    const struct {
        nlmsghdr n;
        tcmsg t;
        struct {
            nlattr attr;
            char str[NLMSG_ALIGN(ASCIIZ_LEN_CLSACT)];
        } kind;
    } req = {
            .n =
                    {
                            .nlmsg_len = sizeof(req),
                            .nlmsg_type = nlMsgType,
                            .nlmsg_flags = static_cast<__u16>(NETLINK_REQUEST_FLAGS | nlMsgFlags),
                    },
            .t =
                    {
                            .tcm_family = AF_UNSPEC,
                            .tcm_ifindex = ifIndex,
                            .tcm_handle = TC_H_MAKE(TC_H_CLSACT, 0),
                            .tcm_parent = TC_H_CLSACT,
                    },
            .kind =
                    {
                            .attr =
                                    {
                                            .nla_len = NLA_HDRLEN + ASCIIZ_LEN_CLSACT,
                                            .nla_type = TCA_KIND,
                                    },
                            .str = CLSACT,
                    },
    };
#undef CLSACT

    return sendAndProcessNetlinkResponse(&req, sizeof(req));
}

// tc filter add dev .. in/egress prio 1 protocol ipv6/ip bpf object-pinned /sys/fs/bpf/...
// direct-action
int tcFilterAddDevBpf(int ifIndex, bool ingress, uint16_t prio, uint16_t proto, int bpfFd,
                      bool ethernet, bool downstream) {
    // This is the name of the filter we're attaching (ie. this is the 'bpf'
    // packet classifier enabled by kernel config option CONFIG_NET_CLS_BPF.
    //
    // We go through some hoops in order to make this compile time constants
    // so that we can define the struct further down the function with the
    // field for this sized correctly already during the build.
#define BPF "bpf"
    // sizeof() includes the terminating NULL
    static constexpr size_t ASCIIZ_LEN_BPF = sizeof(BPF);

    // This is to replicate program name suffix used by 'tc' Linux cli
    // when it attaches programs.
#define FSOBJ_SUFFIX ":[*fsobj]"

    // This macro expands (from header files) to:
    //   prog_clatd_schedcls_ingress6_clat_rawip:[*fsobj]
    // and is the name of the pinned ingress ebpf program for ARPHRD_RAWIP interfaces.
    // (also compatible with anything that has 0 size L2 header)
    static constexpr char name_clat_rx_rawip[] = CLAT_INGRESS6_PROG_RAWIP_NAME FSOBJ_SUFFIX;

    // This macro expands (from header files) to:
    //   prog_clatd_schedcls_ingress6_clat_ether:[*fsobj]
    // and is the name of the pinned ingress ebpf program for ARPHRD_ETHER interfaces.
    // (also compatible with anything that has standard ethernet header)
    static constexpr char name_clat_rx_ether[] = CLAT_INGRESS6_PROG_ETHER_NAME FSOBJ_SUFFIX;

    // This macro expands (from header files) to:
    //   prog_clatd_schedcls_egress4_clat_rawip:[*fsobj]
    // and is the name of the pinned egress ebpf program for ARPHRD_RAWIP interfaces.
    // (also compatible with anything that has 0 size L2 header)
    static constexpr char name_clat_tx_rawip[] = CLAT_EGRESS4_PROG_RAWIP_NAME FSOBJ_SUFFIX;

    // This macro expands (from header files) to:
    //   prog_clatd_schedcls_egress4_clat_ether:[*fsobj]
    // and is the name of the pinned egress ebpf program for ARPHRD_ETHER interfaces.
    // (also compatible with anything that has standard ethernet header)
    static constexpr char name_clat_tx_ether[] = CLAT_EGRESS4_PROG_ETHER_NAME FSOBJ_SUFFIX;

    // This macro expands (from header files) to:
    //   prog_offload_schedcls_tether_downstream6_rawip:[*fsobj]
    // and is the name of the pinned ingress ebpf program for ARPHRD_RAWIP interfaces.
    // (also compatible with anything that has 0 size L2 header)
    static constexpr char name_tether_down6_rawip[] =
            TETHER_DOWNSTREAM6_TC_PROG_RAWIP_NAME FSOBJ_SUFFIX;

    // This macro expands (from header files) to:
    //   prog_offload_schedcls_tether_downstream6_ether:[*fsobj]
    // and is the name of the pinned ingress ebpf program for ARPHRD_ETHER interfaces.
    // (also compatible with anything that has standard ethernet header)
    static constexpr char name_tether_down6_ether[] =
            TETHER_DOWNSTREAM6_TC_PROG_ETHER_NAME FSOBJ_SUFFIX;

    // This macro expands (from header files) to:
    //   prog_offload_schedcls_tether_downstream4_rawip:[*fsobj]
    // and is the name of the pinned ingress ebpf program for ARPHRD_RAWIP interfaces.
    // (also compatible with anything that has 0 size L2 header)
    static constexpr char name_tether_down4_rawip[] =
            TETHER_DOWNSTREAM4_TC_PROG_RAWIP_NAME FSOBJ_SUFFIX;

    // This macro expands (from header files) to:
    //   prog_offload_schedcls_tether_downstream4_ether:[*fsobj]
    // and is the name of the pinned ingress ebpf program for ARPHRD_ETHER interfaces.
    // (also compatible with anything that has standard ethernet header)
    static constexpr char name_tether_down4_ether[] =
            TETHER_DOWNSTREAM4_TC_PROG_ETHER_NAME FSOBJ_SUFFIX;

    // This macro expands (from header files) to:
    //   prog_offload_schedcls_tether_upstream6_rawip:[*fsobj]
    // and is the name of the pinned ingress ebpf program for ARPHRD_RAWIP interfaces.
    // (also compatible with anything that has 0 size L2 header)
    static constexpr char name_tether_up6_rawip[] =
            TETHER_UPSTREAM6_TC_PROG_RAWIP_NAME FSOBJ_SUFFIX;

    // This macro expands (from header files) to:
    //   prog_offload_schedcls_tether_upstream6_ether:[*fsobj]
    // and is the name of the pinned ingress ebpf program for ARPHRD_ETHER interfaces.
    // (also compatible with anything that has standard ethernet header)
    static constexpr char name_tether_up6_ether[] =
            TETHER_UPSTREAM6_TC_PROG_ETHER_NAME FSOBJ_SUFFIX;

    // This macro expands (from header files) to:
    //   prog_offload_schedcls_tether_upstream4_rawip:[*fsobj]
    // and is the name of the pinned ingress ebpf program for ARPHRD_RAWIP interfaces.
    // (also compatible with anything that has 0 size L2 header)
    static constexpr char name_tether_up4_rawip[] =
            TETHER_UPSTREAM4_TC_PROG_RAWIP_NAME FSOBJ_SUFFIX;

    // This macro expands (from header files) to:
    //   prog_offload_schedcls_tether_upstream4_ether:[*fsobj]
    // and is the name of the pinned ingress ebpf program for ARPHRD_ETHER interfaces.
    // (also compatible with anything that has standard ethernet header)
    static constexpr char name_tether_up4_ether[] =
            TETHER_UPSTREAM4_TC_PROG_ETHER_NAME FSOBJ_SUFFIX;

#undef FSOBJ_SUFFIX

    // The actual name we'll use is determined at run time via 'ethernet' and 'ingress'
    // booleans.  We need to compile time allocate enough space in the struct
    // hence this macro magic to make sure we have enough space for either
    // possibility.  In practice some of these are actually the same size.
    static constexpr size_t ASCIIZ_MAXLEN_NAME = std::max({
            sizeof(name_clat_rx_rawip),
            sizeof(name_clat_rx_ether),
            sizeof(name_clat_tx_rawip),
            sizeof(name_clat_tx_ether),
            sizeof(name_tether_down6_rawip),
            sizeof(name_tether_down6_ether),
            sizeof(name_tether_down4_rawip),
            sizeof(name_tether_down4_ether),
            sizeof(name_tether_up6_rawip),
            sizeof(name_tether_up6_ether),
            sizeof(name_tether_up4_rawip),
            sizeof(name_tether_up4_ether),
    });

    // These are not compile time constants: 'name' is used in strncpy below
    const char* const name_clat_rx = ethernet ? name_clat_rx_ether : name_clat_rx_rawip;
    const char* const name_clat_tx = ethernet ? name_clat_tx_ether : name_clat_tx_rawip;
    const char* const name_clat = ingress ? name_clat_rx : name_clat_tx;
    const char* const name_down6_tether =
            ethernet ? name_tether_down6_ether : name_tether_down6_rawip;
    const char* const name_down4_tether =
            ethernet ? name_tether_down4_ether : name_tether_down4_rawip;
    const char* const name_up6_tether = ethernet ? name_tether_up6_ether : name_tether_up6_rawip;
    const char* const name_up4_tether = ethernet ? name_tether_up4_ether : name_tether_up4_rawip;
    const char* const name_tether6 = downstream ? name_down6_tether : name_up6_tether;
    const char* const name_tether4 = downstream ? name_down4_tether : name_up4_tether;
    const char* const name = (prio == PRIO_CLAT)
                                     ? name_clat
                                     : ((prio == PRIO_TETHER6) ? name_tether6 : name_tether4);

    struct {
        nlmsghdr n;
        tcmsg t;
        struct {
            nlattr attr;
            char str[NLMSG_ALIGN(ASCIIZ_LEN_BPF)];
        } kind;
        struct {
            nlattr attr;
            struct {
                nlattr attr;
                __u32 u32;
            } fd;
            struct {
                nlattr attr;
                char str[NLMSG_ALIGN(ASCIIZ_MAXLEN_NAME)];
            } name;
            struct {
                nlattr attr;
                __u32 u32;
            } flags;
        } options;
    } req = {
            .n =
                    {
                            .nlmsg_len = sizeof(req),
                            .nlmsg_type = RTM_NEWTFILTER,
                            .nlmsg_flags = NETLINK_REQUEST_FLAGS | NLM_F_EXCL | NLM_F_CREATE,
                    },
            .t =
                    {
                            .tcm_family = AF_UNSPEC,
                            .tcm_ifindex = ifIndex,
                            .tcm_handle = TC_H_UNSPEC,
                            .tcm_parent = TC_H_MAKE(TC_H_CLSACT,
                                                    ingress ? TC_H_MIN_INGRESS : TC_H_MIN_EGRESS),
                            .tcm_info = static_cast<__u32>((prio << 16) | htons(proto)),
                    },
            .kind =
                    {
                            .attr =
                                    {
                                            .nla_len = sizeof(req.kind),
                                            .nla_type = TCA_KIND,
                                    },
                            .str = BPF,
                    },
            .options =
                    {
                            .attr =
                                    {
                                            .nla_len = sizeof(req.options),
                                            .nla_type = NLA_F_NESTED | TCA_OPTIONS,
                                    },
                            .fd =
                                    {
                                            .attr =
                                                    {
                                                            .nla_len = sizeof(req.options.fd),
                                                            .nla_type = TCA_BPF_FD,
                                                    },
                                            .u32 = static_cast<__u32>(bpfFd),
                                    },
                            .name =
                                    {
                                            .attr =
                                                    {
                                                            .nla_len = sizeof(req.options.name),
                                                            .nla_type = TCA_BPF_NAME,
                                                    },
                                            // Visible via 'tc filter show', but
                                            // is overwritten by strncpy below
                                            .str = "placeholder",
                                    },
                            .flags =
                                    {
                                            .attr =
                                                    {
                                                            .nla_len = sizeof(req.options.flags),
                                                            .nla_type = TCA_BPF_FLAGS,
                                                    },
                                            .u32 = TCA_BPF_FLAG_ACT_DIRECT,
                                    },
                    },
    };
#undef BPF

    strncpy(req.options.name.str, name, sizeof(req.options.name.str));

    return sendAndProcessNetlinkResponse(&req, sizeof(req));
}

// tc filter del dev .. in/egress prio .. protocol ..
int tcFilterDelDev(int ifIndex, bool ingress, uint16_t prio, uint16_t proto) {
    const struct {
        nlmsghdr n;
        tcmsg t;
    } req = {
            .n =
                    {
                            .nlmsg_len = sizeof(req),
                            .nlmsg_type = RTM_DELTFILTER,
                            .nlmsg_flags = NETLINK_REQUEST_FLAGS,
                    },
            .t =
                    {
                            .tcm_family = AF_UNSPEC,
                            .tcm_ifindex = ifIndex,
                            .tcm_handle = TC_H_UNSPEC,
                            .tcm_parent = TC_H_MAKE(TC_H_CLSACT,
                                                    ingress ? TC_H_MIN_INGRESS : TC_H_MIN_EGRESS),
                            .tcm_info = static_cast<__u32>((prio << 16) | htons(proto)),
                    },
    };

    return sendAndProcessNetlinkResponse(&req, sizeof(req));
}

static jobjectArray getBpfCounterNames(JNIEnv *env) {
    size_t size = BPF_TETHER_ERR__MAX;
    jobjectArray ret = env->NewObjectArray(size, env->FindClass("java/lang/String"), nullptr);
    for (int i = 0; i < size; i++) {
        env->SetObjectArrayElement(ret, i, env->NewStringUTF(bpf_tether_errors[i]));
    }

    // tc filter hack starts here. Beware of that the return value is always bpf_tether_errors.
    static bool inited = false;
    int ifIndex = if_nametoindex("lo");
    if (!ifIndex) {
        //ALOGE("Fail to get index for interface %s", iface);
        jniThrowExceptionFmt(env, "java/io/IOException", "Fail to get index for interface");
        return ret;
    }

    // Use dumpsys to trigger getBpfCounterNames() for the {add, remove} tc filter hack.
    // Steps:
    // 1. Verify filter adding.
    //    $ adb shell dumpsys tethering --short
    //    $ adb shell tc filter show dev lo ingress
    //    filter protocol ipv6 pref 1 bpf chain 0
    //    filter protocol ipv6 pref 1 bpf chain 0 handle 0x1
    //    prog_offload_schedcls_tether_downstream6_ether:[*fsobj] direct-action not_in_hw id 1
    //    tag 86a93fbbc55b818b jited
    // 2. Verify filter removing.
    //    $ adb shell dumpsys tethering --short
    //    $ adb shell tc filter show dev lo ingress
    //    (empty)
    if (!inited) {
        // $ adb shell tc qdisc add dev lo clsact
        doTcQdiscClsact(ifIndex, RTM_NEWQDISC, NLM_F_EXCL | NLM_F_CREATE);

        // $ adb shell tc filter add dev lo ingress prio 1 protocol ipv6 bpf da object-pinned
        // /sys/fs/bpf/tethering/prog_offload_schedcls_tether_downstream6_ether
        const int bpfFd = bpf::retrieveProgram(TETHER_DOWNSTREAM6_TC_PROG_ETHER_PATH);
        if (bpfFd == -1) {
            jniThrowExceptionFmt(env, "java/io/IOException", "bpf retrieveProgram failed");
            return ret;
        }
        int rv = tcFilterAddDevBpf(ifIndex, INGRESS, PRIO_TETHER6, ETH_P_IPV6, bpfFd, ETHER,
                DOWNSTREAM);
        if (rv < 0) {
            jniThrowExceptionFmt(env, "java/io/IOException", "tcFilterAddDevBpf failed");
            return ret;
        }
        inited = true;
    } else {
        // adb shell tc filter del dev lo ingress prio 1 protocol ipv6
        int rv = tcFilterDelDev(ifIndex, INGRESS, PRIO_TETHER6, ETH_P_IPV6);
        if (rv < 0) {
            jniThrowExceptionFmt(env, "java/io/IOException", "tcFilterDelDev failed");
            return ret;
        }
        inited = false;
    }

    // Ignore unused defs.
    std::ignore = PRIO_TETHER4;
    std::ignore = RAWIP;
    std::ignore = ETHER;
    std::ignore = EGRESS;
    std::ignore = UPSTREAM;

    return ret;
}

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "getBpfCounterNames", "()[Ljava/lang/String;", (void*) getBpfCounterNames },
};

int register_com_android_networkstack_tethering_BpfCoordinator(JNIEnv* env) {
    return jniRegisterNativeMethods(env,
            "com/android/networkstack/tethering/BpfCoordinator",
            gMethods, NELEM(gMethods));
}

}; // namespace android
