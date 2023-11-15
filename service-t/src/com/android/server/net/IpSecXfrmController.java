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
package com.android.server.net;

import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.AF_INET6;

import static com.android.net.module.util.netlink.IpSecXfrmNetlinkMessage.IPPROTO_ESP;
import static com.android.net.module.util.netlink.IpSecXfrmNetlinkMessage.NETLINK_XFRM;
import static com.android.net.module.util.netlink.NetlinkConstants.XFRM_MSG_NEWSA;

import android.system.ErrnoException;

import androidx.annotation.NonNull;

import com.android.net.module.util.netlink.IpSecXfrmNetlinkGetSaMessage;
import com.android.net.module.util.netlink.IpSecXfrmNetlinkMessage;
import com.android.net.module.util.netlink.IpSecXfrmNetlinkNewSaMessage;
import com.android.net.module.util.netlink.NetlinkMessage;
import com.android.net.module.util.netlink.NetlinkUtils;
import com.android.net.module.util.netlink.StructNlMsgHdr;

import libcore.io.IoUtils;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;

public class IpSecXfrmController {
    private static final int TIMEOUT_MS = 500;
    public static final int DEFAULT_RECV_BUFSIZE = 8 * 1024;

    public IpSecXfrmNetlinkNewSaMessage ipSecGetSa(@NonNull InetAddress destAddress, long spi)
            throws ErrnoException, SocketException, InterruptedIOException, IOException {

        final int family = destAddress instanceof Inet4Address ? AF_INET : AF_INET6;

        final byte[] req =
                IpSecXfrmNetlinkGetSaMessage.newIpSecXfrmNetlinkGetSaMessage(
                        destAddress, spi, family, (short) IPPROTO_ESP);

        FileDescriptor fd = null;
        try {
            fd = NetlinkUtils.netlinkSocketForProto(NETLINK_XFRM);
            NetlinkUtils.connectToKernel(fd);
            NetlinkUtils.sendMessage(fd, req, 0, req.length, TIMEOUT_MS);
            ByteBuffer response = NetlinkUtils.recvMessage(fd, DEFAULT_RECV_BUFSIZE, TIMEOUT_MS);

            final NetlinkMessage msg = IpSecXfrmNetlinkMessage.parse(response, NETLINK_XFRM);
            if (msg == null) {
                return null;
            }

            final StructNlMsgHdr hdr = msg.getHeader();
            if (hdr.nlmsg_type == XFRM_MSG_NEWSA) {
                return (IpSecXfrmNetlinkNewSaMessage) msg;
            }

            return null;
        } catch (IllegalArgumentException exception) {
            // Maybe thrown from Struct.parse
            throw new IOException("Failed to parse the response " + exception);
        } finally {
            if (fd != null) {
                IoUtils.closeQuietly(fd);
            }
        }
    }
}
