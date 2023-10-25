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

#include <chrono>
#include <thread>
#include <unordered_set>
#include <vector>

#include "netdbpf/NetworkTracePoller.h"

using ::testing::AllOf;
using ::testing::Each;
using ::testing::ElementsAre;
using ::testing::Eq;
using ::testing::Field;
using ::testing::Truly;

namespace android {
namespace bpf {
namespace internal {

// Use uint32 max to cause the handler to never Loop. Instead, the tests will
// manually drive things by calling ConsumeAll explicitly.
constexpr uint32_t kNeverPoll = std::numeric_limits<uint32_t>::max();

__be16 bindSocket(int s) {
  sockaddr_in sin = {.sin_family = AF_INET};
  socklen_t len = sizeof(sin);
  if (bind(s, (sockaddr*)&sin, sizeof(sin))) return 0;
  if (getsockname(s, (sockaddr*)&sin, &len)) return 0;
  return sin.sin_port;
}

__be16 bindAndListen(int s) {
  __be16 ret = bindSocket(s);
  if (listen(s, 1)) return 0;
  return ret;
}

// This takes tcp flag constants from the standard library and makes them usable
// with the flags we get from BPF. The standard library flags are big endian
// whereas the BPF flags are reported in host byte order. BPF also trims the
// flags down to the 8 single-bit flag bits (fin, syn, rst, etc).
constexpr inline uint8_t FlagToHost(__be32 be_unix_flags) {
  return ntohl(be_unix_flags) >> 16;
}

class UdpSockets {
 public:
  UdpSockets(size_t num) {
    for (size_t i = 0 ; i < num; i++) {
      sockets_.push_back({});
      Socket& sock = sockets_.back();
      sock.fd.reset(socket(AF_INET, SOCK_DGRAM, 0));
      EXPECT_NE(-1, sock.fd) << "Failed to open server socket";
      sock.port = bindSocket(sock.fd);
      EXPECT_NE(0, sock.port) << "Can't bind to server port";
      sock.addr = {.sin_family = AF_INET, .sin_port = sock.port};
    }
  }

  __be16 port(size_t i) const {
    return sockets_[i].port;
  }

  size_t size() const {
    return sockets_.size();
  }

  // Sends a packet from the socket at port `port(src)` to the socket ad port
  // `port(dst)`. Returns true if successful.
  [[nodiscard]] bool SendPacket(size_t src, size_t dst) {
    if (!IsSocketValid(src) || !IsSocketValid(dst)) {
      return false;
    }
    const char data[] = "abcdefghijklmnopqrstuvwxyz";
    ssize_t sent_len =
        sendto(sockets_[src].fd, data, sizeof data, 0,
               (sockaddr*)&sockets_[dst].addr, sizeof sockets_[dst].addr);
    if (sent_len != sizeof data) {
      return false;
    }

    sockaddr_in recv_addr;
    socklen_t recv_addr_len;
    recv_addr_len = sizeof recv_addr;
    char buff[100];

    ssize_t recv_len = recvfrom(sockets_[dst].fd, buff, sizeof buff, 0,
                                (sockaddr*)&recv_addr, &recv_addr_len);
    if (recv_len != sizeof data) {
      return false;
    }
    return true;
  }

 private:
  bool IsSocketValid(size_t i) {
    return i < sockets_.size() && sockets_[i].fd != -1 && sockets_[i].port != 0;
  }

  struct Socket {
    android::base::unique_fd fd;
    sockaddr_in addr;
    __be16 port;
  };

  std::vector<Socket> sockets_;
};

// Pretty prints all fields for a list of packets (useful for debugging).
struct PacketPrinter {
  const std::vector<PacketTrace>& data;
  static constexpr char kTcpFlagNames[] = "FSRPAUEC";

