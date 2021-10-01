/*
 * Copyright (C) 2022 The Android Open Source Project
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

#include <gtest/gtest.h>
#include <netinet/in.h>

#include "android/net/ConnectivityNative.h"
#include "binder/IServiceManager.h"


namespace binder = android::binder;

using android::IBinder;
using android::IServiceManager;
using android::sp;
using android::String16;
using android::base::unique_fd;
using android::net::IConnectivityNative;

class ConnectivityNativeBinderTest : public ::testing::Test {
  public:
    ConnectivityNativeBinderTest() {
        sp<IServiceManager> sm = android::defaultServiceManager();
        sp<IBinder> binder = sm->getService(String16("connectivity_native"));
        if (binder != nullptr) {
            mService = android::interface_cast<IConnectivityNative>(binder);
        }
    }

    void SetUp() override {
        ASSERT_NE(nullptr, mService.get());
        // TODO: If unblockAllPorts() is tested, will need to get current list of blocked ports.
    }

    void TearDown() override {
        // TODO: See setup for unblockAllPorts().
    }

  protected:
    // Try 5 times to find unused port.
    int RETRY = 5;

    sp<IConnectivityNative> mService;

    /*
    * Open the socket and update the port.
    */
    int openSocket(in_port_t* port, sa_family_t family, const int type, bool block) {
        int ret = 0;
        int enable = 1;
        const int sock = socket(family, type, 0);
        setsockopt(sock, SOL_SOCKET, SO_REUSEPORT, &enable, sizeof(enable));

        if (family == AF_INET) {
            struct sockaddr_in addr4 = { .sin_family = family, .sin_port = *port };
            ret = bind(sock, (struct sockaddr*) &addr4, sizeof(addr4));
        } else {
            struct sockaddr_in6  addr6 = { .sin6_family = family, .sin6_port = *port  };
            ret = bind(sock, (struct sockaddr*) &addr6, sizeof(addr6));
        }

        if (block) {
            EXPECT_NE(0, ret);
            // If port is blocked, return here since the port is not needed
            // for subsequent sockets.
            return 0;
        } else if(ret != 0) {
            // When bind fails and block == false, port may already be in use
            // return 0, and let test case retry with new port.
            return 0;
        }

        if (family == AF_INET) {
            struct sockaddr_in sin;
            socklen_t len = sizeof(sin);
            EXPECT_NE(-1, getsockname(sock, (struct sockaddr *)&sin, &len));
            if (*port != 0) EXPECT_EQ(*port, sin.sin_port);
            *port = sin.sin_port;
        } else {
            struct sockaddr_in6 sin;
            socklen_t len = sizeof(sin);
            EXPECT_NE(-1, getsockname(sock, (struct sockaddr *)&sin, &len));
            if (*port != 0) EXPECT_EQ(*port, sin.sin6_port);
            *port = sin.sin6_port;
        }
        return sock;
    }
};

TEST_F(ConnectivityNativeBinderTest, PortUnblockedV4Udp) {
    in_port_t port = 0;
    int sock, sock2;
    for (int i = 0; i < RETRY; i++) {
        sock = openSocket(&port, AF_INET, SOCK_DGRAM, false);
        sock2 = openSocket(&port, AF_INET, SOCK_DGRAM, false);
        if (port != 0)
            break;
    }
    ASSERT_NE(0, port);

    // Sleep here to keep test case same as when blockPorts is called.
    sleep(1);

    int sock3 = openSocket(&port, AF_INET, SOCK_DGRAM, false);

    close(sock);
    close(sock2);
    close(sock3);
}

TEST_F(ConnectivityNativeBinderTest, PortUnblockedV4Tcp) {
    in_port_t port = 0;
    int sock, sock2;
    for (int i = 0; i < RETRY; i++) {
        sock = openSocket(&port, AF_INET, SOCK_STREAM, false);
        sock2 = openSocket(&port, AF_INET, SOCK_STREAM, false);
        if (port != 0)
            break;
    }
    ASSERT_NE(0, port);

    // Sleep here to keep test case same as when blockPorts is called.
    sleep(1);

    int sock3 = openSocket(&port, AF_INET, SOCK_STREAM, false);

    close(sock);
    close(sock2);
    close(sock3);
}

