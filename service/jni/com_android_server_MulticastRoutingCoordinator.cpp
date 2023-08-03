/*
 * Copyright (C) 2023 The Android Open Source Project
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
#define LOG_TAG "jniMulticastRoutingCoordinator"

#include <log/log.h>
#include <errno.h>

#include <netinet/in.h>
#include <netinet/icmp6.h>
#include <sys/socket.h>
#include <unistd.h>

#include <linux/mroute6.h>
#include <linux/in6.h>
#include <net/if.h>
#include <sys/types.h>
#include <jni.h>
#include <netjniutils/netjniutils.h>

#include "nativehelper/JNIHelp.h"
#include "nativehelper/scoped_utf_chars.h"
#include <nativehelper/ScopedPrimitiveArray.h>

#include <linux/fib_rules.h>
#include <linux/rtnetlink.h>
#include <sys/uio.h>


namespace android {

#define VerifyOrDie(aExpValue, aValue, msg)                                                        \
    do                                                                                             \
    {                                                                                              \
        if (!(aExpValue == aValue))                                                                \
        {                                                                                          \
            ALOGI("Expected %d, got %d", aExpValue, aValue);                                       \
            jniThrowExceptionFmt(env, "java/io/IOException", "%s", msg);                           \
        }                                                                                          \
    } while (false)


static constexpr uint16_t U16_RTA_LENGTH(uint16_t x) {
    return RTA_LENGTH(x);
}

constexpr int32_t MRULE_PRIORITY = 10000;

rtattr FRATTR_PRIORITY  = { U16_RTA_LENGTH(sizeof(uint32_t)),           FRA_PRIORITY };
rtattr FRATTR_TABLE     = { U16_RTA_LENGTH(sizeof(uint32_t)),           FRA_TABLE };

#define ARRAY_SIZE(a) (sizeof(a) / sizeof(*(a)))

uint8_t PADDING_BUFFER[RTA_ALIGNTO] = {0, 0, 0, 0};

const sockaddr_nl KERNEL_NLADDR = {AF_NETLINK, 0, 0, 0};

int openNetlinkSocket(int protocol) {
    int sock = socket(AF_NETLINK, SOCK_DGRAM | SOCK_CLOEXEC, protocol);
    if (sock == -1) {
        return -errno;
    }
    if (connect(sock, reinterpret_cast<const sockaddr*>(&KERNEL_NLADDR),
                sizeof(KERNEL_NLADDR)) == -1) {
        return -errno;
    }
    return sock;
}

int recvNetlinkAck(int sock) {
    struct {
        nlmsghdr msg;
        nlmsgerr err;
    } response;

    int ret = recv(sock, &response, sizeof(response), 0);

    if (ret == -1) {
        ret = -errno;
        ALOGE("netlink recv failed (%s)", strerror(-ret));
        return ret;
    }

    if (ret != sizeof(response)) {
        ALOGE("bad netlink response message size (%d != %zu)", ret, sizeof(response));
        return -EBADMSG;
    }

    return response.err.error;  // Netlink errors are negative errno.
}

int sendNetlinkRequest(uint16_t action, uint16_t flags, iovec* iov, int iovlen) {
    int sock = openNetlinkSocket(NETLINK_ROUTE);
    if (sock < 0) {
        return sock;
    }

    nlmsghdr nlmsg = {
        .nlmsg_type = action,
        .nlmsg_flags = flags,
    };
    iov[0].iov_base = &nlmsg;
    iov[0].iov_len = sizeof(nlmsg);
    for (int i = 0; i < iovlen; ++i) {
        nlmsg.nlmsg_len += iov[i].iov_len;
    }

    ssize_t writevRet = writev(sock, iov, iovlen);
    // Don't let pointers to the stack escape.
    iov[0] = {nullptr, 0};
    int ret = 0;
    if (writevRet == -1) {
        ret = -errno;
        ALOGE("netlink socket connect/writev failed (%s)", strerror(-ret));
        close(sock);
        return ret;
    }

    ret = recvNetlinkAck(sock);

    close(sock);

    return ret;
}

static void com_android_server_MulticastRoutingCoordinator_createMrule(
    JNIEnv* env, jclass clazz, jstring ifName, jint tableId) {
        
    ScopedUtfChars ifn(env, ifName);
    char* oifName = (char*) ifn.c_str();
    size_t oifLength = ifn.size();
    

    int32_t priority = MRULE_PRIORITY;

    rtattr fraOifName = { U16_RTA_LENGTH(oifLength), FRA_OIFNAME };

    fib_rule_hdr rule = {
        .action = FR_ACT_TO_TBL,
        .family = RTNL_FAMILY_IP6MR,
        .table = RT_TABLE_UNSPEC,
    };

    iovec iov[] = {
        { nullptr,              0 },
        { &rule,             sizeof(rule) },
        { &FRATTR_PRIORITY,  sizeof(FRATTR_PRIORITY) },
        { &priority,         sizeof(priority) },
        { &FRATTR_TABLE,     sizeof(FRATTR_TABLE) },
        { &tableId,          sizeof(tableId) },
//        { &FRATTR_FWMARK,    mask ? sizeof(FRATTR_FWMARK) : 0 },
//        { &fwmark,           mask ? sizeof(fwmark) : 0 },
//        { &FRATTR_FWMASK,    mask ? sizeof(FRATTR_FWMASK) : 0 },
//        { &mask,             mask ? sizeof(mask) : 0 },
        { &fraOifName,       sizeof(fraOifName) },
        { oifName,           oifLength },
    };

    uint16_t flags = NLM_F_REQUEST | NLM_F_ACK | NLM_F_CREATE;
    sendNetlinkRequest(RTM_NEWRULE, flags, iov, ARRAY_SIZE(iov));

}

static jint com_android_server_MulticastRoutingCoordinator_configMulticastSocket(
    JNIEnv* env, jclass clazz, jint tableId, jint ifId, jstring ifName) {

    ALOGI("Creating multicast socket for if %d ", ifId);

    ALOGI("size of mrt6msg is %lu", sizeof(struct mrt6msg));

    int                 one = 1;
    struct icmp6_filter filter;
    struct mif6ctl      mif6ctl;

    int                 mSock;

    ScopedUtfChars ifn(env, ifName);
    
    const char*         ifN = ifn.c_str();

    mSock = socket(AF_INET6, SOCK_RAW | SOCK_CLOEXEC, IPPROTO_ICMPV6);
    ALOGI("Created socket %d", mSock);

    // specify a multicast table id
    VerifyOrDie(0, setsockopt(mSock, IPPROTO_IPV6, MRT6_TABLE, &tableId, sizeof(tableId)), "failed to specify table id");

    // Enable Multicast Forwarding in Kernel
    VerifyOrDie(0, setsockopt(mSock, IPPROTO_IPV6, MRT6_INIT, &one, sizeof(one)), "failed to enable multicast forwarding");

    // Filter all ICMPv6 messages
    ICMP6_FILTER_SETBLOCKALL(&filter);
    VerifyOrDie(0, setsockopt(mSock, IPPROTO_ICMPV6, ICMP6_FILTER, (void *)&filter, sizeof(filter)),
                "failed to filter ICMPv6 messages");

    memset(&mif6ctl, 0, sizeof(mif6ctl));
    mif6ctl.mif6c_flags     = 0;
    mif6ctl.vifc_threshold  = 1;
    mif6ctl.vifc_rate_limit = 0;

    // Add Thread network interface to MIF
    mif6ctl.mif6c_mifi = ifId;
    mif6ctl.mif6c_pifi = if_nametoindex(ifN);
    ALOGI("Adding thread mifi %d, pifi %d to MIF", ifId, if_nametoindex(ifN));

    VerifyOrDie(0, setsockopt(mSock, IPPROTO_IPV6, MRT6_ADD_MIF, &mif6ctl, sizeof(mif6ctl)),
                "failed to add network interface to MIF");

    ALOGI("Created socket %d for multicast forwarding", mSock);
    return mSock;
}

static void com_android_server_MulticastRoutingCoordinator_addMif(
    JNIEnv* env, jclass clazz, jobject aFd, jint mifId, jstring ifName) {

    int sock = netjniutils::GetNativeFileDescriptor(env, aFd);
    ScopedUtfChars ifn(env, ifName);
    const char*    ifN = ifn.c_str();

    struct mif6ctl      mif6ctl;
    memset(&mif6ctl, 0, sizeof(mif6ctl));
    mif6ctl.mif6c_flags     = 0;
    mif6ctl.vifc_threshold  = 1;
    mif6ctl.vifc_rate_limit = 0;

    // Add Thread network interface to MIF
    mif6ctl.mif6c_mifi = mifId;
    mif6ctl.mif6c_pifi = if_nametoindex(ifN);
    ALOGI("Adding mifi %d, pifi %d to MIF for socket %d", mifId, if_nametoindex(ifN), sock);

    VerifyOrDie(0, setsockopt(sock, IPPROTO_IPV6, MRT6_ADD_MIF, &mif6ctl, sizeof(mif6ctl)),
                "failed to add thread network interface to MIF");

}

static void com_android_server_MulticastRoutingCoordinator_handleMulticastForwardingPkt(
    JNIEnv* env, jclass clazz, jbyteArray bytes, jint aIif, jint aOif, jobject aFd) {
        ScopedByteArrayRO buf(env, bytes);

        const struct mrt6msg *mrt6msg;

        struct mf6cctl mf6cctl;

        in6_addr src, dst;

        int sock = netjniutils::GetNativeFileDescriptor(env, aFd);

        mrt6msg = reinterpret_cast<const struct mrt6msg *>(buf.get());

        src = mrt6msg->im6_src;
        dst= mrt6msg->im6_dst;

        memset(&mf6cctl, 0, sizeof(mf6cctl));

        memcpy(mf6cctl.mf6cc_origin.sin6_addr.s6_addr, src.s6_addr, sizeof(mf6cctl.mf6cc_origin.sin6_addr.s6_addr));
        memcpy(mf6cctl.mf6cc_mcastgrp.sin6_addr.s6_addr, dst.s6_addr,
           sizeof(mf6cctl.mf6cc_mcastgrp.sin6_addr.s6_addr));
        mf6cctl.mf6cc_parent = aIif;

        IF_SET(aOif, &mf6cctl.mf6cc_ifset);

        setsockopt(sock, IPPROTO_IPV6, MRT6_ADD_MFC, &mf6cctl, sizeof(mf6cctl));
        ALOGI("Added MFC for %d to %d", aIif, aOif);
}

static void com_android_server_MulticastRoutingCoordinator_addMfc(
    JNIEnv* env, jclass clazz, jobject aFd, jint aIif, jbyteArray aOifs,
    jbyteArray srcBytes, jbyteArray dstBytes) {

        ScopedByteArrayRO oifs(env, aOifs);
        ScopedByteArrayRO src(env, srcBytes);
        ScopedByteArrayRO dst(env, dstBytes);
        int sock = netjniutils::GetNativeFileDescriptor(env, aFd);

        struct mf6cctl mf6cctl;

        memset(&mf6cctl, 0, sizeof(mf6cctl));

        memcpy(mf6cctl.mf6cc_origin.sin6_addr.s6_addr, src.get(), sizeof(mf6cctl.mf6cc_origin.sin6_addr.s6_addr));
        memcpy(mf6cctl.mf6cc_mcastgrp.sin6_addr.s6_addr, dst.get(),
           sizeof(mf6cctl.mf6cc_mcastgrp.sin6_addr.s6_addr));
        mf6cctl.mf6cc_parent = aIif;

        for (int i = 0; i < oifs.size(); i++) {
            IF_SET(oifs[i], &mf6cctl.mf6cc_ifset);
            ALOGI("Adding MFC for iif %d,  oif %d", aIif, oifs[i]);
        }

        int opt = MRT6_ADD_MFC;
        if (src.get()[0] == 0) {
            opt = MRT6_ADD_MFC_PROXY;
        }
        setsockopt(sock, IPPROTO_IPV6, opt, &mf6cctl, sizeof(mf6cctl));
        ALOGI("Added MFC for iif %d", aIif);

/*            int ifindex;
            ifindex = if_nametoindex("wlan0");
            setsockopt(sock, IPPROTO_IPV6, IPV6_MULTICAST_IF, &ifindex, sizeof(ifindex));
            int loopback = 0;
            setsockopt(sock, IPPROTO_IPV6, IPV6_MULTICAST_LOOP, &loopback, sizeof(loopback));
            ALOGI("IPV6_MULTICAST_LOOP set to 0");*/

}

