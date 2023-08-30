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

#define LOG_TAG "jniThreadInfra"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <ifaddrs.h>
#include <inttypes.h>
#include <linux/if_arp.h>
#include <linux/ioctl.h>
#include <log/log.h>
#include <net/if.h>
#include <netdb.h>
#include <netinet/icmp6.h>
#include <netinet/in.h>
#include <private/android_filesystem_config.h>
#include <signal.h>
#include <spawn.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include <string>

#include "jni.h"
#include "nativehelper/JNIHelp.h"
#include "nativehelper/scoped_utf_chars.h"

namespace android {
static jint
com_android_server_thread_InfraInterfaceController_createSocket(
    JNIEnv* env, jobject clazz, jstring interfaceName) {
  ScopedUtfChars ifName(env, interfaceName);

  int rval;
  struct icmp6_filter filter;
  constexpr int kEnable = 1;
  constexpr int kIpv6ChecksumOffset = 2;
  constexpr int kHopLimit = 255;

  // Initializes the ICMPv6 socket.
  int sock = socket(AF_INET6, SOCK_RAW, IPPROTO_ICMPV6);
  if (sock == -1) {
    jniThrowExceptionFmt(env, "java/io/IOException",
                         "failed to create the socket (%s)", strerror(errno));
    return -1;
  }

  // Only accept Router Advertisements, Router Solicitations and Neighbor
  // Advertisements.
  ICMP6_FILTER_SETBLOCKALL(&filter);
  ICMP6_FILTER_SETPASS(ND_ROUTER_SOLICIT, &filter);
  ICMP6_FILTER_SETPASS(ND_ROUTER_ADVERT, &filter);
  ICMP6_FILTER_SETPASS(ND_NEIGHBOR_ADVERT, &filter);

  rval =
      setsockopt(sock, IPPROTO_ICMPV6, ICMP6_FILTER, &filter, sizeof(filter));

  if (rval != 0) {
    jniThrowExceptionFmt(env, "java/io/IOException",
                         "failed to create the socket (%d)", rval);
    return -1;
  }

  // We want a source address and interface index.
  rval = setsockopt(sock, IPPROTO_IPV6, IPV6_RECVPKTINFO, &kEnable,
                    sizeof(kEnable));

  if (rval != 0) {
    jniThrowExceptionFmt(env, "java/io/IOException",
                         "failed to create the socket (%d)", rval);
    return -1;
  }

  rval = setsockopt(sock, IPPROTO_RAW, IPV6_CHECKSUM, &kIpv6ChecksumOffset,
                    sizeof(kIpv6ChecksumOffset));

  if (rval != 0) {
    jniThrowExceptionFmt(env, "java/io/IOException",
                         "failed to create the socket (%d)", rval);
    return -1;
  }

  // We need to be able to reject RAs arriving from off-link.
  rval = setsockopt(sock, IPPROTO_IPV6, IPV6_RECVHOPLIMIT, &kEnable,
                    sizeof(kEnable));
  if (rval != 0) {
    jniThrowExceptionFmt(env, "java/io/IOException",
                         "failed to create the socket (%d)", rval);
    return -1;
  }

  rval = setsockopt(sock, IPPROTO_IPV6, IPV6_UNICAST_HOPS, &kHopLimit,
                    sizeof(kHopLimit));
  if (rval != 0) {
    jniThrowExceptionFmt(env, "java/io/IOException",
                         "failed to create the socket (%d)", rval);
    return -1;
  }

  rval = setsockopt(sock, IPPROTO_IPV6, IPV6_MULTICAST_HOPS, &kHopLimit,
                    sizeof(kHopLimit));
  if (rval != 0) {
    jniThrowExceptionFmt(env, "java/io/IOException",
                         "failed to create the socket (%d)", rval);
    return -1;
  }

  rval = setsockopt(sock, SOL_SOCKET, SO_BINDTODEVICE, ifName.c_str(),
                    strlen(ifName.c_str()));
  if (rval != 0) {
    jniThrowExceptionFmt(env, "java/io/IOException",
                         "failed to create the socket (%d)", rval);
    return -1;
  }

  return sock;
}

/*
 * JNI registration.
 */

static const JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    {"nativeCreateSocket", "(Ljava/lang/String;)I",
     (void*)
         com_android_server_thread_InfraInterfaceController_createSocket},
};

int register_com_android_server_thread_InfraInterfaceController(
    JNIEnv* env) {
  return jniRegisterNativeMethods(
      env, "com/android/server/thread/InfraInterfaceController",
      gMethods, NELEM(gMethods));
}

};  // namespace android
