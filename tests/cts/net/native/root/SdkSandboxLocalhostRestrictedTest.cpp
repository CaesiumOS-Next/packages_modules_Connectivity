#include "gtest/gtest.h"

#include "test_utils.h"
#include <android-base/file.h>
#include <cutils/android_filesystem_config.h>
#include <ifaddrs.h>
#include <regex>
#include <sys/socket.h>

using android::base::unique_fd;

static const std::string TEST_PACKAGENAME = "com.android.cts.verifier";

static const int TEST_UID_SDKSANDBOX = 29998;
static const int TEST_UID_OTHER_SDKSANDBOX = 29997;

class SdkSandboxLocalhostRestrictedTest : public ::testing::Test {};

TEST_F(SdkSandboxLocalhostRestrictedTest,
       SdkSandboxCannotReceiveFromLocalhost) {
  sockaddr_in recvAddr = {.sin_family = AF_INET,
                          .sin_port = htons(0),
                          .sin_addr = {htonl(INADDR_LOOPBACK)}};

  int recvSock;

  {
    ScopedUidChange testUid(TEST_UID_SDKSANDBOX);
    recvSock = socket(AF_INET, SOCK_DGRAM | SOCK_NONBLOCK, 0);
    ASSERT_EQ(0,
              bind(recvSock, (struct sockaddr *)&recvAddr, sizeof(recvAddr)));

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

TEST_F(SdkSandboxLocalhostRestrictedTest, SdkSandboxCannotSendToLocalhost) {
  sockaddr_in localhostAddr = {.sin_family = AF_INET,
                               .sin_port = htons(0),
                               .sin_addr = {htonl(INADDR_LOOPBACK)}};

  // Change uid to a sandboxed process
  ScopedUidChange testUid(TEST_UID_SDKSANDBOX);

  unique_fd sndSock(socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0));
  EXPECT_EQ(
      0, connect(sndSock, (sockaddr *)&localhostAddr, sizeof(localhostAddr)));

  socklen_t sendAddrLen = sizeof(localhostAddr);
  ASSERT_EQ(
      0, getsockname(sndSock, (struct sockaddr *)&localhostAddr, &sendAddrLen));
  int sndPort = ntohs(localhostAddr.sin_port);

  // Sandbox UID shouldn't be able to send packets to localhost socket
  EXPECT_EQ(-1, sendto(sndSock, "foo", sizeof("foo"), 0,
                       (sockaddr *)&localhostAddr, sizeof(localhostAddr)));
}

static bool is_app_uid(int uid) {
  if (uid > 0) {
    return uid >= AID_APP_START && uid <= AID_APP_END;
  } else
    return false;
}

static int to_sdksandbox_uid(int uid) {
  if (is_app_uid(uid)) {
    return uid + (AID_SDK_SANDBOX_PROCESS_START - AID_APP_START);
  } else
    return uid;
}

static std::string popenToString(const std::string &command) {
  std::string ret;

  FILE *fp = popen(command.c_str(), "re");
  if (fp) {
    if (!android::base::ReadFdToString(fileno(fp), &ret))
      ret = "";
    pclose(fp);
  }
  return ret;
}

static int sdksandbox_uid_for_packagename(std::string packagename) {
  std::string uidResult =
      popenToString("pm list packages -U | grep " + packagename);
  int appUid = 0;

  std::regex regex("uid:(\\d+)");
  std::smatch match;

  if (std::regex_search(uidResult, match, regex)) {
    if (match.size() == 2) {
      appUid = std::stoi(match[1].str());
    }
  }
  return to_sdksandbox_uid(appUid);
}

TEST_F(SdkSandboxLocalhostRestrictedTest, SdkSandboxLocalhostAllowedUidSend) {
  int testSdkSandboxUid = sdksandbox_uid_for_packagename(TEST_PACKAGENAME);

  sockaddr_in recvAddr = {.sin_family = AF_INET,
                          .sin_port = htons(0),
                          .sin_addr = {htonl(INADDR_LOOPBACK)}};
  int recvSock;

  recvSock = socket(AF_INET, SOCK_DGRAM | SOCK_NONBLOCK, 0);
  ASSERT_EQ(0, bind(recvSock, (struct sockaddr *)&recvAddr, sizeof(recvAddr)));

  socklen_t recvAddrLen = sizeof(recvAddr);
  ASSERT_EQ(0,
            getsockname(recvSock, (struct sockaddr *)&recvAddr, &recvAddrLen));

  {
    ScopedUidChange testUid(testSdkSandboxUid);
    unique_fd sendSock(socket(AF_INET, SOCK_DGRAM, 0));
    ASSERT_NE(-1, sendto(sendSock, "foo", strlen("foo"), 0,
                         (struct sockaddr *)&recvAddr, sizeof(recvAddr)));
  }

  std::array<char, 1024> buffer;
  socklen_t addrLen = sizeof(recvAddr);
  ASSERT_EQ(3, recvfrom(recvSock, buffer.data(), buffer.size(), 0,
                        (struct sockaddr *)&recvAddr, &addrLen));
  close(recvSock);
}

TEST_F(SdkSandboxLocalhostRestrictedTest,
       SdkSandboxLocalhostAllowedUidReceive) {
  int testSdkSandboxUid = sdksandbox_uid_for_packagename(TEST_PACKAGENAME);

  sockaddr_in recvAddr = {.sin_family = AF_INET,
                          .sin_port = htons(0),
                          .sin_addr = {htonl(INADDR_LOOPBACK)}};
  int recvSock;

  {
    ScopedUidChange testUid(testSdkSandboxUid);
    recvSock = socket(AF_INET, SOCK_DGRAM | SOCK_NONBLOCK, 0);
    ASSERT_EQ(0,
              bind(recvSock, (struct sockaddr *)&recvAddr, sizeof(recvAddr)));

    socklen_t recvAddrLen = sizeof(recvAddr);
    ASSERT_EQ(
        0, getsockname(recvSock, (struct sockaddr *)&recvAddr, &recvAddrLen));
  }

  unique_fd sendSock(socket(AF_INET, SOCK_DGRAM, 0));
  ASSERT_NE(-1, sendto(sendSock, "foo", strlen("foo"), 0,
                       (struct sockaddr *)&recvAddr, sizeof(recvAddr)));

  {
    ScopedUidChange testUid(testSdkSandboxUid);
    std::array<char, 1024> buffer;
    socklen_t addrLen = sizeof(recvAddr);
    ASSERT_EQ(3, recvfrom(recvSock, buffer.data(), buffer.size(), 0,
                          (struct sockaddr *)&recvAddr, &addrLen));
    close(recvSock);
  }
}