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
#define LOG_TAG "jniRoutingCoordinator"


#include <errno.h>
#include <jni.h>
#include <linux/in6.h>
#include <linux/mroute6.h>
#include <log/log.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <net/if.h>
#include <netinet/icmp6.h>
#include <netinet/in.h>
#include <netjniutils/netjniutils.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <unistd.h>

namespace android {

static jint com_android_server_MulticastRoutingCoordinator_createMulticastSocket(
    JNIEnv* env, jclass clazz) {

    int                 one = 1;
    struct icmp6_filter filter;
    int                 sock;

    sock = socket(AF_INET6, SOCK_RAW | SOCK_CLOEXEC | SOCK_NONBLOCK, IPPROTO_ICMPV6);
    if (sock == -1) {
        jniThrowExceptionFmt(env, "java/io/IOException", "open raw socket failed (%s)",
                             strerror(errno));
        return -1;
    }

    // Enable Multicast Forwarding in Kernel
    if (0 != setsockopt(sock, IPPROTO_IPV6, MRT6_INIT, &one, sizeof(one))) {
        close(sock);
        jniThrowExceptionFmt(env, "java/io/IOException",
                             "failed to enable multicast forwarding (%s)",
                             strerror(errno));
        return -1;
    }

    // Filter all ICMPv6 messages
    ICMP6_FILTER_SETBLOCKALL(&filter);
    if (0 != setsockopt(sock, IPPROTO_ICMPV6, ICMP6_FILTER, (void *)&filter, sizeof(filter))) {
        close(sock);
        jniThrowExceptionFmt(env, "java/io/IOException", "failed to filter ICMPv6 messages (%s)",
                             strerror(errno));
        return -1;
    }

    return sock;
}

static void com_android_server_MulticastRoutingCoordinator_addMif(
    JNIEnv* env, jclass clazz, jobject fd, jint virtualIfIndex, jint ifIndex) {

    int sock = netjniutils::GetNativeFileDescriptor(env, fd);

    struct mif6ctl      mif6ctl;
    memset(&mif6ctl, 0, sizeof(mif6ctl));
    mif6ctl.mif6c_flags     = 0;
    mif6ctl.vifc_threshold  = 1;

    mif6ctl.mif6c_mifi = virtualIfIndex;
    mif6ctl.mif6c_pifi = ifIndex;

    if (0 != setsockopt(sock, IPPROTO_IPV6, MRT6_ADD_MIF, &mif6ctl, sizeof(mif6ctl))) {
        close(sock);
        jniThrowExceptionFmt(env, "java/io/IOException",
                             "failed to add network interface to MIF (%s)",
                             strerror(errno));
        return;
    }

    ALOGI("Added mifi %d, pifi %d to MIF for socket %d", virtualIfIndex, ifIndex, sock);
}

static void com_android_server_MulticastRoutingCoordinator_removeMif(
    JNIEnv* env, jclass clazz, jobject fd, jint virtualIfIndex) {

    int sock = netjniutils::GetNativeFileDescriptor(env, fd);

    mifi_t mifi = virtualIfIndex;

    if (0 != setsockopt(sock, IPPROTO_IPV6, MRT6_DEL_MIF, &mifi, sizeof(mifi))) {
        close(sock);
        jniThrowExceptionFmt(env, "java/io/IOException",
                             "failed to remove network interface from MIF (%s)",
                             strerror(errno));
        return;
    }

    ALOGI("Removed mifi %d from MIF for socket %d", virtualIfIndex, sock);
}

static void com_android_server_MulticastRoutingCoordinator_addMfc(
    JNIEnv* env, jclass clazz, jobject fd, jint iif, jintArray jOifs,
    jbyteArray srcBytes, jbyteArray dstBytes, jboolean isStatic) {

    ScopedIntArrayRO oifs(env, jOifs);
    ScopedByteArrayRO src(env, srcBytes);
    ScopedByteArrayRO dst(env, dstBytes);
    int sock = netjniutils::GetNativeFileDescriptor(env, fd);
    int opt = isStatic ? MRT6_ADD_MFC_PROXY : MRT6_ADD_MFC;

    struct mf6cctl mf6cctl;

    memset(&mf6cctl, 0, sizeof(mf6cctl));

    memcpy(mf6cctl.mf6cc_origin.sin6_addr.s6_addr, src.get(),
           sizeof(mf6cctl.mf6cc_origin.sin6_addr.s6_addr));
    memcpy(mf6cctl.mf6cc_mcastgrp.sin6_addr.s6_addr, dst.get(),
           sizeof(mf6cctl.mf6cc_mcastgrp.sin6_addr.s6_addr));
    mf6cctl.mf6cc_parent = iif;

    for (int i = 0; i < oifs.size(); i++) {
        IF_SET(oifs[i], &mf6cctl.mf6cc_ifset);
    }

    // For static MFC, the iif needs to be included in the oif set
    if (isStatic) {
        IF_SET(iif, &mf6cctl.mf6cc_ifset);
    }
    if (0 != setsockopt(sock, IPPROTO_IPV6, opt, &mf6cctl, sizeof(mf6cctl))) {
        close(sock);
        jniThrowExceptionFmt(env, "java/io/IOException", "failed to add MFC (%s)",
                             strerror(errno));
        return;
    }
}

static void com_android_server_MulticastRoutingCoordinator_delMfc(
        JNIEnv* env, jclass clazz, jobject fd, jint iif,
        jbyteArray srcBytes, jbyteArray dstBytes, jboolean isStatic) {

    ScopedByteArrayRO src(env, srcBytes);
    ScopedByteArrayRO dst(env, dstBytes);
    int sock = netjniutils::GetNativeFileDescriptor(env, fd);
    int opt = isStatic ? MRT6_DEL_MFC_PROXY : MRT6_DEL_MFC;

    struct mf6cctl mf6cctl;

    memset(&mf6cctl, 0, sizeof(mf6cctl));

    memcpy(mf6cctl.mf6cc_origin.sin6_addr.s6_addr, src.get(),
           sizeof(mf6cctl.mf6cc_origin.sin6_addr.s6_addr));
    memcpy(mf6cctl.mf6cc_mcastgrp.sin6_addr.s6_addr, dst.get(),
           sizeof(mf6cctl.mf6cc_mcastgrp.sin6_addr.s6_addr));
    mf6cctl.mf6cc_parent = iif;

    if (0 != setsockopt(sock, IPPROTO_IPV6, opt, &mf6cctl, sizeof(mf6cctl))) {
        close(sock);
        jniThrowExceptionFmt(env, "java/io/IOException", "failed to remove MFC (%s)",
                             strerror(errno));
        return;
    }
}

static void com_android_server_MulticastRoutingCoordinator_joinGroup(
        JNIEnv* env, jclass clazz, jobject fd, jint ifIndex, jbyteArray addrBytes) {
    int sock = netjniutils::GetNativeFileDescriptor(env, fd);
    ScopedByteArrayRO addr(env, addrBytes);

    struct ipv6_mreq ipv6mr;
    ipv6mr.ipv6mr_interface = ifIndex;
    memcpy(&ipv6mr.ipv6mr_multiaddr, addr.get(), sizeof(ipv6mr.ipv6mr_multiaddr));

    if (0 != setsockopt(sock, IPPROTO_IPV6, IPV6_JOIN_GROUP, (void *)&ipv6mr, sizeof(ipv6mr))) {
        close(sock);
        jniThrowExceptionFmt(env, "java/io/IOException", "failed to join group (%s)",
                             strerror(errno));
        return;
    }
}

static void com_android_server_MulticastRoutingCoordinator_leaveGroup(
        JNIEnv* env, jclass clazz, jobject fd, jint ifIndex, jbyteArray addrBytes) {
    int sock = netjniutils::GetNativeFileDescriptor(env, fd);
    ScopedByteArrayRO addr(env, addrBytes);

    struct ipv6_mreq ipv6mr;
    ipv6mr.ipv6mr_interface = ifIndex;
    memcpy(&ipv6mr.ipv6mr_multiaddr, addr.get(), sizeof(ipv6mr.ipv6mr_multiaddr));

    if (0 != setsockopt(sock, IPPROTO_IPV6, IPV6_LEAVE_GROUP, (void *)&ipv6mr, sizeof(ipv6mr))) {
        close(sock);
        jniThrowExceptionFmt(env, "java/io/IOException", "failed to leave group (%s)",
                             strerror(errno));
        return;
    }
}

static const JNINativeMethod gMethods[] = {
         /* name, signature, funcPtr */
         {"nativeCreateMulticastSocket",
         "()I",
         (void*)com_android_server_MulticastRoutingCoordinator_createMulticastSocket},
         {"nativeAddMif",
         "(Ljava/io/FileDescriptor;II)V",
         (void*)com_android_server_MulticastRoutingCoordinator_addMif},
         {"nativeRemoveMif",
         "(Ljava/io/FileDescriptor;I)V",
         (void*)com_android_server_MulticastRoutingCoordinator_removeMif},
         {"nativeAddMfc",
          "(Ljava/io/FileDescriptor;I[I[B[BZ)V",
          (void*)com_android_server_MulticastRoutingCoordinator_addMfc},
         {"nativeRemoveMfc",
          "(Ljava/io/FileDescriptor;I[B[BZ)V",
          (void*)com_android_server_MulticastRoutingCoordinator_delMfc},
         {"nativeJoinGroup",
         "(Ljava/io/FileDescriptor;I[B)V",
         (void*)com_android_server_MulticastRoutingCoordinator_joinGroup},
         {"nativeLeaveGroup",
         "(Ljava/io/FileDescriptor;I[B)V",
         (void*)com_android_server_MulticastRoutingCoordinator_leaveGroup},
};

int register_com_android_server_RoutingCoordinator(JNIEnv* env) {
    return jniRegisterNativeMethods(
        env,
        "android/net/connectivity/com/android/server/connectivity/RoutingCoordinatorService",
        gMethods,
        NELEM(gMethods)
    );
}
}