  friend std::ostream& operator<<(std::ostream& os, const PacketPrinter& d) {
    os << "Packet count: " << d.data.size();
    for (const PacketTrace& info : d.data) {
      os << "\nifidx=" << info.ifindex;
      os << ", len=" << info.length;
      os << ", uid=" << info.uid;
      os << ", tag=" << info.tag;
      os << ", sport=" << info.sport;
      os << ", dport=" << info.dport;
      os << ", direction=" << (info.egress ? "egress" : "ingress");
      os << ", proto=" << static_cast<int>(info.ipProto);
      os << ", ip=" << static_cast<int>(info.ipVersion);
      os << ", flags=";
      for (int i = 0; i < 8; i++) {
        os << ((info.tcpFlags & (1 << i)) ? kTcpFlagNames[i] : '.');
      }
    }
    return os;
  }
};

class NetworkTracePollerTest : public testing::Test {
 protected:
  void SetUp() {
    if (access(PACKET_TRACE_RINGBUF_PATH, R_OK)) {
      GTEST_SKIP() << "Network tracing is not enabled/loaded on this build.";
    }
    if (sizeof(void*) != 8) {
      GTEST_SKIP() << "Network tracing requires 64-bit build.";
    }
  }
};

TEST_F(NetworkTracePollerTest, ConsumerAllWhileInactive) {
  NetworkTracePoller handler([&](const std::vector<PacketTrace>& pkt) {});

  // Only succeed after start and before stop.
  EXPECT_FALSE(handler.ConsumeAll());
  ASSERT_TRUE(handler.Start(kNeverPoll));
  EXPECT_TRUE(handler.ConsumeAll());
  ASSERT_TRUE(handler.Stop());
  EXPECT_FALSE(handler.ConsumeAll());
}

TEST_F(NetworkTracePollerTest, ConsumeAllConcurrentSessions) {
  // Simulate two concurrent sessions (two starts followed by two stops). Check
  // that tracing is stopped only after both sessions finish.
  NetworkTracePoller handler([&](const std::vector<PacketTrace>& pkt) {});

  ASSERT_TRUE(handler.Start(kNeverPoll));
  EXPECT_TRUE(handler.ConsumeAll());

  ASSERT_TRUE(handler.Start(kNeverPoll));
  EXPECT_TRUE(handler.ConsumeAll());

  ASSERT_TRUE(handler.Stop());
  EXPECT_TRUE(handler.ConsumeAll());

  ASSERT_TRUE(handler.Stop());
  EXPECT_FALSE(handler.ConsumeAll());
}

TEST_F(NetworkTracePollerTest, ConsumeAllTraceTcpSession) {
  __be16 server_port = 0;
  std::vector<PacketTrace> packets, unmatched;

  // Record all packets with the bound address and current uid. This callback is
  // involked only within ConsumeAll, at which point the port should have
  // already been filled in and all packets have been processed.
  NetworkTracePoller handler([&](const std::vector<PacketTrace>& pkts) {
    for (const PacketTrace& pkt : pkts) {
      if ((pkt.sport == server_port || pkt.dport == server_port) &&
          pkt.uid == getuid()) {
        packets.push_back(pkt);
      } else {
        // There may be spurious packets not caused by the test. These are only
        // captured so that we can report them to help debug certain errors.
        unmatched.push_back(pkt);
      }
    }
  });

  ASSERT_TRUE(handler.Start(kNeverPoll));
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

    server_port = bindAndListen(serversocket);
    ASSERT_NE(0, server_port) << "Can't bind to server port";

    sockaddr_in addr = {.sin_family = AF_INET, .sin_port = server_port};
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

  // Poll until we get all the packets (typically we get it first try).
  for (int attempt = 0; attempt < 10; attempt++) {
    ASSERT_TRUE(handler.ConsumeAll());
    if (packets.size() >= 12) break;
    std::this_thread::sleep_for(std::chrono::milliseconds(5));
  }

  ASSERT_TRUE(handler.Stop());

  // There are 12 packets in total (6 messages: each seen by client & server):
  // 1. Client connects to server with syn
  // 2. Server responds with syn ack
  // 3. Client responds with ack
  // 4. Client sends data with psh ack
  // 5. Server acks the data packet
  // 6. Client closes connection with fin ack
  ASSERT_EQ(packets.size(), 12)
      << PacketPrinter{packets}
      << "\nUnmatched packets: " << PacketPrinter{unmatched};

  // All packets should be TCP packets.
  EXPECT_THAT(packets, Each(Field(&PacketTrace::ipProto, Eq(IPPROTO_TCP))));

  // Packet 1: client requests connection with server.
  EXPECT_EQ(packets[0].egress, 1) << PacketPrinter{packets};
  EXPECT_EQ(packets[0].dport, server_port) << PacketPrinter{packets};
  EXPECT_EQ(packets[0].tag, kClientTag) << PacketPrinter{packets};
  EXPECT_EQ(packets[0].tcpFlags, FlagToHost(TCP_FLAG_SYN))
      << PacketPrinter{packets};

  // Packet 2: server receives request from client.
  EXPECT_EQ(packets[1].egress, 0) << PacketPrinter{packets};
  EXPECT_EQ(packets[1].dport, server_port) << PacketPrinter{packets};
  EXPECT_EQ(packets[1].tag, kServerTag) << PacketPrinter{packets};
  EXPECT_EQ(packets[1].tcpFlags, FlagToHost(TCP_FLAG_SYN))
      << PacketPrinter{packets};

  // Packet 3: server replies back with syn ack.
  EXPECT_EQ(packets[2].egress, 1) << PacketPrinter{packets};
  EXPECT_EQ(packets[2].sport, server_port) << PacketPrinter{packets};
  EXPECT_EQ(packets[2].tcpFlags, FlagToHost(TCP_FLAG_SYN | TCP_FLAG_ACK))
      << PacketPrinter{packets};

  // Packet 4: client receives the server's syn ack.
  EXPECT_EQ(packets[3].egress, 0) << PacketPrinter{packets};
  EXPECT_EQ(packets[3].sport, server_port) << PacketPrinter{packets};
  EXPECT_EQ(packets[3].tcpFlags, FlagToHost(TCP_FLAG_SYN | TCP_FLAG_ACK))
      << PacketPrinter{packets};
}

TEST_F(NetworkTracePollerTest, StartAndDestruct) {
  std::mutex mu;
  std::condition_variable cond /*GUARDED_BY(mu)*/;
  std::vector<PacketTrace> packets /*GUARDED_BY(mu)*/;

  UdpSockets sockets(2);
  std::unordered_set<__be16> interesting_ports;
  for (size_t i = 0; i < sockets.size(); i++) {
    interesting_ports.insert(sockets.port(i));
  }

  // Record all packets with the bound addresses and current uid.
  auto record_cb = [&](const std::vector<PacketTrace>& pkts) {
    std::scoped_lock<std::mutex> lock(mu);
    cond.notify_all();
    for (const PacketTrace& pkt : pkts) {
      if (pkt.uid == getuid() && pkt.ipProto == IPPROTO_UDP &&
          interesting_ports.count(pkt.sport) == 1 &&
          interesting_ports.count(pkt.dport) == 1) {
        packets.push_back(pkt);
      }
    }
  };

  {
    NetworkTracePoller handler(record_cb);

    ASSERT_TRUE(handler.Start(/*pollMs=*/5));
    // Destruct handler without calling Stop().
  }

  {
    std::scoped_lock<std::mutex> lock(mu);
    packets.clear();
  }

  // This packet should not be seen.
  ASSERT_TRUE(sockets.SendPacket(0, 1));

  NetworkTracePoller handler(record_cb);

  ASSERT_TRUE(handler.Start(/*pollMs=*/5));
  ASSERT_TRUE(handler.Stop());

  std::scoped_lock<std::mutex> lock(mu);

  // The above packet should not be there.
  EXPECT_TRUE(packets.empty());
}

TEST_F(NetworkTracePollerTest, PeriodicConcurrentAndRestart) {
  std::mutex mu;
  std::condition_variable cond /*GUARDED_BY(mu)*/;
  std::vector<PacketTrace> packets /*GUARDED_BY(mu)*/;

  UdpSockets sockets(3);
  std::unordered_set<__be16> interesting_ports;
  for (size_t i = 0; i < sockets.size(); i++) {
    interesting_ports.insert(sockets.port(i));
  }

  // Record all packets with the bound addresses and current uid.
  NetworkTracePoller handler([&](const std::vector<PacketTrace>& pkts) {
    std::scoped_lock<std::mutex> lock(mu);
    cond.notify_all();
    for (const PacketTrace& pkt : pkts) {
      if (pkt.uid == getuid() && pkt.ipProto == IPPROTO_UDP &&
          interesting_ports.count(pkt.sport) == 1 &&
          interesting_ports.count(pkt.dport) == 1) {
        packets.push_back(pkt);
      }
    }
  });

  ASSERT_TRUE(sockets.SendPacket(0, 1));
  ASSERT_TRUE(handler.Start(/*pollMs=*/5));
  ASSERT_TRUE(sockets.SendPacket(0, 2));
  ASSERT_TRUE(handler.Start(/*pollMs=*/5));
  ASSERT_TRUE(sockets.SendPacket(1, 0));
  ASSERT_TRUE(handler.Stop());
  ASSERT_TRUE(sockets.SendPacket(1, 2));
  ASSERT_TRUE(handler.Stop());
  ASSERT_TRUE(sockets.SendPacket(2, 0));
  // Make sure we can restart the handler after it has been stopped.
  ASSERT_TRUE(handler.Start(/*pollMs=*/5));
  ASSERT_TRUE(sockets.SendPacket(2, 1));
  ASSERT_TRUE(handler.Stop());

  {
    std::unique_lock<std::mutex> lock(mu);
    cond.wait_for(lock, std::chrono::milliseconds(100),
                  [&packets] { return packets.size() >= 8; });
  }

  std::scoped_lock<std::mutex> lock(mu);

  auto packet = [&sockets](bool egress, size_t src_socket, size_t dst_socket) {
    return AllOf(
        Truly([egress](const PacketTrace& p) { return p.egress == egress; }),
        Field(&PacketTrace::sport, sockets.port(src_socket)),
        Field(&PacketTrace::dport, sockets.port(dst_socket)));
  };
  // packet(0, 1) is sent before the first start, so it should not be seen.
  // packet(2, 0) is sent after the second stop, so it should not be seen.
  EXPECT_THAT(packets, ElementsAre(packet(true, 0, 2), packet(false, 0, 2),
                                   packet(true, 1, 0), packet(false, 1, 0),
                                   packet(true, 1, 2), packet(false, 1, 2),
                                   packet(true, 2, 1), packet(false, 2, 1)))
      << PacketPrinter{packets};
}

}  // namespace internal
}  // namespace bpf
}  // namespace android
