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

package android.net.thread.utils

import android.net.thread.utils.IntegrationTestUtils.pollForPacket
import android.system.OsConstants.IPPROTO_IP
import android.system.OsConstants.IPPROTO_UDP
import com.android.net.module.util.PacketBuilder
import com.android.net.module.util.Struct
import com.android.net.module.util.structs.Ipv4Header
import com.android.net.module.util.structs.UdpHeader
import com.android.testutils.PollPacketReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * A class that simulates a UDP echo server that replies to incoming UDP message with the same
 * payload.
 *
 * @param packetReader the packet reader to poll UDP requests from
 * @param serverAddress the address and port of the UDP server
 */
class TestUdpEchoServer(
  private val packetReader: PollPacketReader,
  private val serverAddress: InetSocketAddress,
) {
  private val TAG = TestUdpEchoServer::class.java.simpleName
  private var workerThread: Thread? = null

  /**
   * Starts the UDP echo server to respond to UDP requests.
   *
   * <p> The server polls the UDP requests from the {@code packetReader} and responds with the same
   * payload. The server will automatically stop when it fails to poll a UDP request within the
   * timeout (3000 ms, as defined in IntegrationTestUtils).
   */
  fun start() {
    workerThread = thread {
      var requestPacket: ByteArray
      while (true) {
        requestPacket = pollForUdpPacket() ?: break
        val buf = ByteBuffer.wrap(requestPacket)
        packetReader.sendResponse(buildUdpResponse(buf))
      }
    }
  }

  /** Stops the UDP echo server. */
  fun stop() {
    workerThread?.join()
  }

  private fun pollForUdpPacket(): ByteArray? {
    val filter =
      fun(packet: ByteArray): Boolean {
        val buf = ByteBuffer.wrap(packet)
        val ipv4Header = Struct.parse(Ipv4Header::class.java, buf) ?: return false
        val udpHeader = Struct.parse(UdpHeader::class.java, buf) ?: return false
        return ipv4Header.dstIp == serverAddress.address && udpHeader.dstPort == serverAddress.port
      }
    return pollForPacket(packetReader, filter)
  }

  private fun buildUdpResponse(
    requestPacket: ByteBuffer,
  ): ByteBuffer? {
    val requestIpv4Header = Struct.parse(Ipv4Header::class.java, requestPacket) ?: return null
    val requestUdpHeader = Struct.parse(UdpHeader::class.java, requestPacket) ?: return null
    val remainingRequestPacket = ByteArray(requestPacket.remaining())
    requestPacket.get(remainingRequestPacket)

    val buf =
      PacketBuilder.allocate(
        false /* hasEther */,
        IPPROTO_IP,
        IPPROTO_UDP,
        remainingRequestPacket.size,
      )

    val packetBuilder = PacketBuilder(buf)
    packetBuilder.writeIpv4Header(
      requestIpv4Header.tos,
      requestIpv4Header.id,
      requestIpv4Header.flagsAndFragmentOffset,
      0x40 /* ttl */,
      IPPROTO_UDP.toByte(),
      requestIpv4Header.dstIp, /* srcIp */
      requestIpv4Header.srcIp, /* dstIp */
    )
    packetBuilder.writeUdpHeader(
      requestUdpHeader.dstPort.toShort() /* srcPort */,
      requestUdpHeader.srcPort.toShort(), /* dstPort */
    )
    buf.put(remainingRequestPacket)

    return packetBuilder.finalizePacket()
  }
}