TEST_F(ConnectivityNativeBinderTest, PortUnblockedV6Udp) {
    in_port_t port = 0;
    int sock, sock2;
    for (int i = 0; i < RETRY; i++) {
        sock = openSocket(&port, AF_INET6, SOCK_DGRAM, false);
        sock2 = openSocket(&port, AF_INET6, SOCK_DGRAM, false);
        if (port != 0)
            break;
    }
    ASSERT_NE(0, port);

    // Sleep here to keep test case same as when blockPorts is called.
    sleep(1);

    int sock3 = openSocket(&port, AF_INET6, SOCK_DGRAM, false);

    close(sock);
    close(sock2);
    close(sock3);
}

TEST_F(ConnectivityNativeBinderTest, PortUnblockedV6Tcp) {
    in_port_t port = 0;
    int sock, sock2;
    for (int i = 0; i < RETRY; i++) {
        sock = openSocket(&port, AF_INET6, SOCK_STREAM, false);
        sock2 = openSocket(&port, AF_INET6, SOCK_STREAM, false);
        if (port != 0)
            break;
    }
    ASSERT_NE(0, port);

    // Sleep here to keep test case same as when blockPorts is called.
    sleep(1);

    int sock3 = openSocket(&port, AF_INET6, SOCK_STREAM, false);

    close(sock);
    close(sock2);
    close(sock3);
}

TEST_F(ConnectivityNativeBinderTest, BlockPortV4Udp) {
    in_port_t port = 0;
    int sock, sock2;
    for (int i = 0; i < RETRY; i++) {
        sock = openSocket(&port, AF_INET, SOCK_DGRAM, false);
        sock2 = openSocket(&port, AF_INET, SOCK_DGRAM, false);
        if (port != 0)
            break;
    }
    ASSERT_NE(0, port);

    int blockedPort = ntohs(sock2);
    mService->blockPort(blockedPort);

    // Sleep is necessary to allow AIDL -> BpfMap update, before creating new socket.
    sleep(1);

    int sock3 = openSocket(&port, AF_INET, SOCK_DGRAM, true);

    close(sock);
    close(sock2);
    close(sock3);
    mService->unblockPort(blockedPort);
}

TEST_F(ConnectivityNativeBinderTest, BlockPortV4Tcp) {
    in_port_t port = 0;
    int sock, sock2;
    for (int i = 0; i < RETRY; i++) {
        sock = openSocket(&port, AF_INET, SOCK_STREAM, false);
        sock2 = openSocket(&port, AF_INET, SOCK_STREAM, false);
        if (port != 0)
            break;
    }
    ASSERT_NE(0, port);

    int blockedPort = ntohs(sock2);
    mService->blockPort(blockedPort);

    // Sleep is necessary to allow AIDL -> BpfMap update, before creating new socket.
    sleep(1);

    int sock3 = openSocket(&port, AF_INET, SOCK_STREAM, true);

    close(sock);
    close(sock2);
    close(sock3);
    mService->unblockPort(blockedPort);
}

TEST_F(ConnectivityNativeBinderTest, BlockPortV6Udp) {
    in_port_t port = 0;
    int sock, sock2;
    for (int i = 0; i < RETRY; i++) {
        sock = openSocket(&port, AF_INET6, SOCK_DGRAM, false);
        sock2 = openSocket(&port, AF_INET6, SOCK_DGRAM, false);
        if (port != 0)
            break;
    }
    ASSERT_NE(0, port);

    int blockedPort = ntohs(sock2);
    mService->blockPort(blockedPort);

    // Sleep is necessary to allow AIDL -> BpfMap update, before creating new socket.
    sleep(1);

    int sock3 = openSocket(&port, AF_INET6, SOCK_DGRAM, true);

    close(sock);
    close(sock2);
    close(sock3);
    mService->unblockPort(blockedPort);
}

TEST_F(ConnectivityNativeBinderTest, BlockPortV6Tcp) {
    in_port_t port = 0;
    int sock, sock2;
    for (int i = 0; i < RETRY; i++) {
        sock = openSocket(&port, AF_INET6, SOCK_STREAM, false);
        sock2 = openSocket(&port, AF_INET6, SOCK_STREAM, false);
        if (port != 0)
            break;
    }
    ASSERT_NE(0, port);

    int blockedPort = ntohs(sock2);
    mService->blockPort(blockedPort);

    // Sleep is necessary to allow AIDL -> BpfMap update, before creating new socket.
    sleep(1);

    int sock3 = openSocket(&port, AF_INET6, SOCK_STREAM, true);

    close(sock);
    close(sock2);
    close(sock3);
    mService->unblockPort(blockedPort);
}