static void com_android_server_MulticastRoutingCoordinator_joinGroup(
        JNIEnv* env, jclass clazz, jobject aFd, jstring ifName, jbyteArray addrBytes) {
    int sock = netjniutils::GetNativeFileDescriptor(env, aFd);
    ScopedByteArrayRO addr(env, addrBytes);
    ScopedUtfChars ifn(env, ifName);
    const char*    ifN = ifn.c_str();

    struct ipv6_mreq ipv6mr;
    ipv6mr.ipv6mr_interface = if_nametoindex(ifN);
    memcpy(&ipv6mr.ipv6mr_multiaddr, addr.get(), sizeof(ipv6mr.ipv6mr_multiaddr));
    setsockopt(sock, IPPROTO_IPV6, IPV6_JOIN_GROUP, (void *)&ipv6mr, sizeof(ipv6mr));
    ALOGI("IPV6_JOIN_GROUP on wlan0, with addr ");

}

static const JNINativeMethod gMethods[] = {
         /* name, signature, funcPtr */
         {"nativeConfigMulticastSocket",
         "(IILjava/lang/String;)I",
         (void*)com_android_server_MulticastRoutingCoordinator_configMulticastSocket},
         {"nativeHandleMulticastForwardingPkt",
         "([BIILjava/io/FileDescriptor;)V",
         (void*)com_android_server_MulticastRoutingCoordinator_handleMulticastForwardingPkt},
         {"nativeCreateMrule",
         "(Ljava/lang/String;I)V",
         (void*)com_android_server_MulticastRoutingCoordinator_createMrule},
         {"nativeAddMif",
         "(Ljava/io/FileDescriptor;ILjava/lang/String;)V",
         (void*)com_android_server_MulticastRoutingCoordinator_addMif},
         {"nativeAddMfc",
          "(Ljava/io/FileDescriptor;I[B[B[B)V",
          (void*)com_android_server_MulticastRoutingCoordinator_addMfc},
         {"nativeJoinGroup",
         "(Ljava/io/FileDescriptor;Ljava/lang/String;[B)V",
         (void*)com_android_server_MulticastRoutingCoordinator_joinGroup},
};

int register_com_android_server_MulticastRoutingCoordinator(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "android/net/connectivity/com/android/server/MulticastRoutingCoordinator",
                                    gMethods, NELEM(gMethods));
}
}