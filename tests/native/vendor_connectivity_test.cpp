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

#include "android/net/VendorConnectivity.h"
#include "binder/IServiceManager.h"


namespace binder = android::binder;

using android::IBinder;
using android::IServiceManager;
using android::sp;
using android::String16;
using android::base::unique_fd;
using android::net::IVendorConnectivity;

class VendorConnectivityBinderTest : public ::testing::Test {
  public:
    VendorConnectivityBinderTest() {
        sp<IServiceManager> sm = android::defaultServiceManager();
        sp<IBinder> binder = sm->getService(String16("vendor_connectivity"));
        if (binder != nullptr) {
            mService = android::interface_cast<IVendorConnectivity>(binder);
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
    sp<IVendorConnectivity> mService;
};

TEST_F(VendorConnectivityBinderTest, BlockPorts) {
    int enable = 1;
    std::vector<int32_t> blockedPorts;

    const int sock = socket(AF_INET, SOCK_DGRAM, 0);
    setsockopt(sock, SOL_SOCKET, SO_REUSEPORT, &enable, sizeof(enable));

    struct sockaddr_in addr1 = { .sin_family = AF_INET };
    bind(sock, (struct sockaddr *)&addr1, sizeof(addr1));

    struct sockaddr_in sin;
    socklen_t len = sizeof(sin);
    if (getsockname(sock, (struct sockaddr *)&sin, &len) == -1)
        fprintf(stderr, "getsockname");
    else
        fprintf(stderr, "port number %d or %d\n", ntohs(sin.sin_port), sin.sin_port);

    const int sock2 = socket(AF_INET, SOCK_DGRAM, 0);
    setsockopt(sock2, SOL_SOCKET, SO_REUSEPORT, &enable, sizeof(enable));
    struct sockaddr_in addr2 = { .sin_family = AF_INET, .sin_port = sin.sin_port};
    ASSERT_EQ(0, bind(sock2, (struct sockaddr *) &addr2, sizeof(addr2)));

    if (getsockname(sock2, (struct sockaddr *)&sin, &len) == -1)
        fprintf(stderr, "getsockname");
    else
        fprintf(stderr, "port number %d or %d\n", ntohs(sin.sin_port), sin.sin_port);

    int blockedPort = ntohs(sin.sin_port);
    mService->blockPort(blockedPort);

    // Sleep is necessary to allow AIDL -> BpfMap update, before creating new socket.
    sleep(1);

    const int sock3 = socket(AF_INET, SOCK_DGRAM, 0);
    setsockopt(sock2, SOL_SOCKET, SO_REUSEPORT, &enable, sizeof(enable)); // Should this be necessary?
    struct sockaddr_in addr3 = { .sin_family = AF_INET, .sin_port = sin.sin_port};
    ASSERT_NE(0, bind(sock3, (struct sockaddr *) &addr3, sizeof(addr3)));

    close(sock);
    close(sock2);
    close(sock3);
    mService->unblockPort(blockedPort);
}
