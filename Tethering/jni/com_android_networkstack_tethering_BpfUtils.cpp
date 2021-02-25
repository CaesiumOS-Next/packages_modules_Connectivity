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
#include <jni.h>
#include <linux/if_arp.h>
#include <linux/if_ether.h>
#include <linux/netlink.h>
#include <linux/pkt_cls.h>
#include <linux/pkt_sched.h>
#include <linux/rtnetlink.h>
#include <nativehelper/JNIHelp.h>
#include <net/if.h>
#include <stdio.h>
#include <sys/socket.h>

#define BPF_FD_JUST_USE_INT
#include "BpfSyscallWrappers.h"
#include "bpf_tethering.h"
#include "nativehelper/scoped_utf_chars.h"

#define CLS_BPF_NAME_LEN 256  // sync from net/sched/cls_bpf.c

namespace android {
// Sync from system/netd/server/NetlinkCommands.h
const uint16_t NETLINK_REQUEST_FLAGS = NLM_F_REQUEST | NLM_F_ACK;
const sockaddr_nl KERNEL_NLADDR = {AF_NETLINK, 0, 0, 0};

static int sendAndProcessNetlinkResponse(const void* req, int len) {
    int fd = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC, NETLINK_ROUTE);
    if (fd == -1) {
        const int err = errno;
        // ALOGE("socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC, NETLINK_ROUTE)");
        return -err;
    }

    static constexpr int on = 1;
    int rv = setsockopt(fd, SOL_NETLINK, NETLINK_CAP_ACK, &on, sizeof(on));
    // if (rv) ALOGE("setsockopt(fd, SOL_NETLINK, NETLINK_CAP_ACK, %d)", on);

    // this is needed to get sane strace netlink parsing, it allocates the pid
    rv = bind(fd, (const struct sockaddr*)&KERNEL_NLADDR, sizeof(KERNEL_NLADDR));
    if (rv) {
        const int err = errno;
        // ALOGE("bind(fd, {AF_NETLINK, 0, 0})");
        return -err;
    }

    // we do not want to receive messages from anyone besides the kernel
    rv = connect(fd, (const struct sockaddr*)&KERNEL_NLADDR, sizeof(KERNEL_NLADDR));
    if (rv) {
        const int err = errno;
        // ALOGE("connect(fd, {AF_NETLINK, 0, 0})");
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
        // ALOGE("recv() failed");
        return -err;
    }

    if (rv < (int)NLMSG_SPACE(sizeof(struct nlmsgerr))) {
        // ALOGE("recv() returned short packet: %d", rv);
        return -EMSGSIZE;
    }

    if (resp.h.nlmsg_len != (unsigned)rv) {
        // ALOGE("recv() returned invalid header length: %d != %d", resp.h.nlmsg_len, rv);
        return -EBADMSG;
    }

    if (resp.h.nlmsg_type != NLMSG_ERROR) {
        // ALOGE("recv() did not return NLMSG_ERROR message: %d", resp.h.nlmsg_type);
        return -EBADMSG;
    }

    close(fd);
    return resp.e.error;  // returns 0 on success
}

