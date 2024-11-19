/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include <android-base/unique_fd.h>
#include <android/multinetwork.h>
#include <arpa/inet.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <inttypes.h>
#include <net/if.h>
#include <netinet/tcp.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

#include "libsatellitenetworkstats/SatelliteBufferEventPoller.h"

using ::testing::Eq;
using ::testing::Test;

namespace android {
namespace bpf {
namespace internal {

// Global variable to store sockaddr_in
sockaddr_in global_sin;

// Global server socket
int server_socket = -1;

// Global client socket
int client_socket = -1;

uint32_t *satelliteConfig;
// To create pipe
int readfd = -1;
int writefd = -1;

// To get instance
SatelliteBufferEventPoller *pObj;

sockaddr_in bindAndListen(int s) {
  sockaddr_in sin = {.sin_family = AF_INET};
  socklen_t len = sizeof(sin);
  if (bind(s, (sockaddr *)&sin, sizeof(sin))) {
    return sockaddr_in{};
  }
  if (listen(s, 1)) {
    return sockaddr_in{};
  }
  if (getsockname(s, (sockaddr *)&sin, &len)) {
    return sockaddr_in{};
  }
  return sin;
}

void bindSocket() {
  const uint32_t kClientTag = 2468;
  const uint32_t kServerTag = 1357;
  server_socket = socket(AF_INET, SOCK_STREAM, 0);
  ASSERT_NE(-1, server_socket) << "Failed to open server socket";
  ASSERT_EQ(android_tag_socket(server_socket, kServerTag), 0);

  client_socket = socket(AF_INET, SOCK_STREAM, 0);
  ASSERT_NE(-1, client_socket) << "Failed to open client socket";
  ASSERT_EQ(android_tag_socket(client_socket, kClientTag), 0);

  // Bind the socket
  global_sin = bindAndListen(server_socket);
}

int findInterfaceIndex(struct sockaddr_in sin) {
  char ip_str[INET_ADDRSTRLEN];
  inet_ntop(AF_INET, &(sin.sin_addr), ip_str, INET_ADDRSTRLEN);

  struct if_nameindex *if_nidxs = if_nameindex();
  if (if_nidxs != NULL) {
    for (int i = 0; if_nidxs[i].if_index != 0; i++) {
      struct ifreq ifr;
      strcpy(ifr.ifr_name, if_nidxs[i].if_name);
      if (ioctl(server_socket, SIOCGIFADDR, &ifr) == 0) {
        struct sockaddr_in *if_addr = (struct sockaddr_in *)&ifr.ifr_addr;
        char if_ip_str[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &(if_addr->sin_addr), if_ip_str, INET_ADDRSTRLEN);
        if (strcmp(ip_str, if_ip_str) == 0) {
          int index = if_nidxs[i].if_index;
          if_freenameindex(if_nidxs);
          return index; // Return the interface index
        }
      }
    }
    if_freenameindex(if_nidxs);
  }
  return -1; // Return -1 if no matching interface is found
}

// Function to create a pipe and return read and write file descriptors
std::pair<int, int> createPipe() {
  int pipefd[2];
  if (pipe(pipefd) == -1) {
    perror("pipe");
    return std::make_pair(-1, -1); // Return -1 on error
  }
  return std::make_pair(pipefd[0], pipefd[1]);
}

void createPacketSession() {
  ASSERT_EQ(0,
            connect(client_socket, (sockaddr *)&global_sin, sizeof(global_sin)))
      << "connect to loopback failed: " << strerror(errno);

  int accepted = accept(server_socket, nullptr, nullptr);
  ASSERT_NE(-1, accepted) << "accept connection failed: " << strerror(errno);

  const char data[] =
      "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrst"
      "uvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmn"
      "opqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz";
  EXPECT_EQ(send(client_socket, data, sizeof(data), 0), sizeof(data))
      << "failed to send message: " << strerror(errno);

  char buff[250] = {};
  EXPECT_EQ(recv(accepted, buff, sizeof(buff), 0), sizeof(data))
      << "failed to receive message: " << strerror(errno);

  EXPECT_EQ(std::string(data), std::string(buff));
}

void checkUidBlockedUpdates(int fd, int uid) {
  ASSERT_NE(0, uid) << "UID should not be 0";
  std::array<int, 1> expectedArray = {uid};
  std::array<int, 1> buffer;
  int bytesRead = read(fd, buffer.data(), sizeof(buffer));

  ASSERT_NE(-1, bytesRead) << "bytes read from pipe invalid: "
                           << strerror(errno);
  ASSERT_EQ(bytesRead, sizeof(buffer))
      << "bytes read not matched: " << strerror(errno);
  // Compare the buffer with the expected array
  ASSERT_EQ(buffer[0], expectedArray[0])
      << "expected uid not received: " << strerror(errno);
}

std::vector<SatelliteEvent> createEvents(uint32_t uid, int numEvents) {
  std::vector<SatelliteEvent> events;

  // Populate the vector with the specified number of events
  for (int i = 0; i < numEvents; ++i) {
    SatelliteEvent event;
    event.uid = uid;
    event.rx_bytes = 10; // Adjust as needed
    event.tx_bytes = 10; // Adjust as needed
    events.push_back(event);
  }

  return events;
}

class SatelliteBufferEventPollerTest : public testing::Test {
protected:
  void SetUp() {
    if (access(SATELLITE_CONFIG_MAP_PATH, R_OK)) {
      GTEST_SKIP() << "satellite config is not enabled/loaded on this build.";
    }
    if (access(SATELLITE_EVENT_RINGBUF_PATH, R_OK)) {
      GTEST_SKIP() << "satellite event is not enabled/loaded on this build.";
    }
    bindSocket();

    // TODO to identify the interface index to check further
    int ifindex = findInterfaceIndex(global_sin); // Currently passed -1

    // Initialise Satellite Event Config
    satelliteConfig =
        new uint32_t[14];     // Config including Polling Count(In Secs) +
                              // Max data usage allowed satelliteEvent config
    satelliteConfig[0] = 1;   // polling count
    satelliteConfig[1] = 150; // max data usage allowed
    satelliteConfig[3] = ifindex;
    satelliteConfig[4] = ifindex;

    // Create pipe
    std::pair<int, int> pipefds = createPipe();

    readfd = pipefds.first;
    ASSERT_NE(-1, readfd) << "invalid read fd: " << strerror(errno);

    writefd = pipefds.second;
    ASSERT_NE(-1, writefd) << "invalid write fd: " << strerror(errno);

    // get SatelliteBufferEventPoller instance
    pObj = SatelliteBufferEventPoller::getInstance();
  }
};

TEST_F(SatelliteBufferEventPollerTest, startSatelliteBufferEventMonitoring) {
  // Initialise bpf maps
  pObj->initSatelliteBpfMap();

  // Start startSatelliteBufferEventMonitoring method in a new thread
  std::thread mWorker([&]() {
    pObj->startSatelliteBufferEventMonitoring(satelliteConfig, writefd);
  });
  mWorker.detach();

  // Start packet transaction
  // TODO : Uncomment below code once interface index finding issue is fixed
  // createPacketSession();

  // Wait until polling, reading satellite buffer event from the ring buffer and
  // updating of to be blocked uids to pipe
  std::this_thread::sleep_for(std::chrono::seconds(3));
  // verify if ConsumeAllLocked() called on polling expiry
  ASSERT_TRUE(pObj->ConsumeAllLocked());

  // Function to check if the pipe contains, to be blocked uid updated by write
  // fd
  // TODO : Uncomment below code once interface index finding issue is fixed
  // checkUidBlockedUpdates(readfd, getuid());

  // Stop buffer event
  pObj->stopSatelliteBufferEvent();

  // Close pipe and socket after test
  close(readfd);
  close(writefd);
  close(client_socket);
  close(server_socket);
  delete satelliteConfig; // Deallocate memory after the thread is done
}

// TODO: This test may be not required once interface index finding issue is
// fixed, Reason: The code is by default tested with
// startSatelliteBufferEventMonitoring() test
TEST_F(SatelliteBufferEventPollerTest, updatePipeWithDataOverUsageCheck) {
  // Initialise bpf maps
  pObj->initSatelliteBpfMap();

  // Start startSatelliteBufferEventMonitoring method in a new thread
  std::thread mWorker([&]() {
    pObj->startSatelliteBufferEventMonitoring(satelliteConfig, writefd);
  });
  mWorker.detach();

  // Build satellite buffer events for validating updatePipeForDataOverUsage()
  // method with test uid
  std::vector<SatelliteEvent> events =
      createEvents(12345 /*uid*/, 10 /*numEvents*/);
  pObj->updatePipeWithDataOverUsageCheck(events);
  checkUidBlockedUpdates(readfd, 12345 /*uid*/);

  // Stop buffer event
  pObj->stopSatelliteBufferEvent();
  // Close pipe and socket after test
  close(readfd);
  close(writefd);
  close(client_socket);
  close(server_socket);
  delete satelliteConfig; // Deallocate memory after the thread is done
}

} // namespace internal
} // namespace bpf
} // namespace android