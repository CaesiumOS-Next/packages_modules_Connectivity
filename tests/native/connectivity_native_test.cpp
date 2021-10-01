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

#include <android-modules-utils/sdk_level.h>
#include <cutils/misc.h>  // FIRST_APPLICATION_UID
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
    std::vector<int32_t> actualBlockedPorts;

    ConnectivityNativeBinderTest() {
        sp<IServiceManager> sm = android::defaultServiceManager();
        sp<IBinder> binder = sm->getService(String16("connectivity_native"));
        if (binder != nullptr) {
            mService = android::interface_cast<IConnectivityNative>(binder);
        }
    }

    void SetUp() override {
        // Skip test case if not on T.
        if (!android::modules::sdklevel::IsAtLeastT()) GTEST_SKIP();

        ASSERT_NE(nullptr, mService.get());

        // If there are already ports being blocked on device unblockAllPortsForBind() store
        // the currently blocked ports and add them back at the end of the test. Do this for
        // every test case so additional test cases do not forget to add ports back.
        mService->getPortsBlockedForBind(&actualBlockedPorts);
        if (!actualBlockedPorts.empty()) portsBlockedOnSystem = true;
    }

    void TearDown() override {
        if (portsBlockedOnSystem) for (int i : actualBlockedPorts) mService->blockPortForBind(i);
    }

  protected:
    sp<IConnectivityNative> mService;

    void testSocket(sa_family_t family, const int type, bool blockPort) {
        in_port_t port = 4444, port2;
        int sock, sock2;
        // Open two sockets with SO_REUSEPORT and expect they can both bind to port.
        sock = openSocket(&port, family, type, false);
        EXPECT_NE(-1, sock);
        EXPECT_NE(0, port);
        close(sock);
        port2 = port;
        sock2 = openSocket(&port2, family, type, false);
        EXPECT_NE(-1, sock2);
        EXPECT_NE(0, port2);
        close(sock2);

        int blockedPort = 0;
        if (blockPort) {
            blockedPort = ntohs(port2);
            mService->blockPortForBind(blockedPort);
        }

        int sock3 = openSocket(&port2, family, type, blockPort);
        if (!blockPort) EXPECT_NE(-1, sock3); else EXPECT_EQ(-1, sock3);
        close(sock3);
        if (blockPort) mService->unblockPortForBind(blockedPort);
    }

    /*
    * Open the socket and update the port.
    */
    int openSocket(in_port_t* port, sa_family_t family, const int type, bool expectBindFail) {
        int ret = 0;
        int enable = 1;
        const int sock = socket(family, type, IPPROTO_UDP);
        ret = setsockopt(sock, SOL_SOCKET, SO_REUSEPORT, &enable, sizeof(enable));
        EXPECT_EQ(0, ret);

        if (family == AF_INET) {
            struct sockaddr_in addr4 = { .sin_family = family, .sin_port = htons(5656),
                    .sin_addr.s_addr = htonl(INADDR_ANY) };
            ret = bind(sock, (struct sockaddr*) &addr4, sizeof(addr4));
        } else {
            struct sockaddr_in6 addr6 = { .sin6_family = family, .sin6_port = htons(*port),
                    .sin6_addr = in6addr_any };
            ret = bind(sock, (struct sockaddr*) &addr6, sizeof(addr6));
        }

        if (expectBindFail) {
            EXPECT_NE(0, ret);
            // If port is blocked, return here since the port is not needed
            // for subsequent sockets.
            return -1;
        }
        EXPECT_EQ(0, ret) << "errno: " << errno;

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

TEST_F(ConnectivityNativeBinderTest, BlockPortTwice) {
    mService->blockPortForBind(5555);
    // Expect that no error is thrown.
    mService->blockPortForBind(5555);
    // TODO: how to check that no exception here?
    mService->unblockPortForBind(5555);
}

TEST_F(ConnectivityNativeBinderTest, GetBlockedPorts) {
    std::vector<int> blockedPorts{1, 100, 1220, 1333, 2700, 5555, 5600};
    for (int i : blockedPorts) mService->blockPortForBind(i);
    std::vector<int32_t> actualBlockedPorts;
    mService->getPortsBlockedForBind(&actualBlockedPorts);
    EXPECT_FALSE(actualBlockedPorts.empty());

    // for (int i = 0; i < std::size(blockedPorts); i++) {
    //     EXPECT_TRUE(std::find(actualBlockedPorts.begin(), actualBlockedPorts.end(),
    //             blockedPorts[i]) != actualBlockedPorts.end());
    // }
    //sort(actualBlockedPorts.begin(), actualBlockedPorts.end());
    EXPECT_EQ(blockedPorts, actualBlockedPorts);
}

TEST_F(ConnectivityNativeBinderTest, UnblockAllPorts) {
    if (portsBlockedOnSystem) return;

    std::vector<int> blockedPorts{1, 100, 1220, 1333, 2700, 5555, 5600};
    for (int i : blockedPorts) mService->blockPortForBind(i);

    std::vector<int32_t> actualBlockedPorts;
    mService->getPortsBlockedForBind(&actualBlockedPorts);
    EXPECT_FALSE(actualBlockedPorts.empty());

    mService->unblockAllPortsForBind();
    mService->getPortsBlockedForBind(&actualBlockedPorts);
    EXPECT_TRUE(actualBlockedPorts.empty());
}

TEST_F(ConnectivityNativeBinderTest, UnblockNegativePort) {
    mService->blockPortForBind(-1);
    // TODO: how to check for exception here?
}

TEST_F(ConnectivityNativeBinderTest, CheckPermission) {
    int curUid = getuid();
    seteuid(FIRST_APPLICATION_UID + 2000);
    mService->blockPortForBind(5555);
    // TODO: how to check for exception here?
    seteuid(curUid);
}
