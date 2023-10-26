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

package com.android.net.module.util.netlink;

import static com.android.net.module.util.netlink.StructNlMsgHdr.NLM_F_REQUEST;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A NetlinkMessage subclass for XFRM_MSG_GETSA messages.
 *
 * <p>see also: &lt;linux_src&gt;/include/uapi/linux/xfrm.h
 *
 * <p>XFRM_MSG_GETSA syntax
 *
 * <ul>
 *   <li>TLV: xfrm_usersa_id
 *   <li>Attributes: XFRMA_MARK, XFRMA_SRCADDR
 * </ul>
 *
 * @hide
 */
public class IpSecXfrmNetlinkGetSaMessage extends NetlinkMessage {
    private IpSecStructXfrmUsersaId mIpSecStructXfrmUsersaId;

    private IpSecXfrmNetlinkGetSaMessage(
            @NonNull StructNlMsgHdr header,
            InetAddress destAddress,
            long spi,
            int family,
            short proto) {
        super(header);
        mIpSecStructXfrmUsersaId = new IpSecStructXfrmUsersaId(destAddress, spi, family, proto);
    }

    private IpSecXfrmNetlinkGetSaMessage(@NonNull StructNlMsgHdr header) {
        super(header);
        mIpSecStructXfrmUsersaId = null;
    }

    /**
     * Parse XFRM_MSG_GETSA message from ByteBuffer.
     *
     * @param header netlink message header.
     * @param byteBuffer the ByteBuffer instance that wraps the raw netlink message bytes. MUST be
     *     native order
     */
    @Nullable
    public static IpSecXfrmNetlinkGetSaMessage parse(@NonNull ByteBuffer byteBuffer) {
        final int startPosition = (byteBuffer != null) ? byteBuffer.position() : -1;
        final StructNlMsgHdr nlmsghdr = StructNlMsgHdr.parse(byteBuffer);
        if (nlmsghdr == null) {
            return null;
        }

        final int messageLength = NetlinkConstants.alignedLengthOf(nlmsghdr.nlmsg_len);
        final int payloadLength = messageLength - StructNlMsgHdr.STRUCT_SIZE;
        if (payloadLength < 0 || payloadLength > byteBuffer.remaining()) {
            // Malformed message or runt buffer.  Pretend the buffer was consumed.
            byteBuffer.position(byteBuffer.limit());
            return null;
        }

        final IpSecXfrmNetlinkGetSaMessage msg = new IpSecXfrmNetlinkGetSaMessage(nlmsghdr);

        msg.mIpSecStructXfrmUsersaId =
                IpSecStructXfrmUsersaId.parse(IpSecStructXfrmUsersaId.class, byteBuffer);
        if (msg.mIpSecStructXfrmUsersaId == null) {
            return null;
        }

        return msg;
    }

    /** Write a XFRM_MSG_GETSA message to {@link ByteBuffer}. */
    protected void pack(ByteBuffer byteBuffer) {
        getHeader().pack(byteBuffer);
        mIpSecStructXfrmUsersaId.pack(byteBuffer);
    }

    /** A convenience method to create a XFRM_MSG_GETSA message. */
    public static byte[] newIpSecXfrmNetlinkGetSaMessage(
            int seqNo, @NonNull InetAddress destAddress, long spi, int family, short proto) {

        final int length = StructNlMsgHdr.STRUCT_SIZE + IpSecStructXfrmUsersaId.STRUCT_SIZE;
        final byte[] bytes = new byte[length];
        final ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
        byteBuffer.order(ByteOrder.nativeOrder());

        final StructNlMsgHdr nlmsghdr = new StructNlMsgHdr();
        nlmsghdr.nlmsg_len = length;
        nlmsghdr.nlmsg_type = NetlinkConstants.XFRM_MSG_GETSA;
        nlmsghdr.nlmsg_flags = NLM_F_REQUEST;
        nlmsghdr.nlmsg_seq = seqNo;

        final IpSecXfrmNetlinkGetSaMessage message =
                new IpSecXfrmNetlinkGetSaMessage(nlmsghdr, destAddress, spi, family, proto);
        message.pack(byteBuffer);

        return bytes;
    }

    public IpSecStructXfrmUsersaId getIpSecStructXfrmUsersaId() {
        return mIpSecStructXfrmUsersaId;
    }
}
