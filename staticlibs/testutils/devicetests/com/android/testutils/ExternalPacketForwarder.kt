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
 * limitations under the License
 */

package com.android.testutils

import java.io.FileDescriptor

class ExternalPacketForwarder(
   srcFd: FileDescriptor,
   mtu: Int,
   dstFd: FileDescriptor,
   translationMap: PacketBridge.TranslationMap
) : PacketForwarderBase(srcFd, mtu, dstFd, translationMap) {
   /**
    * Swap addresses, rewrite src port and fix up checksums for packets received on the external
    * interface.
    *
    * Incoming response from external interface which is being forwarded to the internal
    * interface with translated address and port, e.g. 1.2.3.4:0080 -> 8.8.8.8:1234
    * will be translated into 8.8.8.8:80 -> 1.2.3.4:1234
    */
   override fun preparePacketForForwarding(buf: ByteArray, len: Int, version: Int, proto: Int) {
      val transportOffset =
         if (version == 4) {
            PacketReflector.IPV4_HEADER_LENGTH
         } else {
            PacketReflector.IPV6_HEADER_LENGTH
         }
      val srcPort = getPortAt(buf, transportOffset)

      val intAddrInfo = synchronized(mTranslationMap) {
         mTranslationMap.toInternalAddrInfo(srcPort)
      }

      // No matched translation mapping before, skip translation.
      intAddrInfo ?: return

      // Swap source and destination address.
      swapAddresses(buf, version)

      // Copy translated source port.
      setPortAt(intAddrInfo.port, buf, transportOffset)

      // Fix IP and Transport layer checksum.
      fixPacketChecksum(buf, len, version, proto.toByte())
   }
}
