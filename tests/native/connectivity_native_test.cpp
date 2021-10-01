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

#include "android/net/connectivity/aidl/ConnectivityNative.h"
#include "binder/IServiceManager.h"


namespace binder = android::binder;

using android::IBinder;
using android::IServiceManager;
using android::sp;
using android::String16;
using android::base::unique_fd;
using android::net::connectivity::aidl::IConnectivityNative;

class ConnectivityNativeBinderTest : public ::testing::Test {
  public:
    bool portsBlockedOnSystem = false;

    ConnectivityNativeBinderTest() {
        sp<IServiceManager> sm = android::defaultServiceManager();
        sp<IBinder> binder = sm->getService(String16("connectivity_native"));
        if (binder != nullptr) {
            mService = android::interface_cast<IConnectivityNative>(binder);
        }
    }

    void SetUp() override {
        ASSERT_NE(nullptr, mService.get());

        // If there are already ports being blocked on device unblockAllPortsForBind() tests
        // must be skipped.
        std::vector<int32_t> actualBlockedPorts;
        mService->getPortsBlockedForBind(&actualBlockedPorts);
        if (!actualBlockedPorts.empty()) portsBlockedOnSystem = true;
    }

  protected:
    // Try 5 times to find unused port.
    int RETRY = 5;

    sp<IConnectivityNative> mService;

    void testSocket(sa_family_t family, const int type, bool blockPort) {
        in_port_t port = 0;
        int sock, sock2;
        // Open two sockets with SO_REUSEPORT and expect they can both bind to port.
        for (int i = 0; i < RETRY; i++) {
            sock = openSocket(&port, family, type, false);
            sock2 = openSocket(&port, family, type, false);
            if (port != 0)
                break;
        }
        ASSERT_NE(0, port);

        int blockedPort = 0;
        if (blockPort) {
            blockedPort = ntohs(sock2);
            mService->blockBindOnPort(blockedPort);
        }

        int sock3 = openSocket(&port, family, type, blockPort);

        close(sock);
        close(sock2);
        close(sock3);
        if (blockPort) mService->unblockBindOnPort(blockedPort);
    }

    /*
    * Open the socket and update the port.
    */
    int openSocket(in_port_t* port, sa_family_t family, const int type, bool expectBindFail) {
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

        if (expectBindFail) {
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
    testSocket(AF_INET, SOCK_DGRAM, false);
}

TEST_F(ConnectivityNativeBinderTest, PortUnblockedV4Tcp) {
    testSocket(AF_INET, SOCK_STREAM, false);
}

TEST_F(ConnectivityNativeBinderTest, PortUnblockedV6Udp) {
    testSocket(AF_INET6, SOCK_DGRAM, false);
}

TEST_F(ConnectivityNativeBinderTest, PortUnblockedV6Tcp) {
    testSocket(AF_INET6, SOCK_STREAM, false);
}

TEST_F(ConnectivityNativeBinderTest, BlockPort4Udp) {
    testSocket(AF_INET, SOCK_DGRAM, true);
}

TEST_F(ConnectivityNativeBinderTest, BlockPort4Tcp) {
    testSocket(AF_INET, SOCK_STREAM, true);
}

TEST_F(ConnectivityNativeBinderTest, BlockPort6Udp) {
    testSocket(AF_INET6, SOCK_DGRAM, true);
}

TEST_F(ConnectivityNativeBinderTest, BlockPort6Tcp) {
    testSocket(AF_INET6, SOCK_STREAM, true);
}

TEST_F(ConnectivityNativeBinderTest, GetBlockedPorts) {
    int blockedPorts[] = {1,100,1220,5555,1333,2700,5600};
    for (int i : blockedPorts)
        mService->blockBindOnPort(i);
    std::vector<int32_t> actualBlockedPorts;
    mService->getPortsBlockedForBind(&actualBlockedPorts);
    EXPECT_FALSE(actualBlockedPorts.empty());

    for (int i = 0; i < std::size(blockedPorts); i++)
        EXPECT_TRUE(std::find(actualBlockedPorts.begin(), actualBlockedPorts.end(),
                blockedPorts[i]) != actualBlockedPorts.end());
}

TEST_F(ConnectivityNativeBinderTest, UnblockAllPorts) {
    if (portsBlockedOnSystem) return;

    int blockedPorts[] = {1,100,1220,5555,1333,2700,5600};
    for (int i : blockedPorts)
        mService->blockBindOnPort(i);

    std::vector<int32_t> actualBlockedPorts;
    mService->getPortsBlockedForBind(&actualBlockedPorts);
    EXPECT_FALSE(actualBlockedPorts.empty());

    mService->unblockAllPortsForBind();
    mService->getPortsBlockedForBind(&actualBlockedPorts);
    EXPECT_TRUE(actualBlockedPorts.empty());
}
