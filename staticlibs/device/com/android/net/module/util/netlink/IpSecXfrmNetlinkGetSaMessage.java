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

import com.android.net.module.util.Struct;

import java.net.InetAddress;
import java.nio.ByteBuffer;

/**
 * An IpSecXfrmNetlinkMessage subclass for XFRM_MSG_GETSA messages.
 *
 * <p>see include/uapi/linux/xfrm.h
 *
 * <p>XFRM_MSG_GETSA syntax
 *
 * <ul>
 *   <li>TLV: xfrm_usersa_id
 *   <li>Optional Attributes: XFRMA_MARK, XFRMA_SRCADDR
 * </ul>
 *
 * @hide
 */
public class IpSecXfrmNetlinkGetSaMessage extends IpSecXfrmNetlinkMessage {
    private IpSecStructXfrmUsersaId mIpSecStructXfrmUsersaId;

    private IpSecXfrmNetlinkGetSaMessage(@NonNull StructNlMsgHdr header) {
        super(header);
    }

    private IpSecXfrmNetlinkGetSaMessage(
            @NonNull StructNlMsgHdr header,
            @NonNull InetAddress destAddress,
            long spi,
            int family,
            short proto) {
        this(header);
        mIpSecStructXfrmUsersaId = new IpSecStructXfrmUsersaId(destAddress, spi, family, proto);
    }

    @Override
    protected void packPayload(@NonNull ByteBuffer byteBuffer) {
        mIpSecStructXfrmUsersaId.writeToByteBuffer(byteBuffer);
    }

    /**
     * Parse XFRM_MSG_GETSA message from ByteBuffer.
     *
     * @param nlmsghdr netlink message header.
     * @param byteBuffer the ByteBuffer instance that wraps the raw netlink message bytes. MUST be
     *     host order
     */
    @Nullable
    public static IpSecXfrmNetlinkGetSaMessage parse(
            @NonNull StructNlMsgHdr nlmsghdr, @NonNull ByteBuffer byteBuffer) {
        final IpSecXfrmNetlinkGetSaMessage msg = new IpSecXfrmNetlinkGetSaMessage(nlmsghdr);

        msg.mIpSecStructXfrmUsersaId = Struct.parse(IpSecStructXfrmUsersaId.class, byteBuffer);
        if (msg.mIpSecStructXfrmUsersaId == null) {
            return null;
        }

        final int payloadLen = nlmsghdr.nlmsg_len - StructNlMsgHdr.STRUCT_SIZE;

        // Attributes not supported. Perform basic parsing and validation.
        int parsedLen = IpSecStructXfrmUsersaId.STRUCT_SIZE;
        while (parsedLen < payloadLen) {
            final StructNlAttr attr = StructNlAttr.parse(byteBuffer);
            parsedLen += attr.nla_len;
        }

        return msg;
    }

    /** A convenience method to create a XFRM_MSG_GETSA message. */
    public static byte[] newIpSecXfrmNetlinkGetSaMessage(
            @NonNull InetAddress destAddress, long spi, int family, short proto) {
        final int payloadLen = IpSecStructXfrmUsersaId.STRUCT_SIZE;

        final StructNlMsgHdr nlmsghdr = newXfrmNlMsgHdr(payloadLen, XFRM_MSG_GETSA, NLM_F_REQUEST);
        final IpSecXfrmNetlinkGetSaMessage message =
                new IpSecXfrmNetlinkGetSaMessage(nlmsghdr, destAddress, spi, family, proto);

        final ByteBuffer byteBuffer = newXfrmNlMsgByteBuffer(payloadLen);
        message.pack(byteBuffer);

        return byteBuffer.array();
    }

    public IpSecStructXfrmUsersaId getIpSecStructXfrmUsersaId() {
        return mIpSecStructXfrmUsersaId;
    }
}
