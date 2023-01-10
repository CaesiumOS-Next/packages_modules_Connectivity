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

#include <vector>

#include "netdbpf/NetworkTraceHandler.h"

using ::testing::AllOf;
using ::testing::AnyOf;
using ::testing::Each;
using ::testing::Eq;
using ::testing::Field;
using ::testing::Test;

namespace android {
namespace bpf {

uint16_t bindAndListen(int s) {
  for (int i = 0; i < 10; i++) {
    uint16_t port = 1024 + arc4random_uniform(0xffff - 1024);
    sockaddr_in sin = {.sin_family = AF_INET, .sin_port = htons(port)};
    if (bind(s, (sockaddr*)&sin, sizeof(sin)) == 0) {
      listen(s, 1);
      return port;
    }
  }
  return 0;
}

// This takes tcp flag constants from the standard library and makes them usable
// with the flags we get from BPF. The standard library flags are big endian
// whereas the BPF flags are reported in host byte order. BPF also trims the
// flags down to the 8 single-bit flag bits (fin, syn, rst, etc).
uint8_t FlagToHost(uint32_t be_unix_flags) {
  return ntohl(be_unix_flags) >> 16;
}

// Pretty prints all fields for a list of packets (useful for debugging).
struct PacketPrinter {
  std::vector<PacketInfo>* data;
  static constexpr char kBitNames[] = "fsrpauec";