static int hardwareAddressType(const char* interface) {
    int ufd = socket(AF_INET6, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (ufd < 0) return -errno;

    struct ifreq ifr = {};
    // We use strncpy() instead of strlcpy() since kernel has to be able
    // to handle non-zero terminated junk passed in by userspace anyway,
    // and this way too long interface names (more than IFNAMSIZ-1 = 15
    // characters plus terminating NULL) will not get truncated to 15
    // characters and zero-terminated and thus potentially erroneously
    // match a truncated interface if one were to exist.
    strncpy(ifr.ifr_name, interface, sizeof(ifr.ifr_name));

    int rv;
    if (ioctl(ufd, SIOCGIFHWADDR, &ifr, sizeof(ifr))) {
        rv = -errno;
    } else {
        rv = ifr.ifr_hwaddr.sa_family;
    }

    close(ufd);
    return rv;
}

static jboolean com_android_networkstack_tethering_BpfUtils_isEthernet(JNIEnv* env, jobject clazz,
                                                                       jstring iface) {
    ScopedUtfChars interface(env, iface);

    int rv = hardwareAddressType(interface.c_str());
    if (rv < 0) {
        jniThrowExceptionFmt(env, "java/io/IOException",
                             "Get hardware address type of interface %s failed: %s",
                             interface.c_str(), strerror(-rv));
        return false;
    }

    switch (rv) {
        case ARPHRD_ETHER:
            return true;
        case ARPHRD_NONE:
        case ARPHRD_RAWIP:  // in Linux 4.14+ rmnet support was upstreamed and this is 519
        case 530:           // this is ARPHRD_RAWIP on some Android 4.9 kernels with rmnet
            return false;
        default:
            jniThrowExceptionFmt(env, "java/io/IOException",
                                 "Unknown hardware address type %s on interface %s", rv,
                                 interface.c_str());
            return false;
    }
}

// tc filter add dev .. in/egress prio 1 protocol ipv6/ip bpf object-pinned /sys/fs/bpf/...
// direct-action
static void com_android_networkstack_tethering_BpfUtils_tcFilterAddDevBpf(
        JNIEnv* env, jobject clazz, jint ifIndex, jboolean ingress, jshort prio, jshort proto,
        jstring bpfProgPath) {
    ScopedUtfChars pathname(env, bpfProgPath);

    const int bpfFd = bpf::retrieveProgram(pathname.c_str());
    if (bpfFd == -1) {
        jniThrowExceptionFmt(env, "java/io/IOException", "bpf retrieveProgram failed %s",
                             strerror(errno));
        return;
    }

    struct {
        nlmsghdr n;
        tcmsg t;
        struct {
            nlattr attr;
            // The maximum classifier name length is defined as IFNAMSIZ.
            // See tcf_proto_ops in include/net/sch_generic.h.
            char str[NLMSG_ALIGN(IFNAMSIZ)];
        } kind;
        struct {
            nlattr attr;
            struct {
                nlattr attr;
                __u32 u32;
            } fd;
            struct {
                nlattr attr;
                char str[NLMSG_ALIGN(CLS_BPF_NAME_LEN)];
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
                            .tcm_info = static_cast<__u32>((static_cast<uint16_t>(prio) << 16) |
                                                           htons(static_cast<uint16_t>(proto))),
                    },
            .kind =
                    {
                            .attr =
                                    {
                                            .nla_len = sizeof(req.kind),
                                            .nla_type = TCA_KIND,
                                    },
                            // Classifier name. See cls_bpf_ops in net/sched/cls_bpf.c.
                            .str = "bpf",
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

    char bpfName[CLS_BPF_NAME_LEN];
    snprintf(bpfName, sizeof(bpfName), "%s:[%s]", basename(pathname.c_str()), "*fsobj");
    strncpy(req.options.name.str, bpfName, sizeof(req.options.name.str));

    const int rv = sendAndProcessNetlinkResponse(&req, sizeof(req));
    close(bpfFd);

    if (rv) {
        jniThrowExceptionFmt(env, "java/io/IOException", "bpf tcFilterAddDevBpfImpl failed %s",
                             strerror(-rv));
    }
}

// tc filter del dev .. in/egress prio .. protocol ..
static void com_android_networkstack_tethering_BpfUtils_tcFilterDelDev(JNIEnv* env, jobject clazz,
                                                                       jint ifIndex,
                                                                       jboolean ingress,
                                                                       jshort prio, jshort proto) {
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
                            .tcm_info = static_cast<__u32>((static_cast<uint16_t>(prio) << 16) |
                                                           htons(static_cast<uint16_t>(proto))),
                    },
    };

    const int ret = sendAndProcessNetlinkResponse(&req, sizeof(req));
    if (ret) {
        jniThrowExceptionFmt(env, "java/io/IOException", "bpf tcFilterDelDev failed %s",
                             strerror(-ret));
    }
}

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
        /* name, signature, funcPtr */
        {"isEthernet", "(Ljava/lang/String;)Z",
         (void*)com_android_networkstack_tethering_BpfUtils_isEthernet},
        {"tcFilterAddDevBpf", "(IZSSLjava/lang/String;)V",
         (void*)com_android_networkstack_tethering_BpfUtils_tcFilterAddDevBpf},
        {"tcFilterDelDev", "(IZSS)V",
         (void*)com_android_networkstack_tethering_BpfUtils_tcFilterDelDev},
};

int register_com_android_networkstack_tethering_BpfUtils(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/networkstack/tethering/BpfUtils", gMethods,
                                    NELEM(gMethods));
}

};  // namespace android
