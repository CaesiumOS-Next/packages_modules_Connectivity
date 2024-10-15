#include "gtest/gtest.h"

#include "com_android_net_flags.h"
#include "test_utils.h"
#include <flag_macros.h>
#include <ifaddrs.h>

#define NET_FLAGS com::android::net::flags

using android::base::unique_fd;

static const int TEST_UID_SDKSANDBOX = 29998;

class SdkSandboxLocalhostRestrictedTest : public ::testing::Test {};

TEST_F_WITH_FLAGS(SdkSandboxLocalhostRestrictedTest,
                  SdkSandboxCannotReceiveFromLocalhost,
                  REQUIRES_FLAGS_ENABLED(ACONFIG_FLAG(
                      NET_FLAGS, sdksandbox_localhost_restricted))) {
  sockaddr_in recvAddr = {.sin_family = AF_INET,
                          .sin_port = htons(0),
                          .sin_addr = {htonl(INADDR_LOOPBACK)}};

  int recvSock;

  {
    ScopedUidChange testUid(TEST_UID_SDKSANDBOX);
    recvSock = socket(AF_INET, SOCK_DGRAM | SOCK_NONBLOCK, 0);
    ASSERT_EQ(0,
              bind(recvSock, (struct sockaddr *)&recvAddr, sizeof(recvAddr)));

    // Get the assigned port of the receiving socket
    socklen_t recvAddrLen = sizeof(recvAddr);
    ASSERT_EQ(
        0, getsockname(recvSock, (struct sockaddr *)&recvAddr, &recvAddrLen));
  }

  unique_fd sendSock(socket(AF_INET, SOCK_DGRAM, 0));
  ASSERT_NE(-1, sendto(sendSock, "foo", strlen("foo"), 0,
                       (struct sockaddr *)&recvAddr, sizeof(recvAddr)));

  {
    ScopedUidChange testUid(TEST_UID_SDKSANDBOX);
    std::array<char, 1024> buffer;
    socklen_t addrLen = sizeof(recvAddr);
    ASSERT_EQ(-1, recvfrom(recvSock, buffer.data(), buffer.size(), 0,
                           (struct sockaddr *)&recvAddr, &addrLen));
    close(recvSock);
  }
}

TEST_F_WITH_FLAGS(SdkSandboxLocalhostRestrictedTest,
                  SdkSandboxCannotSendToLocalhost,
                  REQUIRES_FLAGS_ENABLED(ACONFIG_FLAG(
                      NET_FLAGS, sdksandbox_localhost_restricted))) {
  sockaddr_in localhostAddr = {.sin_family = AF_INET,
                               .sin_port = htons(0),
                               .sin_addr = {htonl(INADDR_LOOPBACK)}};

  // Change uid to a sandboxed process
  ScopedUidChange testUid(TEST_UID_SDKSANDBOX);

  unique_fd sndSock(socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0));
  EXPECT_EQ(
      0, connect(sndSock, (sockaddr *)&localhostAddr, sizeof(localhostAddr)));

  // Sandbox UID shouldn't be able to send packets to localhost socket
  EXPECT_EQ(-1, sendto(sndSock, "foo", sizeof("foo"), 0,
                       (sockaddr *)&localhostAddr, sizeof(localhostAddr)));
}