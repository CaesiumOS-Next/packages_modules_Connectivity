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

class InternalPacketForwarder(
    srcFd: FileDescriptor,
    mtu: Int,
    dstFd: FileDescriptor,
    translationMap: PacketBridge.TranslationMap
) : PacketForwarderBase(srcFd, mtu, dstFd, translationMap) {
    /**
     * Swap addresses, rewrite dst port and fix up checksums for packets received on the internal
     * interface.
     *
     * Outgoing packet from the internal interface which is being forwarded to the
     * external interface with translated address and port, e.g. 1.2.3.4:1234 -> 8.8.8.8:80
     * will be translated into 8.8.8.8:1234 -> 1.2.3.4:8080
     */
    override fun preparePacketForForwarding(buf: ByteArray, len: Int, version: Int, proto: Int) {
        val transportOffset =
            if (version == 4) {
                PacketReflector.IPV4_HEADER_LENGTH
            } else {
                PacketReflector.IPV6_HEADER_LENGTH
            }
        val (addrPos, addrLen) = getAddressPositionAndLength(version)

        val srcAddr = getInetAddressAt(buf, addrPos, addrLen)
        val dstPort = getPortAt(buf, transportOffset + DESTINATION_PORT_OFFSET)

        val extPort = synchronized(mTranslationMap) {
            mTranslationMap.toExternalPort(srcAddr, dstPort)
        }

        // No translation needed
        extPort ?: return

        // Swap source and destination addresses
        swapAddresses(buf, version)

        // Translate destination port
        setPortAt(extPort, buf, transportOffset + DESTINATION_PORT_OFFSET)

        // Fix IP and Transport layer checksum.
        fixPacketChecksum(buf, len, version, proto.toByte())
    }
}