  friend std::ostream& operator<<(std::ostream& os, const PacketPrinter& d) {
    os << "Packet count: " << d.data->size();
    for (PacketInfo& info : *d.data) {
      os << "\nifindex=" << info.ifindex;
      os << ", length=" << info.length;
      os << ", uid=" << info.uid;
      os << ", tag=" << info.tag;
      os << ", localPort=" << info.localPort;
      os << ", remotePort=" << info.remotePort;
      os << ", egress=" << info.egress;
      os << ", ipProto=" << static_cast<int>(info.ipProto);
      os << ", tcpFlags=";
      for (int i = 0; i < 8; i++) {
        os << ((info.tcpFlags & (1 << i)) ? kBitNames[i] : '.');
      }
    }
    return os;
  }
};

class NetworkTraceHandlerTest : public testing::Test {
 protected:
  void SetUp() {
    if (access(PACKET_INFO_MAP_PATH, R_OK) != 0) {
      GTEST_SKIP() << "Network tracing is not enabled/loaded on this build";
    }
    ASSERT_EQ(0, setrlimitForTest());
  }
};

TEST_F(NetworkTraceHandlerTest, PollWhileInactive) {
  NetworkTraceHandler handler([&](PacketInfo* pkt) {});

  // One succeed after start and before stop.
  EXPECT_FALSE(handler.PollOnce());
  ASSERT_TRUE(handler.Start());
  EXPECT_TRUE(handler.PollOnce());
  ASSERT_TRUE(handler.Stop());
  EXPECT_FALSE(handler.PollOnce());
}

TEST_F(NetworkTraceHandlerTest, TraceTcpSession) {
  uint16_t port = 0;
  std::vector<PacketInfo> packets;

  // Record all packets with the bound address and current uid. This callback is
  // involked only within PollOnce, at which point the port should have already
  // been filled in and all packets have been processed.
  NetworkTraceHandler handler([&](PacketInfo* pkt) {
    if ((pkt->localPort == port || pkt->remotePort == port) &&
        (pkt->uid == getuid())) {
      packets.push_back(*pkt);
    }
  });

  ASSERT_TRUE(handler.Start());
  const uint32_t kClientTag = 2468;
  const uint32_t kServerTag = 1357;

  // Go through a typical connection sequence between two v4 sockets using tcp.
  // This covers connection handshake, shutdown, and one data packet.
  {
    android::base::unique_fd clientsocket(socket(AF_INET, SOCK_STREAM, 0));
    ASSERT_NE(-1, clientsocket) << "Failed to open client socket";
    ASSERT_EQ(android_tag_socket(clientsocket, kClientTag), 0);

    android::base::unique_fd serversocket(socket(AF_INET, SOCK_STREAM, 0));
    ASSERT_NE(-1, serversocket) << "Failed to open server socket";
    ASSERT_EQ(android_tag_socket(serversocket, kServerTag), 0);

    port = bindAndListen(serversocket);
    ASSERT_NE(0, port) << "Can't bind to server port";
    port = htons(port);  // sockaddr and PacketInfo use network order.

    sockaddr_in addr = {.sin_family = AF_INET, .sin_port = port};
    ASSERT_EQ(0, connect(clientsocket, (sockaddr*)&addr, sizeof(addr)))
        << "connect to loopback failed: " << strerror(errno);

    int accepted = accept(serversocket, nullptr, nullptr);
    ASSERT_NE(-1, accepted) << "accept connection failed: " << strerror(errno);

    const char data[] = "abcdefghijklmnopqrstuvwxyz";
    EXPECT_EQ(send(clientsocket, data, sizeof(data), 0), sizeof(data))
        << "failed to send message: " << strerror(errno);

    char buff[100] = {};
    EXPECT_EQ(recv(accepted, buff, sizeof(buff), 0), sizeof(data))
        << "failed to receive message: " << strerror(errno);

    EXPECT_EQ(std::string(data), std::string(buff));
  }

  ASSERT_TRUE(handler.PollOnce());
  ASSERT_TRUE(handler.Stop());

  // There are 12 packets in total (6 messages: each seen by client & server):
  // 1. Client connects to server with syn
  // 2. Server responds with syn ack
  // 3. Client responds with ack
  // 4. Client sends data with psh ack
  // 5. Server acks the data packet
  // 6. Client closes connection with fin ack
  ASSERT_EQ(packets.size(), 12) << PacketPrinter{&packets};

  // All packets should be TCP packets.
  EXPECT_THAT(packets, Each(Field(&PacketInfo::ipProto, Eq(IPPROTO_TCP))));

  // Packet 1: client requests connection with server.
  EXPECT_EQ(packets[0].egress, 1) << PacketPrinter{&packets};
  EXPECT_EQ(packets[0].remotePort, port) << PacketPrinter{&packets};
  EXPECT_EQ(packets[0].tag, kClientTag) << PacketPrinter{&packets};
  EXPECT_EQ(packets[0].tcpFlags, FlagToHost(TCP_FLAG_SYN))
      << PacketPrinter{&packets};

  // Packet 2: server receives request from client.
  EXPECT_EQ(packets[1].egress, 0) << PacketPrinter{&packets};
  EXPECT_EQ(packets[1].localPort, port) << PacketPrinter{&packets};
  EXPECT_EQ(packets[1].tag, kServerTag) << PacketPrinter{&packets};
  EXPECT_EQ(packets[1].tcpFlags, FlagToHost(TCP_FLAG_SYN))
      << PacketPrinter{&packets};

  // Packet 3: server replies back with syn ack.
  EXPECT_EQ(packets[2].egress, 1) << PacketPrinter{&packets};
  EXPECT_EQ(packets[2].localPort, port) << PacketPrinter{&packets};
  EXPECT_EQ(packets[2].tcpFlags, FlagToHost(TCP_FLAG_SYN | TCP_FLAG_ACK))
      << PacketPrinter{&packets};

  // Packet 4: client receives the server's syn ack.
  EXPECT_EQ(packets[3].egress, 0) << PacketPrinter{&packets};
  EXPECT_EQ(packets[3].remotePort, port) << PacketPrinter{&packets};
  EXPECT_EQ(packets[3].tcpFlags, FlagToHost(TCP_FLAG_SYN | TCP_FLAG_ACK))
      << PacketPrinter{&packets};
}

}  // namespace bpf
}  // namespace android
